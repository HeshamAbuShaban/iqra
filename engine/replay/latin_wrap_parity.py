#!/usr/bin/env python3
"""
Every Latin string on a right-to-left screen is wrapped in `ltr`.

Both Arabic screens - Live and Choose - are wrapped in
`LocalLayoutDirection = Rtl`, which is right: their content is Arabic and the
gutter has to move. It also hands every English string a right-to-left paragraph,
and a TRAILING NEUTRAL character then resolves to that paragraph and is laid out
on the left. "Let me think…" renders as "…Let me think". "Which part?" rendered
as "?Which part". Nothing else in the string is wrong, so it looks like a font
bug or a bad string until you look at where the punctuation went.

Material3's `Text` has no `textDirection` parameter, so the fix cannot live in
the composable. It lives in the string, as a Unicode bidi isolate - LRI/PDI,
U+2066 and U+2069 - via `ltr()`.

This check exists because the wrapper was dropped once already. Adding
`LiveState.Paused` meant rewriting that whole `Text(` block, the rewrite did not
carry `ltr(` with it, and the result compiled, passed every other check, and
shipped with "…Let me think" on the screen. A missing isolate looks exactly like
a present one. Nothing else in this repository would have noticed.

So the rule is mechanical and total: in a screen wrapped in RTL, a `Text` whose
argument is Latin carries `ltr(`. The exceptions are named explicitly - mushaf
text, which is already right-to-left and must not have invisible characters put
around the words being recited.

The trailing-neutral rule, stated once so it can be applied by reading:

  A string ending in a NEUTRAL character (. ? ! , : ; …) in an RTL paragraph
  puts that character on the LEFT. A string ending in a letter or a digit is
  unaffected, which is why this went unnoticed for so long - most of the chrome
  is fine and the few that are not are the ones that read as a joke.
"""
import re
import sys
from pathlib import Path

UI = Path("android/app/src/main/java/com/iqra/quran/ui")

# Mushaf-derived text. Right-to-left already; wrapping would achieve nothing and
# would put invisible codepoints around the words being recited.
MUSHAF = (
    "v.textUthmani",
    "s.name,", "s.name", "text,", "text =", "quranFont",
    "info?.name",            # the Arabic surah name
)
# Non-Text composables that take a label/glyph argument, already wrapped.
SINK = ("fun SwitchRow", "fun SettingDivider", "fun SettingsSection")

NEUTRAL = ".?!,:;…"


def rtl_wrapped(path: Path) -> bool:
    return "LocalLayoutDirection provides" in path.read_text(encoding="utf-8")


def first_arg(block: str, open_idx: int) -> str:
    """The first argument of a call, respecting strings and comments.

    The whole balanced block, not one line: `Text(` alone on its line has its
    argument on the next, and a multi-line `when` or concatenation runs further
    still. Reading only the next line is what let the real regression survive -
    `when (state) {` contains no quote, so a one-line check saw nothing wrong.
    """
    i = open_idx + 1
    depth = 0
    stack = []
    n = len(block)
    while i < n:
        c = block[i]
        if stack and stack[-1] == "line":
            if c == "\n":
                stack.pop()
            i += 1
            continue
        if stack and stack[-1] == "str":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                stack.pop()
            elif block.startswith("${", i):
                stack.append("line")
            i += 1
            continue
        if block.startswith("//", i):
            stack.append("line")
            i += 2
            continue
        if c == '"':
            stack.append("str")
            i += 1
            continue
        if c == "(":
            depth += 1
            i += 1
            continue
        if c == ")":
            if depth == 0:
                return block[open_idx + 1:i]
            depth -= 1
            i += 1
            continue
        if c == "," and depth == 0:
            return block[open_idx + 1:i]
        i += 1
    return block[open_idx + 1:]


def check(paths):
    bad = []
    for path in paths:
        src = path.read_text(encoding="utf-8")
        if not rtl_wrapped(path):
            continue
        lines = src.split("\n")
        i = 0
        while i < len(lines):
            m = re.match(r"^(\s*)Text\(", lines[i])
            if not m:
                i += 1
                continue
            # gather the balanced call
            depth = lines[i].count("(") - lines[i].count(")")
            block = lines[i]
            j = i
            while depth > 0:
                j += 1
                if j >= len(lines):
                    break
                block += "\n" + lines[j]
                depth += lines[j].count("(") - lines[j].count(")")
            arg = first_arg(block, block.index("(")).strip()
            # Two forms carry user-facing prose since the Arabic translation:
            # a literal (checked since this check existed) and a resolved
            # resource (the stringResource call). Both must sit inside ltr(),
            # which is content-aware - it isolates Latin and passes Arabic
            # through - so the wrapper is correct in either locale and a
            # missing one is a defect in English, where the trailing
            # punctuation still flips.
            if arg and not any(k in arg for k in MUSHAF):
                carries = '"' in arg or "stringResource(" in arg
                if carries and "ltr(" not in arg:
                    bad.append(f"{path.name}:{i + 1} Text({arg[:52]!r} is not wrapped "
                               f"in ltr()")
            i = j + 1
    return bad


TARGETS = [UI / "LiveMode.kt", UI / "ChooseScreen.kt"]

failures = check(TARGETS)
if failures:
    print("FAIL latin_wrap_parity")
    for f in failures:
        print("   -", f)
    print("   A trailing '.' or '?' or '…' in an RTL paragraph lands on the LEFT.")
    sys.exit(1)

# Fault injection. The failure being guarded against is real and has happened,
# so each mutation below is a real edit someone has already made.
MUTANTS = {
    "Paused added to the state line, wrapper dropped": (
        UI / "LiveMode.kt",
        lambda s: s.replace("ltr(when (state) {", "when (state) {", 1)),
    "the header title loses its wrapper": (
        UI / "ChooseScreen.kt",
        lambda s: s.replace(
            "ltr(if (stage == 0) stringResource(R.string.choose_title_stage0)",
            "if (stage == 0) stringResource(R.string.choose_title_stage0)", 1)),
    "the passage caption loses its wrapper": (
        UI / "ChooseScreen.kt",
        lambda s: s.replace(
            "ltr(stringResource(R.string.choose_whole_blurb, ayahCount))",
            "stringResource(R.string.choose_whole_blurb, ayahCount)", 1)),
}
survived = []
for label, (path, mutate) in MUTANTS.items():
    original = path.read_text(encoding="utf-8")
    mutated = mutate(original)
    if mutated == original:
        survived.append(f"{label}: mutation changed nothing")
        continue
    path.write_text(mutated, encoding="utf-8")
    try:
        got = check(TARGETS)
    finally:
        path.write_text(original, encoding="utf-8")
    if not got:
        survived.append(f"{label}: SURVIVED")

if survived:
    print("FAIL latin_wrap_parity (assertions that cannot fail)")
    for s in survived:
        print("   -", s)
    sys.exit(1)

print("PASS latin_wrap_parity")
#!/usr/bin/env python3
"""Static guard: a `remember` in the reader must key everything it reads.

The control-bar preview read like this:

    val standWords = remember(activeVerse, mushaf) {
        mushaf.flatMap { all 604 pages of words }
            .filter { it.surah == surah && it.verse == av }
    }

`surah` is a parameter of the composable. It is used in the body and absent from
the keys, so travelling between two surahs that sat on the same ayah number
never invalidated the value: the bar kept showing the PREVIOUS surah's words
while the colouring tracked the new surah correctly. The user saw exactly that,
and could not clear it by restarting the app.

The same defect had already been fixed once, for the header, a few lines away,
with a comment explaining it. Fixing an instance does not fix the class, and the
class here is invisible to every test that exists - there is no corpus of Kotlin
UI state and no device in CI. So it is checked statically instead.

Scope is deliberately narrow, because a check that cries wolf gets deleted:

  * only identifiers in NAV_KEYS are considered - values that change on their
    own when the reader travels. Values like `ctx`, `vm` or a theme colour are
    stable for the life of the composition and flagging them would be noise.
  * only `remember(` blocks are inspected, and only the lambda body that follows
    the key list.
  * a name that appears in the KEYS is fine, however many times it appears in
    the body.

Run: engine/.venv-replay/bin/python engine/replay/ui_state_parity.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"

# Values that change independently of a lock move. A `remember` that reads one
# of these without keying it will outlive the change it depends on.
NAV_KEYS = (
    "surah",            # composable parameter: set by the screen the user travelled to
    "headerSurah",      # derived from currentPage
    "screenSurah",
    "currentPage",
    "startPage",
    "activeVerse",
    "selectedAyah",
    "wordsVersion",
)


def strip_noise(src):
    """Blank out comments and string literals, preserving offsets.

    Without this the check flagged its OWN documentation: the fix for the stale
    preview has a comment quoting the old broken expression, so `surah` appeared
    in a "body" that no longer contained it. A guard that reports the fix as the
    bug is worse than no guard - it gets deleted on first use, and then the third
    instance lands unnoticed. Offsets are preserved so line numbers still line up.
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            for k in range(i, min(i + 2, n)):
                out[k] = " "
            i += 2
        elif c == '"':
            out[i] = " "
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\" and i + 1 < n:
                    out[i] = " "
                    out[i + 1] = " "
                    i += 2
                    continue
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            if i < n:
                out[i] = " "
                i += 1
        else:
            i += 1
    return "".join(out)


def _match_paren(src, i):
    """Index just past the ')' matching the '(' at index i."""
    depth = 0
    while i < len(src):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == '"':
            i += 1
            while i < len(src) and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
        i += 1
    return -1


def _match_brace(src, i):
    """Index just past the '}' matching the '{' at index i."""
    depth = 0
    while i < len(src):
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == '"':
            i += 1
            while i < len(src) and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
        i += 1
    return -1


def check(src, path_name="MainActivity.kt"):
    failures = []
    checked = 0
    for m in re.finditer(r"\bremember\s*\(", src):
        open_paren = m.end() - 1
        close_paren = _match_paren(src, open_paren)
        if close_paren < 0:
            continue
        keys = src[open_paren + 1:close_paren - 1]
        # Body = the lambda that follows the key list.
        b = src.find("{", close_paren)
        if b < 0 or src[close_paren:b].count("=") and "->" in src[close_paren:b]:
            continue
        if b < 0 or b - close_paren > 40:
            continue  # not an immediately-following lambda body
        end = _match_brace(src, b)
        if end < 0:
            continue
        body = src[b:end]
        checked += 1
        line = src[:m.start()].count("\n") + 1
        for name in NAV_KEYS:
            if not re.search(r"\b%s\b" % re.escape(name), body):
                continue
            if re.search(r"\b%s\b" % re.escape(name), keys):
                continue
            failures.append(
                "%s:%d: remember(...) reads `%s` in its body but does not key it. "
                "The value will outlive the change it depends on - this is how the "
                "control bar kept showing the previous surah." % (path_name, line, name))
    return checked, failures


def main() -> int:
    if not MAIN.is_file():
        print("FAIL: %s not found" % MAIN)
        return 1
    checked, failures = check(strip_noise(MAIN.read_text(encoding="utf-8")))
    if failures:
        print("FAIL")
        for f in failures:
            print("  -", f)
        return 1
    print("PASS: %d remember blocks inspected; every navigation value read in a "
          "body is keyed" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
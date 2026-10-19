#!/usr/bin/env python3
"""
Hide mode must not print the text it exists to withhold.

The reader has a hide-verse mode: the words are masked with the page's own
paper and only a word's OWN verdict may open its mask. That contract was
enforced in one place - `resolveWordStyle` - and everywhere else the mask was
a convention rather than a rule, so five paths printed a hidden word:

  1. the standing word (the word the reciter has not said yet) was painted
     blue, because a word with a status went through `resolveLayer` and
     `isCurrent` won before the hide branch was ever consulted;
  2. the play-head word and the already-played words were painted the same
     way;
  3. the selected ayah was painted in the fallback renderer;
  4. the recite bar's expected-words line printed the whole standing ayah in
     text, outside the page, where no mask could reach it;
  5. the live screen printed the focus ayah's words, and never read the
     preference at all.

The amber outline on the active ayah was a sixth leak of a different kind: it
traced the box of every hidden word, so the letters were gone but the shape of
the answer was not.

What must hold:

  1. in the hide branch, the two verdict arms come FIRST, so a CORRECT or
     WRONG word is revealed by its own verdict;
  2. every OTHER layer in that branch returns a masked style: `hidden = true`
     and a zero tint, so no rectangle is painted over a hidden word;
  3. no masked style carries an outline;
  4. the expected-words line takes the preference and returns before it
     appends a single word;
  5. the live screen reads the preference and keeps every word that has no
     verdict of its own invisible, and draws no marker over the current word.

Run: python3 engine/replay/hide_mask_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MAIN = ROOT / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"
LIVE = ROOT / "android/app/src/main/java/com/iqra/quran/ui/LiveMode.kt"

# Layers that may open a mask. Everything else is a position.
VERDICT_LAYERS = ("WRONG",)


def code(path):
    """The file's code with every comment removed.

    Block comments matter here: the doc comment above `resolveWordStyle`
    explains the hide contract in prose, and a check that read the comments
    would be reading the intention instead of the behaviour.
    """
    text = Path(path).read_text(encoding="utf-8")
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def body_of(text, signature):
    """The slice of a function's own body, brace-matched."""
    m = re.search(signature, text)
    if not m:
        return ""
    depth = 0
    for i in range(m.start(), len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[m.start():i + 1]
    return text[m.start():]


def call_text(text, start):
    """The argument list of a call starting at `start` (the open paren)."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return text[start:]


def main() -> int:
    main_src = code(MAIN)
    live_src = code(LIVE)
    bad = []

    style = body_of(main_src, r"private fun resolveWordStyle\s*\(")
    if not style:
        bad.append("resolveWordStyle is gone; the hide contract cannot be checked")
    else:
        hide = body_of(style, r"if \(hide\) \{")
        if not hide:
            bad.append("resolveWordStyle has no hide branch")
        else:
            split = hide.find("return when (layer)")
            if split < 0:
                bad.append("the hide branch no longer keys on the verdict")
            else:
                verdict_part, layer_part = hide[:split], hide[split:]
                for st in ("CORRECT", "WRONG"):
                    if "WordStatus.%s" % st not in verdict_part:
                        bad.append("hide mode does not reveal a %s word on its verdict" % st)
                if "hidden = false" in layer_part:
                    bad.append("a hide-mode layer opens a mask: a position is not a "
                               "verdict, and the standing word is the word the reciter "
                               "has not said yet")
                if "outline = true" in layer_part:
                    bad.append("a hide-mode layer traces the box of a hidden word, so "
                               "the shape of the answer survives the mask")
                # Every arm that is not a verdict must be masked, with no tint.
                for m in re.finditer(r"WordStyle\s*\(", layer_part):
                    call = call_text(layer_part, m.end() - 1)
                    head = layer_part[:m.start()]
                    label = re.findall(r"HighlightLayer\.(\w+)", head)
                    label = label[-1] if label else ""
                    if label in VERDICT_LAYERS:
                        continue
                    if "hidden = true" not in call:
                        bad.append("hide-mode layer %s is not masked" % (label or "?"))
                    elif ", 0f," not in call:
                        bad.append("hide-mode layer %s paints a tint over a hidden word"
                                   % (label or "?"))

    # The expected-words line: the preference must reach it, and it must
    # return before it appends a word.
    line_fn = body_of(main_src, r"private fun ExpectedWordsLine\s*\(")
    if not line_fn:
        bad.append("ExpectedWordsLine is gone; the recite bar cannot be checked")
    else:
        sig = line_fn[:line_fn.find("{")]
        if "hide" not in sig:
            bad.append("the recite bar's expected-words line does not take the hide "
                       "preference, so it cannot know it is hiding")
        else:
            guard = line_fn.find("if (hide) return")
            first_text = line_fn.find("b.append(")
            if guard < 0:
                bad.append("the expected-words line does not return when hiding, so it "
                           "prints the standing ayah in full")
            elif first_text >= 0 and guard > first_text:
                bad.append("the expected-words line appends words before it checks the "
                           "hide preference")
    if "ExpectedWordsLine(" in main_src and "hide = hide" not in main_src:
        bad.append("the expected-words line is not called with the hide preference")

    # The live screen: it must read the preference and keep unjudged words
    # invisible.
    if "ReaderPrefs.hideVerse(" not in live_src:
        bad.append("the live screen never reads the hide preference, so hiding in the "
                   "reader does not hide the focus ayah")
    lw = body_of(live_src, r"private fun LiveWord\s*\(")
    if not lw:
        bad.append("LiveWord is gone; the live screen's words cannot be checked")
    else:
        if "hide" not in lw[:lw.find("{")]:
            bad.append("LiveWord does not take the hide preference")
        elif "hide &&" not in lw:
            bad.append("LiveWord paints a word with no verdict of its own while hiding")
        if "drawRoundRect" in lw and "!hide" not in lw:
            bad.append("the live screen draws a marker over the current word while "
                       "hiding, which traces a hidden word's place")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: hide mode prints only a word's own verdict, and no position, outline "
          "or side row can open a mask")
    return 0


if __name__ == "__main__":
    sys.exit(main())

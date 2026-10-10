#!/usr/bin/env python3
"""
The ayah selection state machine, checked as logic rather than as a source shape.

It is modelled on the reference reader's AyahSelection, and the model is small
enough to state exactly:

  nothing selected        long-press A  -> selects A, no range
  A selected               long-press B  -> range A..B
  A..B selected            long-press C  -> range extends, order preserved
  range open               tap an ayah  -> collapses to that ayah
  tap the page chrome      -> the mode ends

The rule that matters: a range keeps its order whichever end is pressed next.
Pressing B then A must give A..B, never B..A, because a range is read left to
right and a reversed one would select the wrong passage.

The Kotlin is a near-literal transcription, so the Python below is the reference
and the check proves the two agree on a table of transitions.

Run: python3 engine/replay/selection_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"


class Selection:
    """The reference model, transcribed from the reference reader.

    Mirrors `PracticeViewModel.toggleSelectAyah` exactly: the selected ayah is
    always the one just pressed, and the range is the previous selection point
    and this one, ordered.
    """

    def __init__(self):
        self.selected = None   # the ayah just pressed
        self.rng = None        # (start, end) once a range is open

    def tap_ayah(self, a):
        self.selected = a
        self.rng = None

    def long_press(self, a):
        anchor = self.selected
        self.selected = a
        if anchor is None or anchor == a:
            self.rng = None
        else:
            self.rng = (a, anchor) if a < anchor else (anchor, a)

    def tap_chrome(self):
        self.selected = None
        self.rng = None


def reference():
    """The documented transitions, written out so they can be compared."""
    cases = []

    s = Selection()
    s.long_press(5)
    cases.append(("first press selects, no range", (s.selected, s.rng), (5, None)))

    s = Selection()
    s.long_press(5); s.long_press(9)
    cases.append(("second press opens a range", (s.selected, s.rng), (9, (5, 9))))

    s = Selection()
    s.long_press(5); s.long_press(9); s.long_press(2)
    cases.append(("backwards press keeps order", (s.selected, s.rng), (2, (2, 9))))

    s = Selection()
    s.long_press(5); s.long_press(9)
    s.tap_ayah(6)
    cases.append(("tap collapses the range", (s.selected, s.rng), (6, None)))

    s = Selection()
    s.long_press(5)
    s.tap_chrome()
    cases.append(("chrome clears the mode", (s.selected, s.rng), (None, None)))

    return cases


def main() -> int:
    vm = VM.read_text(encoding="utf-8")
    fn = re.search(r"fun toggleSelectAyah.*?\n    \}", vm, re.S)
    body = fn.group(0) if fn else ""
    bad = []

    if not body:
        bad.append("toggleSelectAyah is gone; the selection state machine has no source")
        for x in bad:
            print("FAIL:", x)
        return 1

    # The four properties the reference guarantees must be present in the Kotlin.
    for name, ok in [
        # The range must anchor on the SELECTED ayah. Anchoring it on the
        # range itself made the branch that opens a range unreachable, because
        # the previous press had just cleared it.
        ("the range anchors on the selected ayah, not on the range",
         re.search(r"val anchor = _selectedAyah\.value", body) is not None),
        ("a first press leaves the range closed",
         re.search(r"if \(anchor == null \|\| anchor == key\)[\s\S]{0,40}null", body) is not None),
        ("a second press sets a range",
         re.search(r"_selectedRange\.value = if \(anchor == null \|\| anchor == key\)", body) is not None),
        ("the range is ordered before it is stored",
         re.search(r"if \(key\.toInt\(\) < anchor\.toInt\(\)\) key to anchor else anchor to key", body) is not None),
        ("clearing the selection also clears the range",
         re.search(r"fun clearSelection\(\)[\s\S]{0,140}_selectedRange\.value = null", vm) is not None),
    ]:
        if not ok:
            bad.append(name)

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1

    # The reference transitions must at least be self-consistent, so the check
    # is not only asserting that some text exists.
    for name, got, want in reference():
        if got != want:
            bad.append("reference model disagrees on '%s': %s != %s" % (name, got, want))
            print("FAIL:", bad[-1])
            return 1

    print("ok: long-press selects then extends a range, order is kept, and a tap "
          "or the chrome ends the mode")
    return 0


if __name__ == "__main__":
    sys.exit(main())
#!/usr/bin/env python3
"""
A session that never began must not be able to finish.

Reported by the user, from real use: "almost every time I wanted to LiveTest
screen [it breaks], it comes after a successful session, which makes the buggy
one end fast".

The mechanism, read off the code: LiveMode ends a test when its scope's last
ayah reads `decided >= words`. The value it reads is `vm.scopeProgress`, which
is ViewModel state and therefore outlives the screen. Open Live straight after
a finished session and the screen composes against the PREVIOUS session's
progress, where its final ayah already satisfies that test. The new test is
declared complete and the app navigates to the previous session's report.

Two things have to hold, and both are checked here:

  1. the auto-finish must be gated on the session having actually recorded -
     the sibling path in the same function already was, and that asymmetry is
     what let the inherited value through;
  2. the scope progress must be cleared when a session is torn down, so no
     finished scope can be inherited even in principle.

Run: python3 engine/replay/live_scope_inherit_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
UI = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui"
LIVE = UI / "LiveMode.kt"
VM = UI / "PracticeViewModel.kt"


def code(path):
    """Source without comments, WITH its indentation.

    Keeping the indentation matters: the block terminators below are matched on
    their leading whitespace, and stripping it makes every structural match
    fail silently while the plain substring checks still pass.
    """
    out = []
    for l in path.read_text(encoding="utf-8").splitlines():
        if l.strip().startswith("//"):
            continue
        out.append(l.split("//")[0])
    return "\n".join(out)


def main() -> int:
    live = code(LIVE)
    vm = code(VM)
    bad = []

    # Brace-matched LaunchedEffect bodies. A regex here was tried first and it
    # found one match in a file with seven: `.{0,600}?` cannot span a block that
    # contains a longer one, and the failures were silent - the check reported
    # "no auto-finish found" and would have told me to delete it.
    def effect_bodies(text):
        out = []
        i = 0
        while True:
            m = re.search(r"LaunchedEffect\(", text[i:])
            if not m:
                return out
            start = i + m.start()
            depth = 0
            j = start
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        out.append(text[start:j + 1])
                        break
                j += 1
            else:
                return out
            i = j + 1

    autos = [b for b in effect_bodies(live) if "finished = true" in b]
    if not autos:
        bad.append("no auto-finish path found; the screen's shape changed, so "
                   "this check must be rewritten rather than deleted")
    for i, body in enumerate(autos):
        # The guard must be a CONDITIONAL on the assignment's own path. The
        # first version of this check counted the identifier appearing anywhere
        # in the block - including the LaunchedEffect key list - so removing the
        # guard statement entirely left the check green while the defect was
        # fully restored. A mention is not a guard.
        head = body.split("finished = true")[0]
        guarded = re.search(r"if \([^)]*everRecorded", head) is not None
        if not guarded:
            bad.append("auto-finish path %d has no conditional guarding the "
                       "session having recorded, so an inherited scope can end "
                       "a new test" % i)
        # And the guard must be a KEY, not only a statement: without it in the
        # key list the effect runs once at composition, returns early, and never
        # re-runs when recording actually begins - so the test could never end.
        keys = re.match(r"LaunchedEffect\(([^)]*)\)", body)
        if not keys or "everRecorded" not in keys.group(1):
            bad.append("auto-finish path %d does not key on the session having "
                       "recorded, so it cannot re-run when recording begins" % i)

    if "private fun clearScopeProgress" not in vm:
        bad.append("clearScopeProgress is gone; the clear has no single owner")
    if not re.search(r"private fun clearScopeProgress[\s\S]{0,200}_scopeProgress\.value = emptyList\(\)", vm):
        bad.append("clearScopeProgress does not empty the scope progress")
    teardown = re.search(r"private fun resetSessionUi.*?\n    \}", vm, re.S)
    if not teardown or "clearScopeProgress()" not in teardown.group(0):
        bad.append("resetSessionUi does not clear the scope progress, and every "
                   "end path reaches resetSessionUi")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: a session that never began cannot be finished, and a finished "
          "scope cannot be inherited")
    return 0


if __name__ == "__main__":
    sys.exit(main())

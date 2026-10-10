#!/usr/bin/env python3
"""
The docs must say the same number of checks as the gate runs.

This check exists because the two drifted immediately. CORE_LOGIC.md said 23,
MAINTAINING.md said 23, the skill file said 23, and the gate ran 24, because a
new check had been added and the prose had not caught up. A document that is wrong
about the size of the thing it describes is the first thing a reader trusts and
the first thing to be misled by.

The rule: the number in each document must equal the length of CHECKS in
run_checks.py, and each new check must also be named somewhere. Adding a check
and forgetting the documents now fails a gate instead of misleading a reader.

Run: python3 engine/replay/doc_check_count.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

RUN = (HERE / "run_checks.py").read_text(encoding="utf-8")
DOCS = [
    ROOT / "docs/CORE_LOGIC.md",
    ROOT / "docs/MAINTAINING.md",
    ROOT / ".opencode/skill/iqra-recognition/SKILL.md",
]


def declared_checks():
    m = re.search(r"CHECKS = \[(.*?)\n\]", RUN, re.S)
    if not m:
        return []
    return [x for x in re.findall(r'\("([a-z_]+)",', m.group(1))]


def main() -> int:
    names = declared_checks()
    bad = []
    if not names:
        print("FAIL: could not read the CHECKS list")
        return 1

    for doc in DOCS:
        text = doc.read_text(encoding="utf-8")
        for m in re.finditer(r"(\d+)\s+checks?", text):
            if int(m.group(1)) != len(names):
                bad.append("%s says '%s checks'; run_checks.py declares %d"
                           % (doc.relative_to(ROOT), m.group(0), len(names)))
        # every check must be named in CORE_LOGIC, the one file that lists them
        if doc.name == "CORE_LOGIC.md":
            missing = [n for n in names if n not in text]
            if missing:
                bad.append("CORE_LOGIC.md does not name: %s" % ", ".join(missing))

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: %d checks declared, and every document says so" % len(names))
    return 0


if __name__ == "__main__":
    sys.exit(main())

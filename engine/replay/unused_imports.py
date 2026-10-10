#!/usr/bin/env python3
"""
Unused imports are not harmless. They are the residue of deleted features.

The audit that produced this check counted 93 unused imports in HomeScreen.kt,
16 in MainActivity.kt and 9 in LiveMode.kt. Several were icon imports - View,
FolderOpen, MonitorHeart, ShowChart, Tune, History - which is proof that the
controls they belonged to were deleted and the imports were left behind. An
import that names a component that no longer exists is the only trace that the
feature ever was, and it is the kind of trace that makes a reader hunt for
something that is not there.

The rule: an import counts as used when its last path segment appears as a
bare identifier in the file's comment-stripped body. Wildcards are skipped -
`import androidx.compose.material3.*` cannot be checked this way, and a file
with a wildcard uses everything.

Run: python3 engine/replay/unused_imports.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran"

IMPORT = re.compile(r"^import\s+([\w.]+)\s*$", re.M)
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)

# Imports that compile to code with no textual trace. `val x by remember { ... }`
# is spelled `getValue` at the point of use and `setValue` on a `var`, so
# neither name appears in the body even though both are load-bearing.
OPERATOR_IMPORTS = {"getValue", "setValue", "provideDelegate"}


def main() -> int:
    bad = []
    checked = 0
    for path in sorted(JAVA.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        body = BLOCK_COMMENT.sub("", text)
        # Strip imports from the body before looking for uses, so a name that
        # only appears in another import line does not count as a use.
        body_no_imports = "\n".join(l for l in body.splitlines() if not l.startswith("import "))
        names = set()
        for m in IMPORT.finditer(text):
            fq = m.group(1)
            if fq.endswith(".*"):
                continue
            names.add(fq.rsplit(".", 1)[1])
        if not names:
            continue
        checked += len(names)
        for n in sorted(names):
            if n in OPERATOR_IMPORTS:
                continue
            if not re.search(r"\b" + re.escape(n) + r"\b", body_no_imports):
                bad.append("%s: unused import %s" % (path.relative_to(JAVA), n))

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: %d imports checked, all used" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())

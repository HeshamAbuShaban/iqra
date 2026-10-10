#!/usr/bin/env python3
"""
Dead code is a liability: it reads as a feature and ships as a bug.

The audit that produced this check found, in one pass, an enum constant for a
verdict the aligner cannot emit, a `wordProb` array computed on every alignment
and read by nobody, a confidence-gate's plumbing still standing after the gate
itself was deleted, a `forVerdict` helper whose comment claims it unified the
verdict colours across two renderers while both renderers still carry their own
five literals, and a whole 554-line screen with no caller. None of it was
reachable, so nothing tested it, so nobody noticed.

Two rules, both against the whole production tree:

  1. Every top-level declaration must be referenced from somewhere. A
     declaration nobody references is dead weight a future reader believes is
     load-bearing.
  2. Every FILE must be referenced from another file. A file whose members only
     reference each other is unreachable however many references they share
     between them - rule 1 cannot see it, because a dead screen's members call
     each other.

References are counted as bare identifiers, not calls: Kotlin exposes
StateFlow properties, data-class constructors and constants without
parentheses (`vm.statusMap`, `advisory.kind`, `alignment.wordCoverage`).
Comments are stripped first, because a mention in prose is not a reference.

KNOWN LIMIT. This counts identifiers, not symbols, so a new declaration whose
name already exists elsewhere shares the other one's reference count and passes.
It catches the common case - a dead name nobody else uses - and not the case of
shadowing with a duplicate.

Run: python3 engine/replay/dead_code.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
JAVA = ROOT / "android/app/src/main/java/com/iqra/quran"
TEST = ROOT / "android/app/src/test"

# Declarations at column 0 or 4 columns. Some files put their functions at the
# top level (MainActivity.kt) and some nest them inside a class
# (PracticeViewModel.kt), and matching only one of those shapes silently skips
# the other - which is how this check passed over every dead declaration in
# MainActivity.kt while reporting them in PracticeViewModel.kt.
DECL = re.compile(r"^[ \t]{0,4}(?:private |internal |@\w+ )*(?:fun|val|var|class|object|const)\s+([A-Za-z_]\w*)")
# A file is referenced when something names a symbol it declares.
TOP = re.compile(r"^(?:internal |private )?(?:fun|val|var|class|object|data class|enum class)\s+([A-Za-z_]\w*)")

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)

# Entry points that Android instantiates from the manifest, so no Kotlin caller
# can name them. They are not dead; they are unreachable-from-this-tree.
MANIFEST_ENTRY = {"QuranApplication", "MainActivity"}


def strip_comments(text):
    text = BLOCK_COMMENT.sub("", text)
    return "\n".join(l.split("//")[0] for l in text.splitlines())


def read_tree():
    files = {}
    for base in (JAVA, TEST):
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*.kt")):
            try:
                files[p] = strip_comments(p.read_text(encoding="utf-8"))
            except OSError:
                pass
    return files


def main():
    files = read_tree()
    bad = []
    checked = 0

    # ---- rule 1: declarations ---------------------------------------------
    for path, text in files.items():
        if not str(path).startswith(str(JAVA)):
            continue
        lines = text.splitlines()
        for i, line in enumerate(lines, 1):
            m = DECL.match(line)
            if not m:
                continue
            name = m.group(1)
            checked += 1
            if name in MANIFEST_ENTRY:
                continue
            rx = re.compile(r"\b" + re.escape(name) + r"\b")
            refs = sum(len(rx.findall(t)) for t in files.values()) - len(rx.findall(line))
            if refs <= 0:
                bad.append("%s:%d %s: declared, referenced from nowhere" % (
                    path.relative_to(JAVA), i, name))

    # ---- rule 2: files -----------------------------------------------------
    for path, text in files.items():
        if not str(path).startswith(str(JAVA)):
            continue
        named = [m.group(1) for m in TOP.finditer("\n".join(text.splitlines()[1:]))]
        if not named:
            continue
        referenced = False
        for other, otext in files.items():
            if other == path:
                continue
            for name in set(named):
                if re.search(r"\b" + re.escape(name) + r"\b", otext):
                    referenced = True
                    break
            if referenced:
                break
        if not referenced:
            bad.append("%s: entire file is unreachable - no other file names "
                       "anything it declares" % path.relative_to(JAVA))

    if bad:
        for b in sorted(bad):
            print("FAIL:", b)
        return 1
    print("ok: %d declarations checked; every file is reachable from another" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())

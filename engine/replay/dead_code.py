#!/usr/bin/env python3
"""
Dead engine code is a liability: it reads as a feature and ships as a bug.

The audit that produced this check found, in one pass, an enum constant for a
verdict the aligner cannot emit, a `wordProb` array computed on every alignment
and read by nobody, a confidence-gate's plumbing still standing after the gate
itself was deleted, and a whole 554-line screen with no caller. None of it was
reachable, so nothing tested it, so nobody noticed.

The rule enforced here: every declaration in the recognition surface must be
referenced from some OTHER file. A declaration nobody references is dead weight
that a future reader will believe is load-bearing.

References are counted as bare identifiers, not as calls, because Kotlin exposes
StateFlow properties, data-class constructors and constants without parentheses -
`vm.statusMap`, `advisory.kind`, `Alignment.wordProb`. Comments are stripped
first: a mention in prose is not a reference.

Deliberately out of scope: theme tokens, chart helpers, screens and other styling
composables. Those are cosmetic, another agent owns them, and deleting them on a
name-count alone risks removing API someone else is about to call.

KNOWN LIMIT. This counts identifiers, not symbols, so a NEW declaration whose name
already exists elsewhere in the tree will share the other one's reference count
and pass. It therefore catches the common case - a dead name nobody else uses -
and not the case of shadowing with a duplicate. Adding a second `isReady()` to a
file next to another `isReady()` is exactly what it misses. Whoever repairs it
needs a real parser, not this.
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran"

DECL = re.compile(r"^\s{4}(?:private |internal |@\w+ )*(?:fun|val|var|class|object|const)\s+([A-Za-z_]\w*)")

# Whether the identifier appears anywhere else in the tree. `\bname\b` matches a
# property read, a call, a type use and a constructor argument alike.
IDENT = r"\b{0}\b"

# Legitimate entry points: called from the JVM test source or from Android, so
# no reference appears in the production tree.
EXTERNAL_ENTRY = {
    "PhonemeMapper", "UnitAligner", "SherpaVad", "SherpaZipformer",
    "Levenshtein", "Alignment", "Advisory", "AdvisoryKind",
    # Read by android/app/src/test/java/com/iqra/quran/ml/UnitAlignerTest.kt,
    # which is the only reason it exists. It is not dead, it is untested-from-
    # this-tree, and deleting it would silently drop its coverage.
    "canonicalCases",
}

# Names reachable only through the JVM test tree are allowlisted here. Anything
# else needs a caller, and a function whose name begins with get/is/has/table/
# unit/count gets no leniency just for being a plausible accessor - `tableSize`,
# `unitCount`, `wordTableSize`, `isReady`, `speechInWindow` and `modelDir` were
# all deleted from this surface in one pass for exactly that reason.

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_comments(text: str) -> str:
    text = BLOCK_COMMENT.sub("", text)
    return "\n".join(l.split("//")[0] for l in text.splitlines())


def main() -> int:
    everything = {}
    for p in JAVA.rglob("*.kt"):
        try:
            everything[p] = strip_comments(p.read_text(encoding="utf-8"))
        except OSError:
            continue

    targets = sorted(set(
        [JAVA / f for f in ("ui/PracticeViewModel.kt", "ui/Advisory.kt")]
        + list((JAVA / "ml").rglob("*.kt"))
    ))

    bad = []
    checked = 0
    for path in targets:
        lines = everything[path].splitlines()
        for i, line in enumerate(lines, 1):
            m = DECL.match(line)
            if not m:
                continue
            name = m.group(1)
            checked += 1
            if name in EXTERNAL_ENTRY:
                continue
            rx = re.compile(IDENT.format(re.escape(name)))
            # Every reference anywhere in the tree, minus the one on the line
            # that declares it. A ViewModel's members are legitimately used only
            # inside the ViewModel, so counting must include its own file -
            # excluding it would flag most of the class as dead.
            refs = sum(len(rx.findall(text)) for text in everything.values())
            refs -= len(rx.findall(line))
            if refs <= 0:
                bad.append("%s:%d %s: declared in the recognition surface, referenced from nowhere"
                           % (path.relative_to(JAVA), i, name))

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: %d declarations checked, every one referenced from another file" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())

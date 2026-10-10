#!/usr/bin/env python3
"""Source-parity checks on PhonemeMapper's dynamic program.

The DP's outputs are not reproducible offline - the app is the only place the
Kotlin runs - so the guard has to read the source and assert the properties that
make it trustworthy. This exists because a proposed "optimisation" of this very
function was produced elsewhere and would have broken it in a way no test in the
replay suite could see. It had three faults, all of them structural:

  * it banded the DP with a half-width of max(n, len) + 1, which provably spans
    the whole grid, so it computed the full table;
  * its boundary write `cur[-off] = i` used a negative index on the first row
    (lo is 0 on every row, because off > n), which throws in Kotlin;
  * its fill wrote the direction table at column `j + off` while its backtrace
    read column `j - i + off` - the same path, offset by i.

None of those change a line of Python, so none of them were visible to the
equivalence harness. They are visible in the source, which is what this reads.

Run:  engine/.venv-replay/bin/python engine/replay/dp_source_parity.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# The DP now lives in UnitAligner.kt, extracted so CI can execute it. The guard
# follows it there rather than being deleted - a source check that is "updated"
# to point at nothing is how a real guard quietly stops guarding.
KT = ROOT / "android/app/src/main/java/com/iqra/quran/ml/UnitAligner.kt"


MAPPER = ROOT / "android/app/src/main/java/com/iqra/quran/ml/PhonemeMapper.kt"


def check_caller(failures) -> None:
    """PhonemeMapper must consume the kernel, not keep a second traceback.

    Splitting the DP out is only safe while there is exactly ONE traceback in
    the codebase. If someone inlines a local fill loop again, the two can drift
    and the pinned kernel digest stops describing what the app actually runs.
    """
    if not MAPPER.is_file():
        failures.append(f"missing {MAPPER}")
        return
    body = MAPPER.read_text(encoding="utf-8")
    if "UnitAligner.refToQuery(" not in body:
        failures.append("PhonemeMapper no longer calls UnitAligner.refToQuery - if a "
                        "second DP was inlined here, the pinned kernel digest is no "
                        "longer the behaviour of this app")
    # PhonemeMapper.align is the public entry point and must exist; what must
    # NOT exist is a second copy of the DP's internals, which could drift from
    # the kernel the digest pins.
    for pat, why in [
        (r"IntArray\s*\(\s*\(\s*n\s*\+\s*1", "a second direction table"),
        (r"val\s+sub\s*=", "a second fill"),
        (r"dir\[", "a second direction-table index"),
    ]:
        if re.search(pat, body):
            failures.append(f"PhonemeMapper contains {why}; there must be one traceback")


def main() -> int:
    src = KT.read_text(encoding="utf-8")
    failures = []

    body = src[src.index("fun refToQuery("):]

    # 1. NO BANDING. The fill must sweep the full expected width every row. A
    #    band would be written as lo..hi, or with a half-width applied to the
    #    column bound.
    fill = re.search(r"for \(j in 1\.\.len\)\s*\{(.*?)\n {12}\}", body, re.S)
    if not fill:
        failures.append(
            "could not find the inner DP fill loop; if it was restructured, "
            "update this check rather than deleting it"
        )
    for pat, why in [
        (r"val off\s*=", "a band half-width is defined"),
        (r"val w\s*=", "a band width is defined"),
        (r"for \(j in lo\.\.hi\)", "the fill is banded (j in lo..hi)"),
        (r"\[\s*-\s*off\s*\]", "a negative band offset is indexed"),
    ]:
        if re.search(pat, body):
            failures.append(f"banding detected: {why}. The band is not exact here - "
                            "a safe half-width is max(n,len), which spans the whole grid")

    # 2. FILL AND BACKTRACE MUST AGREE on the direction-table index. This is the
    #    check that catches a fill/backtrace offset mismatch. The fill is
    #    allowed to use a local alias for the row base, so the alias is
    #    resolved first: `val row = i * width` makes `dir[row + j]` the same
    #    expression as `dir[i * width + j]`, and treating those as different was
    #    a false positive that would have made this guard unusable.
    alias = re.search(r"val row = (i \* width|\(?n \+ 1\)? \* w)", body)
    alias_norm = None
    if alias:
        alias_norm = alias.group(1).replace(" ", "")

    def norm(idx):
        e = idx.replace(" ", "")
        if alias_norm and e == "row+j":
            return "i*width+j"
        return e

    # `(?!=)` is load-bearing: without it the write pattern's trailing `=`
    # matches the first `=` of a `==` comparison, so every read was also counted
    # as a write and a genuinely mis-indexed fill passed unnoticed.
    # A read is either `dir[x] == 0` or the dispatch form `when (dir[x])`. Both
    # index the same table, so both are reads; only the first was matched before,
    # which made the guard report "no read found" on a correct kernel.
    IDX = r"dir\[\s*([a-zA-Z0-9_()+ *\-]+?)\s*\]"
    writes = {norm(w) for w in re.findall(IDX + r"\s*=(?!=)", body)}
    reads = {norm(r) for r in re.findall(IDX + r"\s*==", body)}
    reads |= {norm(r) for r in re.findall(r"when\s*\(\s*" + IDX, body)}
    reads = {r for r in reads if r}
    if not writes or not reads:
        failures.append("could not find the direction-table write/read expressions")
    elif writes != reads:
        failures.append(
            f"direction table is written at {sorted(writes)} but read at "
            f"{sorted(reads)}; a mismatch silently traces a different path"
        )

    # 3. The expected side must be unable to collide with the -1 that unknown
    #    emitted symbols get. It cannot, PROVIDED the interning map is built from
    #    the very list the expected side is drawn from. The `?: -1` fallback in
    #    the lookup is unreachable in that case, so it is not the thing to
    #    assert - the provenance is.
    # Expected/explode stayed in PhonemeMapper; only the traceback moved.
    mapper = MAPPER.read_text(encoding="utf-8")
    exp_decl = mapper[mapper.index("data class Expected("):mapper.index("private fun explode")]
    # The build interns each expected unit. Since 4 legate a single realisation,
    # it maps a madd unit's canonical length instead of its specific variant, so
    # two legal length choices of the same bearer compare equal. Still built
    # strictly from Expected.units; the canonicalisation is the whole point.
    if "for (i in units.indices)" not in exp_decl or "canonicalId" not in exp_decl:
        failures.append(
            "symbolIds is not built from Expected.units, so the expected side "
            "could miss the map and collide with the -1 used for unknown symbols"
        )
    if "val flat = expected.units" not in mapper:
        failures.append("the expected unit list no longer comes from Expected.units")

    # 4. TIE-BREAK ORDER must be substitution, then deletion, then insertion.
    #    Any reordering can pick a different path through an equal-cost
    #    alignment and change the per-word verdicts at identical edit distance.
    if not fill:
        pass
    else:
        order = re.findall(r"(sub|del|ins)\b", fill.group(1))
        first_use = []
        for name in order:
            if name not in first_use:
                first_use.append(name)
        if first_use[:3] != ["sub", "del", "ins"]:
            failures.append(
                f"tie-break order is {first_use[:3]}, expected ['sub', 'del', 'ins']"
            )
        # Declaring sub, del, ins in that order is not the same as preferring
        # them in that order. `best` must be SEEDED from the substitution, or a
        # substitution can lose to an equal-cost insertion - the original defect
        # this whole file exists for.
        seed = re.search(r"var best = (sub|del|ins)\b", fill.group(1))
        if not seed or seed.group(1) != "sub":
            failures.append(
                f"best is seeded from {seed.group(1) if seed else 'nothing'}, "
                "expected 'sub'; substitution must be preferred at equal cost"
            )
        di, ii = fill.group(1).find("del <"), fill.group(1).find("ins <")
        if di < 0 or ii < 0 or di > ii:
            failures.append("deletion must be considered before insertion")

    # 5. Interning must be present at all; check 3 covers the -1 collision.
    if "symbolIds" not in mapper:
        failures.append("units are not interned; the inner loop compares Arabic strings")
    # Having the map is not the same as using it at the lookup site. An expected
    # unit taken as a raw string straight into the IntArray would restore the
    # Arabic comparison the optimisation removed - and would not even compile,
    # which is how this gap stayed invisible.
    if not (re.search(r"ids\[[^\]]*flat\[", mapper)
            or re.search(r"val u = flat\[it\][\s\S]{0,120}ids\[", mapper)):
        failures.append(
            "the expected units are not read through ids[...flat[...]]; the kernel "
            "takes IntArray, so raw strings would compare Arabic per row"
        )

    # 6. The backtrace must keep its defensive tail branches. A collapsed
    #    `when`/`else` that assumes dir==0 spins forever if it ever reads a cell
    #    that was never written. The guard is anchored on the loop itself, not on
    #    the first `if`, so it cannot silently go dead if that line is reworded.
    loop = re.search(r"while \(i > 0 \|\| j > 0\) \{(.*?)\n        \}", body, re.S)
    if not loop:
        failures.append("could not find the backtrace loop")
    else:
        tail = loop.group(1)
        if "else if (i > 0)" not in tail or not re.search(r"else \{\s*\n\s*j--", tail):
            failures.append(
                "the backtrace has no branch for i==0 or j==0; if the direction "
                "read ever misses, the loop cannot terminate"
            )
        # The unknown-direction arm must BAIL rather than continue. Advancing on
        # a cell that was never written walks off the edge of the path and
        # eventually returns a plausible-looking alignment that is not one.
        if not re.search(r"else\s*->\s*return", tail):
            failures.append(
                "the backtrace's unknown-direction arm no longer returns; it must "
                "bail rather than keep walking a path it cannot trust"
            )

    # 7. The `Expected` interning map must be per-ayah and not rebuilt per call.
    exp = mapper[mapper.index("data class Expected("):mapper.index("private fun explode")]
    if "val symbolIds" not in exp:
        failures.append("Expected has no cached symbolIds map")
    if "val ids: Map<String, Int>?" in exp and "get()" in exp:
        if "synchronized" not in exp and "Volatile" not in exp:
            failures.append("symbolIds is lazily built with no visibility guarantee")

    check_caller(failures)

    if failures:
        print("FAIL")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("PASS: the DP is unbanded, its fill and backtrace index the direction")
    print("      table identically, the tie-break order is intact, and units are")
    print("      interned with the expected side unable to collide with -1")
    return 0


if __name__ == "__main__":
    sys.exit(main())

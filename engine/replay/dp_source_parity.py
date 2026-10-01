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
KT = ROOT / "android/app/src/main/java/com/iqra/quran/ml/PhonemeMapper.kt"


def main() -> int:
    src = KT.read_text(encoding="utf-8")
    failures = []

    body = src[src.index("fun align("):]

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
    IDX = r"dir\[\s*([a-zA-Z0-9_()+ *\-]+?)\s*\]"
    writes = {norm(w) for w in re.findall(IDX + r"\s*=(?!=)", body)}
    reads = {norm(r) for r in re.findall(IDX + r"\s*==", body)}
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
    exp_decl = src[src.index("data class Expected("):src.index("private fun explode")]
    if "for (u in units)" not in exp_decl or "m[u] = m.size" not in exp_decl:
        failures.append(
            "symbolIds is not built from Expected.units, so the expected side "
            "could miss the map and collide with the -1 used for unknown symbols"
        )
    if "val flat = expected.units" not in body:
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

    # 5. Interning must be present at all; check 3 covers the -1 collision.
    if "symbolIds" not in body:
        failures.append("units are not interned; the inner loop compares Arabic strings")

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

    # 7. The `Expected` interning map must be per-ayah and not rebuilt per call.
    exp = src[src.index("data class Expected("):src.index("private fun explode")]
    if "val symbolIds" not in exp:
        failures.append("Expected has no cached symbolIds map")
    if "val ids: Map<String, Int>?" in exp and "get()" in exp:
        if "synchronized" not in exp and "Volatile" not in exp:
            failures.append("symbolIds is lazily built with no visibility guarantee")

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

#!/usr/bin/env python3
"""Build web/public/data/phonemes.json — a compact per-ayah phonemisation
table derived from tanzil-data quran-phonetics.json (the same upstream source
family the Android app's ordered_quran_phonemes.json comes from).

Output format: { "S:A": ["بِسمِ", "ٱللّهِ", ...], ... }  (word strings per ayah)
Also emits units.txt: one phoneme unit per line (sorted longest-first is done
client-side, mirroring PhonemeMapper.ensureUnits).
"""
import json, re, sys, pathlib

SRC = pathlib.Path("/tmp/tanzil/quran-phonetics.json")
OUT_DIR = pathlib.Path(__file__).parent / "public" / "data"
OUT_DIR.mkdir(parents=True, exist_ok=True)

raw = json.loads(SRC.read_text(encoding="utf-8"))
verses = raw["verses"] if isinstance(raw, dict) and "verses" in raw else raw

table = {}
units = set()
for v in verses:
    ident = v.get("identifier") or {}
    s, a = ident.get("surah"), ident.get("ayah")
    words = v.get("words")
    if not s or not a or not words:
        continue
    out = []
    for w in sorted(words, key=lambda x: x.get("position", 0)):
        ph = w.get("phonemes_v2") or w.get("phonemes_v1") or ""
        ph = ph.strip()
        if not ph:
            continue
        out.append(ph)
        for u in ph.split():
            units.add(u)
    if out:
        table[f"{s}:{a}"] = out

(OUT_DIR / "phonemes.json").write_text(
    json.dumps(table, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
(OUT_DIR / "units.txt").write_text("\n".join(sorted(units, key=len, reverse=True)) + "\n", encoding="utf-8")
print(f"ayat: {len(table)}  units: {len(units)}  bytes: {(OUT_DIR/'phonemes.json').stat().st_size}")

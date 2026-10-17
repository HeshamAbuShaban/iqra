#!/usr/bin/env python3
"""
Build the replay screen for one or more session records.

The record holds what the app measured: per-poll frames, per-word verdicts
with their decision times (`wt`), the retained emission stream, arrivals,
spans, advisories and the log. This exporter joins that with the mushaf page
geometry the reader draws from - ayahinfo_1024.db glyph boxes, paired to word
keys by the same rules the app applies (pageSlots + pairAyahBoxes, ported
without change) - and embeds the page bitmap, so the output directory is one
HTML file's directory: open replay.html and scrub.

What it refuses to invent, the screen admits:
  - an ayah whose glyph boxes do not reconcile with the word layout is left
    unboxed (leak rather than wrong box), as on device;
  - a window the record cannot reproduce is NOT RECONSTRUCTABLE (the
    device_blame.judged_span rule, reused unmodified);
  - a verdict row is replayed as recorded - the screen is the record, not a
    re-simulation of the recognition.

Run:
  adb -s 35d5637e shell run-as com.iqra.quran cat files/sessions/<f>.json > /tmp/rec.json
  python3 engine/replay/replay_export.py /tmp/rec.json \\
      --glyphdb /path/ayahinfo_1024.db --pages-dir /path/pages
  open engine/replay/out/replay/<record-name>/replay.html
"""

import argparse
import base64
import json
import shutil
import sqlite3
import sys
import time
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MUSHAF = ROOT / "android/app/src/main/assets/mushaf.json"
TEMPLATE = HERE / "replay_screen"

# The reader's own drawing rules, ported verbatim from MainActivity.kt.
MIN_WORD_BOX = 20.0
MIN_WORD_H = 24.0
ORNAMENT_RUB_EL_HIZB = 0x06DE
ORNAMENT_SAJDAH = 0x06E9

SLOT_TOKEN, SLOT_ORNAMENT, SLOT_WAQF = 0, 1, 2


def is_sign_token(t):
    saw = False
    for c in t:
        v = ord(c)
        if v in range(0x200B, 0x2010) or v == 0xFEFF or v == 0x061C:
            continue
        if v not in range(0x06D6, 0x06EA):
            return False
        saw = True
    return saw


def page_slots(texts):
    out = []
    for wi, text in enumerate(texts):
        for part in text.split(" "):
            part = part.replace("\t", " ").replace("\n", " ").replace("\u00A0", " ").replace("\u2007", " ").replace("\u202F", " ")
            for p in part.split(" "):
                if not p:
                    continue
                if not is_sign_token(p):
                    out.append((wi, SLOT_TOKEN))
                elif any(ord(ch) in (ORNAMENT_RUB_EL_HIZB, ORNAMENT_SAJDAH) for ch in p):
                    out.append((wi, SLOT_ORNAMENT))
                else:
                    out.append((wi, SLOT_WAQF))
    return out


def pair_ayah_boxes(all_boxes, slots, words):
    boxes = []
    for x0, y0, x1, y1 in all_boxes:
        rx0, rx1 = min(x0, x1), max(x0, x1)
        ry0, ry1 = min(y0, y1), max(y0, y1)
        if rx1 - rx0 >= MIN_WORD_BOX and ry1 - ry0 >= MIN_WORD_H:
            boxes.append((rx0, ry0, rx1, ry1))
    if not boxes:
        return None
    body = boxes[:-1]
    tokens = sum(1 for _, k in slots if k == SLOT_TOKEN)
    ornaments = sum(1 for _, k in slots if k == SLOT_ORNAMENT)
    signs = sum(1 for _, k in slots if k == SLOT_WAQF)
    extra = len(body) - tokens - ornaments
    if extra == 0:
        seq = [(w, k) for w, k in slots if k != SLOT_WAQF]
    elif extra == signs:
        seq = slots
    elif extra < 0 and len(body) == words:
        seq = [(i, SLOT_TOKEN) for i in range(words)]
    else:
        return None
    if len(seq) != len(body):
        return None
    out = [None] * words
    for (w, k), r in zip(seq, body):
        if k != SLOT_TOKEN or w >= words:
            continue
        out[w] = r if out[w] is None else (
            min(out[w][0], r[0]), min(out[w][1], r[1]),
            max(out[w][2], r[2]), max(out[w][3], r[3]),
        )
    return out


def page_word_boxes(mx, cnx):
    """key -> box list for one mushaf page, via the app's pairing rules."""
    boxes_by_ayah = {}
    try:
        cur = cnx.execute(
            "SELECT sura_number, ayah_number, min_x, min_y, max_x, max_y "
            "FROM glyphs WHERE page_number=? ORDER BY sura_number, ayah_number, position",
            (mx["page"],))
        for s, a, x0, y0, x1, y1 in cur:
            boxes_by_ayah.setdefault((s, a), []).append((x0, y0, x1, y1))
    except sqlite3.Error:
        pass
    boxes = {}
    lines = [l for l in mx["lines"] if l["type"] == "text"]
    by_key = {}
    for l in lines:
        for w in l.get("words") or []:
            by_key[w["location"]] = w["word"]
    ayahs = {}
    for key, text in by_key.items():
        s, a, i = (int(v) for v in key.split(":"))
        ayahs.setdefault((s, a), {})[i] = text
    for (s, a), words in ayahs.items():
        g = boxes_by_ayah.get((s, a), [])
        if not g:
            continue
        slots = page_slots([words[i] for i in sorted(words)])
        got = pair_ayah_boxes(g, slots, len(words))
        if got:
            for wi, r in enumerate(got):
                if r:
                    boxes["%d:%d:%d" % (s, a, wi + 1)] = [round(r[0], 1), round(r[1], 1), round(r[2], 1), round(r[3], 1)]
    return boxes


def page_text_lines(mx, boxes):
    """Fallback render: one y per line group of this page's text lines."""
    ys = []
    y = 400
    for l in mx["lines"]:
        if l["type"] == "text" and l.get("text"):
            ys.append({"y": y, "text": l["text"]})
            y += 90
        elif l["type"] in ("surah-header", "basmala"):
            pass
    return ys


def causes_for(record):
    """Per-WRONG word reconstruction, reusing device_blame unmodified."""
    sys.path.insert(0, str(HERE))
    import device_blame as db
    import word_verdicts as wv
    table = json.load(open(ROOT / "android/app/src/main/assets/word_aligned_phonemes.json", encoding="utf-8"))
    units = wv.load_units()
    unit_set = set(units)
    causes = {}
    for w in record.get("words", []):
        if w.get("st") != "WRONG":
            continue
        s_, a_, i_ = (int(v) for v in w["key"].split(":"))
        wlist = table.get("%d:%d" % (s_, a_))
        if not wlist or i_ > len(wlist):
            causes[w["key"]] = {"error": "no table row"}
            continue
        lo, hi, why = db.judged_span(record, s_, a_)
        if why:
            causes[w["key"]] = {"error": why}
            continue
        ems = record["emissions"]
        span = ems[lo - record.get("emissionBase", 0):hi - record.get("emissionBase", 0)]
        flat = [u for wd in wlist for u in wd]
        uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
        units_out = []
        try:
            got = [e for e in span if e in unit_set]
            matched, wrong, r2q, _e, _h, _nw = wv.align(got, flat, uw2)
            for k in range(len(flat)):
                if uw2[k] != i_ - 1:
                    continue
                q = r2q[k]
                heard = got[q] if q is not None and q >= 0 else None
                units_out.append([flat[k], heard, bool(wrong[k])])
        except Exception as e:  # the alignment itself must not fabricate
            causes[w["key"]] = {"lo": lo, "hi": hi, "units": [], "error": "re-align: %s" % e}
            continue
        causes[w["key"]] = {"lo": lo, "hi": hi, "units": units_out}
    return causes


def build(one, mushaf, glyphdb, pages_dir, out_root):
    rd = json.load(open(one, encoding="utf-8"))
    stem = Path(one).stem
    name = stem.replace("-#1", "").replace("#1", "build1")
    out_dir = out_root / stem.replace("#", "_")
    out_dir.mkdir(parents=True, exist_ok=True)

    # word key -> page index, from the pages list itself
    key_page = {}
    for p in mushaf:
        for l in p["lines"]:
            for w in (l.get("words") or []):
                key_page[w["location"]] = p["page"]

    pages_needed = sorted({key_page[w["key"]] for w in rd.get("words", []) if w["key"] in key_page})
    # frames may hold a surah whose words are elsewhere; add its pages too
    fh_surahs = {int(w["key"].split(":")[0]) for w in rd.get("words", [])}
    for p in mushaf:
        ps = sorted({int(l["surah"]) for l in p["lines"] if l.get("surah")})
        if any(s in fh_surahs for s in ps):
            pages_needed.append(p["page"])
    pages_needed = sorted(set(pages_needed))

    cnx = sqlite3.connect("file:%s?mode=ro" % glyphdb, uri=True)
    pages_obj = {}
    for pn in pages_needed:
        mx = next(p for p in mushaf if p["page"] == pn)
        boxes = page_word_boxes(mx, cnx)
        png = pages_dir / ("%03d.png" % pn)
        img = None
        if png.exists():
            img = "data:image/png;base64," + base64.b64encode(png.read_bytes()).decode("ascii")
        pages_obj[str(pn)] = {
            "img": img,
            "boxes": boxes,
            "textLines": [] if img else page_text_lines(mx, boxes),
        }
    cnx.close()

    started = rd.get("startedAt")
    when = ""
    if isinstance(started, (int, float)) and started > 0:
        when = time.strftime("%d %b %H:%M", time.localtime(started / 1000))
    data = {
        "name": name,
        "meta": "%s · %s · surah %s→%s lock %s · moves %s · decided %s of %s · pollSec %s" % (
            name.split("-")[-1], when, rd.get("surahStart"), rd.get("surahNow"), rd.get("lockNow"),
            rd["counters"]["moves"], rd["counters"]["judgedWords"], rd["counters"]["evaluatedWords"],
            rd["counters"]["pollSec"]),
        "honest": "Painted from the record: every word takes its recorded verdict at the decision time it carries (wt); the lock tracks the recorded frames. The screen replays the record, not a re-simulation. A window the record cannot reproduce reads NOT RECONSTRUCTABLE when its word is clicked.",
        "lastT": max((f["t"] for f in rd.get("frames", [])), default=0),
        "frames": rd.get("frames", []),
        "words": rd.get("words", []),
        "advisories": rd.get("advisories", []),
        "diag": rd.get("diagTail", []),
        "emissions": rd.get("emissions", []),
        "emissionBase": rd.get("emissionBase", 0),
        "arrivals": rd.get("arrivals", {}),
        "spans": rd.get("spans", {}),
        "causes": causes_for(rd),
        "pages": pages_obj,
    }
    shutil.copy(TEMPLATE / "replay.html", out_dir / "replay.html")
    shutil.copy(TEMPLATE / "app.js", out_dir / "app.js")
    (out_dir / "data.js").write_text(
        "// generated by replay_export.py from %s\nwindow.REPLAY = %s;\n" % (stem, json.dumps(data, ensure_ascii=False, separators=(",", ":"))),
        encoding="utf-8")
    boxed = sum(len(p["boxes"]) for p in pages_obj.values())
    print("%-28s -> %s  (pages %s, %d boxed words)" % (stem, out_dir / "replay.html", pages_needed, boxed))
    return out_dir / "replay.html"


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("records", nargs="+", help="session record .json files (pulled from the phone)")
    ap.add_argument("--mushaf", default=str(MUSHAF))
    ap.add_argument("--glyphdb", required=True, help="ayahinfo_1024.db (copy from the phone's /sdcard/Iqra)")
    ap.add_argument("--pages-dir", required=True, help="directory of 001.png..604.png page bitmaps")
    a = ap.parse_args()
    mushaf = json.load(open(a.mushaf, encoding="utf-8"))
    pages_dir = Path(a.pages_dir)
    out_root = HERE / "out" / "replay"
    for one in a.records:
        build(one, mushaf, a.glyphdb, pages_dir, out_root)


if __name__ == "__main__":
    main()

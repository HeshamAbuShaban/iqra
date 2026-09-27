#!/usr/bin/env python3
"""Poll the reader's active-ayah line and record every change (= lock moves).

The expected-words bar shows the LOCKED ayah's words, so a change in that text
is a lock advance. uiautomator dump is the only window into Compose state
without touching the app, and it caps us at roughly one sample per 1-2s.
"""
import re
import subprocess
import sys
import time

ARABIC = re.compile(r"[؀-ۿ]")
DUR = int(sys.argv[1]) if len(sys.argv) > 1 else 100


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/u.xml"],
                   capture_output=True, timeout=30)
    r = subprocess.run(["adb", "shell", "cat", "/sdcard/u.xml"],
                       capture_output=True, text=True, timeout=30)
    return r.stdout


def arabic_lines(xml):
    out = []
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = m.group(1)
        if not ARABIC.search(t):
            continue
        y = int(m.group(3))
        # the expected-words bar sits just above the bottom bar
        if 1700 < y < 2100:
            out.append((y, t))
    return sorted(out)


def main():
    t0 = time.time()
    last = None
    seen = []
    while time.time() - t0 < DUR:
        try:
            xml = dump()
        except Exception:
            time.sleep(1)
            continue
        lines = arabic_lines(xml)
        sig = " | ".join(t for _y, t in lines)
        if sig and sig != last:
            el = time.time() - t0
            print("[%5.1fs] ACTIVE AYAH: %s" % (el, sig), flush=True)
            seen.append((round(el, 1), sig))
            last = sig
        time.sleep(0.6)
    print()
    print("distinct states observed: %d" % len(seen))


if __name__ == "__main__":
    main()

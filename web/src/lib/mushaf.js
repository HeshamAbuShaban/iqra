/**
 * Mushaf page model for the web app. Loads the same Madinah page layout data
 * the Android app ships (public/data/mushaf.json — copied from
 * android/app/src/main/assets), and indexes it by verse so the practice screen
 * can render any ayah range with per-word spans.
 */

const ARABIC_NUM = '٠١٢٣٤٥٦٧٨٩'

/** Convert a Western integer to Eastern Arabic numerals (for ayah markers). */
export function toArabicNum(n) {
  return String(n)
    .split('')
    .map((d) => ARABIC_NUM[+d] ?? d)
    .join('')
}

let pages = null // page -> [{line fields..., verses:[{surah,ayah,words:[{text, loc}]}]}]
let verseIndex = new Map() // "S:A" -> { surah, ayah, words:[{text, loc}], page }
let juzStarts = []

function parseVerseRange(range) {
  // "1:1-1:7" or "1:2-1:2"
  if (!range) return null
  const m = range.match(/^(\d+):(\d+)-(\d+):(\d+)$/)
  if (!m) return null
  return { s1: +m[1], a1: +m[2], s2: +m[3], a2: +m[4] }
}

/** Split one line's text into words, tagging trailing ayah-number glyphs. */
function splitWords(text) {
  return text.split(/\s+/).filter(Boolean)
}

export async function loadMushaf() {
  if (pages) return pages
  const raw = await fetch('./data/mushaf.json').then((r) => r.json())
  pages = new Map()
  for (const p of raw) {
    const lines = []
    for (const ln of p.lines || []) {
      const line = { ...ln, verses: [] }
      const vr = parseVerseRange(ln.verseRange)
      if (vr && ln.type === 'text') {
        const toks = splitWords(ln.text)
        // Distribute tokens across the ayat on this line using the word
        // locations when present; fall back to ayah-marker splitting.
        const byLoc = new Map()
        for (const w of ln.words || []) {
          const m = w.location && w.location.match(/^(\d+):(\d+):(\d+)$/)
          if (!m) continue
          const key = `${m[1]}:${m[2]}`
          if (!byLoc.has(key)) byLoc.set(key, [])
          byLoc.get(key).push(w)
        }
        for (let s = vr.s1, a = vr.a1; !(s === vr.s2 && a > vr.a2); ) {
          const key = `${s}:${a}`
          const ws = byLoc.get(key)
          let texts
          if (ws && ws.length) {
            // Word entries carry the mushaf orthography; strip the ayah number
            // token from the last word's display text.
            texts = ws.map((w) => w.word.replace(/\s*[٠-٩]+\s*$/, '').trim()).filter(Boolean)
          } else {
            texts = []
          }
          line.verses.push({ surah: s, ayah: a, key, words: texts })
          if (s === vr.s2 && a === vr.a2) break
          a++
          // rollover handled by verseRange always being within one line/one
          // surah in this dataset; guard anyway:
          if (a > 9999) break
        }
        // If any token of the line is unaccounted for (no `words` array),
        // rebuild verse word lists from the raw text via ayah markers.
        if (line.verses.some((v) => v.words.length === 0)) {
          const rebuilt = []
          let cur = []
          for (const t of toks) {
            if (/^[٠-٩]+$/.test(t)) {
              const num = [...t].reduce((acc, d) => acc * 10 + ARABIC_NUM.indexOf(d), 0)
              rebuilt.push({ ayah: num, words: cur })
              cur = []
            } else cur.push(t)
          }
          if (cur.length) rebuilt.push({ ayah: null, words: cur })
          let ti = 0
          const outVerses = []
          for (let s = vr.s1, a = vr.a1; !(s === vr.s2 && a > vr.a2); ) {
            const rb = rebuilt[ti]
            outVerses.push({
              surah: s,
              ayah: a,
              key: `${s}:${a}`,
              words: rb ? rb.words : [],
            })
            ti++
            if (s === vr.s2 && a === vr.a2) break
            a++
          }
          line.verses = outVerses
        }
      }
      lines.push(line)
    }
    pages.set(p.page, lines)
    for (const ln of lines)
      for (const v of ln.verses) {
        if (!verseIndex.has(v.key))
          verseIndex.set(v.key, { ...v, page: p.page })
      }
  }
  return pages
}

export function getPage(page) {
  return pages ? pages.get(page) : null
}

export function getVerse(key) {
  return verseIndex.get(key) || null
}

export function allVerseKeysInRange(surah, from, to) {
  const keys = []
  for (let a = from; a <= to; a++) {
    const k = `${surah}:${a}`
    if (verseIndex.has(k)) keys.push(k)
  }
  return keys
}

/** Total verses available per surah (from indexed data). */
export function verseCountOf(surah) {
  let c = 0
  while (verseIndex.has(`${surah}:${c + 1}`)) c++
  return c
}

export function setJuzStarts(list) {
  juzStarts = list
}
export function getJuzStarts() {
  return juzStarts
}

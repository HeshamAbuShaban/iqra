/**
 * Web port of the recognition alignment kernel from
 * android/app/src/main/java/com/iqra/quran/ml/UnitAligner.kt and PhonemeMapper.kt.
 *
 * The tie-break order (substitution, then deletion, then insertion) is
 * load-bearing: engine/replay/dp_source_parity.py pins Kotlin and Python to the
 * same digest, and this file keeps that same order so web verdicts match the
 * device verdicts unit-for-unit.
 */

export const WordStatus = {
  CORRECT: 'CORRECT',
  SKIPPED: 'SKIPPED',
  WRONG: 'WRONG',
  EXTRA: 'EXTRA',
  UNKNOWN: 'UNKNOWN',
}

/**
 * Align `qry` (emitted units) against `ref` (expected units).
 * Returns refToQuery: result[j] = index in qry matched by expected unit j, or -1.
 */
export function refToQuery(qry, ref) {
  const n = qry.length
  const len = ref.length
  const out = new Array(len).fill(-1)
  if (len === 0 || n === 0) return out

  const width = len + 1
  const dir = new Uint8Array((n + 1) * width)
  let prev = new Int32Array(width)
  for (let j = 0; j < width; j++) prev[j] = j // dp[0][j] = j
  let cur = new Int32Array(width)

  for (let i = 1; i <= n; i++) {
    cur[0] = i // dp[i][0] = i
    const qi = qry[i - 1]
    const row = i * width
    for (let j = 1; j <= len; j++) {
      const cost = qi === ref[j - 1] ? 0 : 1
      const sub = prev[j - 1] + cost
      const del = prev[j] + 1
      const ins = cur[j - 1] + 1
      let best = sub
      let d = 0
      if (del < best) { best = del; d = 1 }
      if (ins < best) { best = ins; d = 2 }
      cur[j] = best
      dir[row + j] = d
    }
    const t = prev
    prev = cur
    cur = t
  }

  let i = n
  let j = len
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0) {
      const d = dir[i * width + j]
      if (d === 0) { out[j - 1] = i - 1; i--; j-- }
      else if (d === 1) { i-- }
      else if (d === 2) { j-- }
      else return out
    } else if (i > 0) {
      i--
    } else {
      j--
    }
  }
  return out
}

/**
 * Expected side: explode each word's phoneme string into the model's own unit
 * inventory with greedy longest-match (mirrors PhonemeMapper.explode).
 * `unitsByLength` must be sorted longest-first.
 */
export function explodeWord(wordPhonemes, unitsByLength) {
  const out = []
  if (!unitsByLength) return wordPhonemes.split(/\s+/).filter(Boolean)
  let i = 0
  const n = wordPhonemes.length
  outer: while (i < n) {
    for (const u of unitsByLength) {
      const ul = u.length
      if (ul <= n - i && wordPhonemes.startsWith(u, i)) {
        out.push(u)
        i += ul
        continue outer
      }
    }
    i++ // codepoint the model does not emit
  }
  return out
}

/** Build the Expected structure for one ayah: {wordCount, units, unitWord}. */
export function buildExpected(wordPhonemes, unitsByLength) {
  const units = []
  const unitWord = []
  for (let wi = 0; wi < wordPhonemes.length; wi++) {
    for (const u of explodeWord(wordPhonemes[wi], unitsByLength)) {
      units.push(u)
      unitWord.push(wi)
    }
  }
  return { wordCount: wordPhonemes.length, units, unitWord }
}

/**
 * Unit-level edit DP of emitted phonemes against an ayah's expected units.
 * Verdict rules (identical to PhonemeMapper.align on-device):
 *  - CORRECT: every unit of the word matched
 *  - SKIPPED: fewer than half the units matched (coverage, not mismatch count)
 *  - WRONG:   some unit landed on a contradicted phoneme
 *  - UNKNOWN: partly covered, nothing contradicted — no verdict
 */
export function align(emitted, expected, probs = null) {
  const m = expected.wordCount
  const flat = expected.units
  const wordOf = expected.unitWord
  const n = emitted.length
  const len = flat.length
  if (len === 0 || n === 0) {
    return {
      statuses: len === 0 ? new Array(m).fill(WordStatus.SKIPPED) : [],
      emitWord: new Array(n).fill(-1),
      wordProb: new Array(m).fill(-1),
      unitsMatched: 0,
      unitsTotal: len,
      coverage: 0,
    }
  }

  // Intern units to dense ints once (same trick as the Kotlin side).
  const ids = new Map()
  for (const u of flat) if (!ids.has(u)) ids.set(u, ids.size)
  const ref = flat.map((u) => ids.get(u))
  const qry = emitted.map((u) => (ids.has(u) ? ids.get(u) : -1))

  const r2q = refToQuery(Int32Array.from(qry), Int32Array.from(ref))

  const matched = new Uint8Array(len)
  const wrong = new Uint8Array(len)
  const emitWord = new Array(n).fill(-1)
  let unitsMatched = 0
  for (let j = 0; j < len; j++) {
    const qi = r2q[j]
    if (qi < 0) continue
    if (qry[qi] === ref[j]) {
      matched[j] = 1
      unitsMatched++
    } else {
      wrong[j] = 1
    }
    emitWord[qi] = wordOf[j]
  }

  // Per-word unit tallies in one pass over the units.
  const total = new Int32Array(m)
  const ok = new Int32Array(m)
  const bad = new Int32Array(m)
  for (let k = 0; k < len; k++) {
    const wi = wordOf[k]
    total[wi]++
    if (matched[k]) ok[wi]++
    if (wrong[k]) bad[wi]++
  }

  const statuses = new Array(m)
  for (let wi = 0; wi < m; wi++) {
    if (total[wi] === 0) statuses[wi] = WordStatus.SKIPPED
    else if (ok[wi] === total[wi]) statuses[wi] = WordStatus.CORRECT
    else if (ok[wi] * 2 < total[wi]) statuses[wi] = WordStatus.SKIPPED
    else if (bad[wi] > 0) statuses[wi] = WordStatus.WRONG
    else statuses[wi] = WordStatus.UNKNOWN
  }

  const wordProb = new Array(m).fill(-1)
  if (probs) {
    const sum = new Float64Array(m)
    const cnt = new Int32Array(m)
    for (let k = 0; k < n; k++) {
      const wi = emitWord[k]
      if (wi >= 0 && wi < m && k < probs.length) {
        sum[wi] += probs[k]
        cnt[wi]++
      }
    }
    for (let wi = 0; wi < m; wi++) if (cnt[wi] > 0) wordProb[wi] = sum[wi] / cnt[wi]
  }

  return {
    statuses,
    emitWord,
    wordProb,
    unitsMatched,
    unitsTotal: len,
    coverage: len === 0 ? 0 : unitsMatched / len,
  }
}

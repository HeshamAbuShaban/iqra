package com.iqra.quran.ml

import com.iqra.quran.data.Verse

/**
 * Verse recognizer: maps a CTC-decoded Arabic transcript to the best-matching
 * Quran verse (and optional multi-verse span). Faithfully ports the scoring
 * strategy of @tilawa/core (Levenshtein ratio + fragment boost), with an
 * n-gram shortlist to keep it cheap on low-end devices.
 */
class VerseMatcher(private val data: com.iqra.quran.data.QuranData) {

    data class VerseMatch(
        val surah: Int,
        val ayah: Int,
        val ayahEnd: Int,
        val score: Double,
        val transcript: String,
    )

    fun bestMatch(transcript: String, scope: Set<Int>? = null): VerseMatch? {
        val text = transcript.trim()
        if (text.isEmpty() || data.verses.isEmpty()) return null

        // Reciters almost always preface a surah with the Basmala and/or the
        // Ta'awwudh. Those prefixes are NOT part of any ayah's reference text
        // (outside Al-Fatiha) and, in real observation, cause the tracker to
        // lock onto the wrong verse (e.g. the Basmala maps onto 1:1 and the real
        // recitation gets misrouted). Strip them before matching.
        val stripped = stripPrefixes(text)
        val noSpace = stripped.replace(" ", "")
        val candidates = if (noSpace.length < 4) {
            data.verses
        } else {
            data.shortlist(noSpace)
        }

        val FRAG_BLEND = 0.82
        val scoreRef: (String, String) -> Double = { q, ref ->
            if (ref.isEmpty()) 0.0
            else {
                val qn = q.replace(" ", "")
                var raw = Levenshtein.ratio(q, ref)
                if (qn.length <= 10) raw = maxOf(raw, shortBoost(qn, ref))
                if (qn.length >= 8) {
                    val frag = Levenshtein.fragmentScore(qn, ref.replace(" ", ""))
                    if (frag > raw) raw = raw + (frag - raw) * FRAG_BLEND
                }
                raw
            }
        }

        val singles = candidates.map { v ->
            val ref = data.referenceText(v) ?: ""
            // Score both the raw and the prefix-stripped transcript and keep the
            // best, so an ayah whose reference legitimately contains the Basmala
            // (Al-Fatiha 1:1) still matches.
            val raw = maxOf(scoreRef(text, ref), scoreRef(stripped, ref))
            Candidate(v.surah, v.ayah, v.ayah, raw)
        }

        val scoped = if (scope != null) singles.filter { scope.contains(it.surah) } else singles
        if (scoped.isEmpty()) return null

        val topSurahs = scoped.sortedByDescending { it.score }
            .take(32)
            .map { it.surah }
            .distinct()

        val spans = mutableListOf<Candidate>()
        for (surah in topSurahs) {
            val vs = data.getSurah(surah)
            for (i in vs.indices) {
                for (span in 2..6) {
                    if (i + span > vs.size) break
                    val chunk = vs.subList(i, i + span)
                    val spanText = chunk.joinToString(" ") { data.referenceText(it) ?: "" }
                    val raw = maxOf(scoreRef(text, spanText), scoreRef(stripped, spanText))
                    spans.add(Candidate(chunk.first().surah, chunk.first().ayah, chunk.last().ayah, raw))
                }
            }
        }

        val best = (scoped + spans).maxByOrNull { it.score } ?: return null
        if (best.score < 0.3) return null
        return VerseMatch(best.surah, best.ayah, best.ayahEnd, best.score, stripped)
    }

    private fun shortBoost(noSpace: String, ref: String): Double {
        val candidate = ref.replace(" ", "")
        if (candidate.isEmpty()) return 0.0
        val window = minOf(candidate.length, noSpace.length + 6)
        return Levenshtein.ratio(noSpace, candidate.take(window))
    }

    // Basmala + Ta'awwudh, both spaced and unspaced, normalized alef/hamza.
    private val PREFIXES = listOf(
        "بسم الله الرحمن الرحيم",
        "بسماللهالرحمنالرحيم",
        "أعوذ بالله من الشيطان الرجيم",
        "أعوذباللهمنالشيطانالرجيم",
        "اعوذ بالله من الشيطان الرجيم",
        "اعوذباللهمنالشيطانالرجيم",
    )

    private fun stripPrefixes(s: String): String {
        var t = s
        var changed = true
        while (changed) {
            changed = false
            for (p in PREFIXES) {
                if (t.startsWith(p)) { t = t.substring(p.length); changed = true }
            }
        }
        return t.trim()
    }

    private data class Candidate(val surah: Int, val ayah: Int, val ayahEnd: Int, val score: Double)
}

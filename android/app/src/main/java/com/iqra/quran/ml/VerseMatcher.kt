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

        val noSpace = text.replace(" ", "")
        val candidates = if (noSpace.length < 4) {
            data.verses
        } else {
            data.shortlist(noSpace)
        }

        val FRAG_BLEND = 0.82
        val singles = candidates.map { v ->
            val ref = data.referenceText(v) ?: ""
            var raw = Levenshtein.ratio(text, ref)
            if (noSpace.length <= 10) raw = maxOf(raw, shortBoost(noSpace, ref))
            if (noSpace.length >= 8) {
                val frag = Levenshtein.fragmentScore(noSpace, ref.replace(" ", ""))
                if (frag > raw) raw = raw + (frag - raw) * FRAG_BLEND
            }
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
                    var raw = Levenshtein.ratio(text, spanText)
                    if (noSpace.length >= 8) {
                        val frag = Levenshtein.fragmentScore(noSpace, spanText.replace(" ", ""))
                        if (frag > raw) raw = raw + (frag - raw) * FRAG_BLEND
                    }
                    spans.add(Candidate(chunk.first().surah, chunk.first().ayah, chunk.last().ayah, raw))
                }
            }
        }

        val best = (scoped + spans).maxByOrNull { it.score } ?: return null
        if (best.score < 0.3) return null
        return VerseMatch(best.surah, best.ayah, best.ayahEnd, best.score, text)
    }

    private fun shortBoost(noSpace: String, ref: String): Double {
        val candidate = ref.replace(" ", "")
        if (candidate.isEmpty()) return 0.0
        val window = minOf(candidate.length, noSpace.length + 6)
        return Levenshtein.ratio(noSpace, candidate.take(window))
    }

    private data class Candidate(val surah: Int, val ayah: Int, val ayahEnd: Int, val score: Double)
}

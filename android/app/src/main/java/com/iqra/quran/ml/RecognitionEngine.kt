package com.iqra.quran.ml

import com.iqra.quran.data.WordStatus

interface RecognitionEngine {
    data class Result(
        val transcript: String,
        val tokenIds: IntArray,
        val logProbs: TilawaEngine.LogProbs?,
        val verse: Int?,
        val wordStatuses: List<WordStatus>,
        val currentKey: String?,
        val confidence: Double,
    )

    fun recognize(audio: FloatArray, lockedAyah: Int): Result
    fun close()
}

class ConstrainedCtcDecoder(
    private val vocab: Map<Int, String>,
    private val blankId: Int,
) {
    data class ConstrainedResult(
        val tokenIds: IntArray,
        val text: String,
        val prefixCoverage: Double,
        val isFullyCovered: Boolean,
    )

    fun decodeConstrained(
        logProbs: TilawaEngine.LogProbs,
        allowedTokenIds: Set<Int>,
        referenceTokens: IntArray,
    ): ConstrainedResult {
        val t = logProbs.timeSteps
        val v = logProbs.vocabSize
        val data = logProbs.data
        val out = mutableListOf<Int>()
        var prev = blankId
        var refIdx = 0
        for (ti in 0 until t) {
            var best = blankId
            var bestScore = Float.NEGATIVE_INFINITY
            val base = ti * v
            for (vi in 0 until v) {
                if (vi != blankId && vi !in allowedTokenIds) continue
                val s = data[base + vi]
                if (s > bestScore) {
                    bestScore = s
                    best = vi
                }
            }
            if (best != blankId && best != prev) {
                if (refIdx < referenceTokens.size && best == referenceTokens[refIdx]) {
                    out.add(best)
                    refIdx++
                } else if (best in allowedTokenIds) {
                    out.add(best)
                }
            }
            prev = best
        }
        val text = out.joinToString("") { vocab[it] ?: "" }.trim()
        val coverage = if (referenceTokens.isEmpty()) 1.0 else refIdx.toDouble() / referenceTokens.size
        return ConstrainedResult(out.toIntArray(), text, coverage, coverage >= 0.95)
    }
}

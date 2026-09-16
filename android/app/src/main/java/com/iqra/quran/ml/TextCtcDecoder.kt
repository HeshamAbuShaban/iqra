package com.iqra.quran.ml

import com.iqra.quran.data.WordResult
import com.iqra.quran.data.WordStatus

/**
 * Greedy CTC decoder + word segmentation, ported from
 * @tilawa/core/src/text-ctc-decode.ts. Operates purely on the vocab table and
 * the CTC blank id (1024).
 */
class TextCtcDecoder(
    private val vocab: Map<Int, String>,
    private val blankId: Int = 1024,
) {
    data class DecodeResult(val text: String, val tokenIds: IntArray)

    /** One non-blank CTC emission with its frame span — the basis for knowing
     *  which word the reciter is physically standing on each frame. */
    data class Emission(val tokenId: Int, val startFrame: Int, val endFrame: Int)

    data class TimedResult(val text: String, val tokenIds: IntArray, val emissions: List<Emission>)

    fun decode(logProbs: FloatArray, timeSteps: Int, vocabSize: Int): DecodeResult {
        val t = decodeTimed(logProbs, timeSteps, vocabSize)
        return DecodeResult(t.text, t.tokenIds)
    }

    fun decodeTimed(logProbs: FloatArray, timeSteps: Int, vocabSize: Int): TimedResult {
        val frameIds = IntArray(timeSteps)
        for (t in 0 until timeSteps) {
            val offset = t * vocabSize
            var maxIdx = 0
            var maxVal = logProbs[offset]
            for (v in 1 until vocabSize) {
                val value = logProbs[offset + v]
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = v
                }
            }
            frameIds[t] = maxIdx
        }

        val tokenIds = mutableListOf<Int>()
        val emissions = mutableListOf<Emission>()
        var previous = -1
        var runStart = 0
        for (t in frameIds.indices) {
            val id = frameIds[t]
            if (id != previous) {
                if (previous != -1 && previous != blankId) {
                    emissions.add(Emission(previous, runStart, t - 1))
                }
                if (id != blankId) tokenIds.add(id)
                runStart = t
            }
            previous = id
        }
        if (previous != -1 && previous != blankId) {
            emissions.add(Emission(previous, runStart, frameIds.size - 1))
        }
        return TimedResult(tokenIdsToText(tokenIds.toIntArray()), tokenIds.toIntArray(), emissions)
    }

    /** Raw per-word strings WITHOUT normalization — harakat kept — split on
     *  the ▁ boundary, so a wrong harakah can be told apart from a wrong word. */
    fun emissionsToStrictWords(emissions: List<Emission>): List<String> {
        val words = mutableListOf<StringBuilder>()
        var current: StringBuilder? = null
        for (e in emissions) {
            if (e.tokenId == blankId) continue
            val tok = vocab[e.tokenId] ?: continue
            if (tok == "<unk>" || tok == "<blank>") continue
            if (tok.startsWith('\u2581')) {
                current = StringBuilder()
                words.add(current)
                current.append(tok.substring(1))
            } else {
                if (current == null) {
                    current = StringBuilder()
                    words.add(current)
                }
                current.append(tok)
            }
        }
        return words.map { it.toString() }
    }

    companion object {
        private val DIACRITIC = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")

        fun hasDiacritic(s: String): Boolean = DIACRITIC.containsMatchIn(s)
    }

    fun tokenIdsToText(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id == blankId) continue
            val tok = vocab[id] ?: continue
            if (tok == "<unk>" || tok == "<blank>") continue
            sb.append(tok)
        }
        return ArabicNormalizer.normalize(sb.toString().replace('\u2581', ' ')).trim()
    }

    /** Split a verse token sequence into per-word token-id lists using the ▁ boundary. */
    fun splitWords(tokenIds: IntArray): List<IntArray> {
        val words = mutableListOf<MutableList<Int>>()
        var current: MutableList<Int>? = null
        for (id in tokenIds) {
            if (id == blankId) continue
            val tok = vocab[id] ?: continue
            if (tok == "<unk>" || tok == "<blank>") continue
            val isBoundary = tok.startsWith('\u2581')
            if (isBoundary) {
                current = mutableListOf()
                words.add(current)
            } else {
                if (current == null) {
                    current = mutableListOf()
                    words.add(current)
                }
            }
            current!!.add(id)
        }
        return words.map { it.toIntArray() }
    }
}

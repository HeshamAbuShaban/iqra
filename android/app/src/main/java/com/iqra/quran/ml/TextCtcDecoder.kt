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

    fun decode(logProbs: FloatArray, timeSteps: Int, vocabSize: Int): DecodeResult {
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
        var previous = -1
        for (id in frameIds) {
            if (id != previous && id != blankId) tokenIds.add(id)
            previous = id
        }
        return DecodeResult(tokenIdsToText(tokenIds.toIntArray()), tokenIds.toIntArray())
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

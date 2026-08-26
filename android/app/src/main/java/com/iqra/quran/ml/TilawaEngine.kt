package com.iqra.quran.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device acoustic model wrapper around the bundled Tilawa ONNX graph
 * (fastconformer CTC, mel preprocessing baked in). Feeds mono 16 kHz float32
 * PCM and returns flattened [T, vocab] log-probabilities.
 */
class TilawaEngine(context: Context, private val vocabSize: Int) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val signalName: String
    private val lengthName: String
    private val outputName: String

    init {
        val modelStream = context.assets.open("model.onnx")
        session = modelStream.use { env.createSession(it, OrtSession.SessionOptions()) }
        val names = session.inputNames.toList()
        signalName = names.firstOrNull { it.contains("audio") } ?: names[0]
        lengthName = names.firstOrNull { it.contains("length") } ?: names.getOrElse(1) { "length" }
        outputName = session.outputNames.first()
        Log.i("TilawaEngine", "inputs=$names signal=$signalName length=$lengthName out=$outputName")
    }

    data class LogProbs(val data: FloatArray, val timeSteps: Int, val vocabSize: Int)

    fun run(audio: FloatArray): LogProbs {
        val n = audio.size
        val signalBuf = FloatBuffer.wrap(audio)
        val signalTensor = OnnxTensor.createTensor(env, signalBuf, longArrayOf(1, n.toLong()))
        val lengthTensors = listOf(
            { OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(n.toLong())), longArrayOf(1)) },
            { OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(intArrayOf(n)), longArrayOf(1)) },
        )

        signalTensor.use { s ->
            var lastErr: Throwable? = null
            for (makeLength in lengthTensors) {
                val l = makeLength()
                try {
                    l.use {
                        val feeds = mapOf(signalName to s, lengthName to l)
                        session.run(feeds).use { result ->
                            val out = result.get(outputName)
                            out.use {
                                val fbuf = it.floatBuffer
                                val arr = FloatArray(fbuf.remaining())
                                fbuf.get(arr)
                                val t = arr.size / vocabSize
                                return LogProbs(arr, t, vocabSize)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    lastErr = e
                }
            }
            throw lastErr ?: IllegalStateException("TilawaEngine.run failed")
        }
    }

    fun close() = session.close()
}

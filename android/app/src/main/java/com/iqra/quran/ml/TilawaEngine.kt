package com.iqra.quran.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.IntBuffer

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
        val bytes = context.assets.open("model.onnx").use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions())
        val names = session.inputNames.toList()
        signalName = names.firstOrNull { it.contains("audio") } ?: names[0]
        lengthName = names.firstOrNull { it.contains("length") } ?: names.getOrElse(1) { "length" }
        outputName = session.outputNames.first()
        Log.i("TilawaEngine", "inputs=$names signal=$signalName length=$lengthName out=$outputName")
    }

    data class LogProbs(val data: FloatArray, val timeSteps: Int, val vocabSize: Int)

    fun run(audio: FloatArray): LogProbs {
        val n = audio.size
        val signalTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audio),
            longArrayOf(1, n.toLong()),
        )
        val lengthTensor = OnnxTensor.createTensor(
            env,
            IntBuffer.wrap(intArrayOf(n)),
            longArrayOf(1),
        )
        try {
            val feeds = mapOf(signalName to signalTensor, lengthName to lengthTensor)
            val result = session.run(feeds)
            try {
                val value = result[outputName].get() as OnnxTensor
                try {
                    val fbuf = value.floatBuffer
                    val arr = FloatArray(fbuf.remaining())
                    fbuf.get(arr)
                    val t = arr.size / vocabSize
                    return LogProbs(arr, t, vocabSize)
                } finally {
                    value.close()
                }
            } finally {
                result.close()
            }
        } finally {
            signalTensor.close()
            lengthTensor.close()
        }
    }

    fun close() = session.close()
}

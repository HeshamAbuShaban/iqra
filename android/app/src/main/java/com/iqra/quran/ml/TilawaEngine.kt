package com.iqra.quran.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * On-device acoustic model wrapper around the Tilawa ONNX graph
 * (fastconformer CTC, mel preprocessing baked in). Feeds mono 16 kHz float32
 * PCM and returns flattened [T, vocab] log-probabilities.
 */
class TilawaEngine(modelFile: File, private val vocabSize: Int) {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val signalName: String
    private val lengthName: String
    private val outputName: String

    init {
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
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
            LongBuffer.wrap(longArrayOf(n.toLong())),
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

package com.iqra.quran.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures mono 16 kHz 16-bit PCM from the microphone and returns it as a
 * normalized float32 buffer in [-1, 1], exactly what the Tilawa graph expects.
 */
class AudioRecorder(private val sampleRate: Int = 16000) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var running = false
    private val samples = ArrayList<Float>()

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, sampleRate * 2)
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        record?.startRecording()
        running = true
        samples.clear()
        thread = Thread {
            val shortBuf = ShortArray(1024)
            val rec = record ?: return@Thread
            while (running) {
                val read = rec.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    samples.add(shortBuf[i] / 32768.0f)
                }
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1500)
        thread = null
        record?.stop()
        record?.release()
        record = null
        Log.i("AudioRecorder", "captured ${samples.size} samples (${"%.1f".format(samples.size / sampleRate.toFloat())}s)")
        return samples.toFloatArray()
    }

    fun isRecording(): Boolean = running
}

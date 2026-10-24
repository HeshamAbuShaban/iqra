package com.iqra.quran.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The session's own audio, written to disk as it is captured: a plain WAV,
 * mono 16-bit at the recorder's rate, the same samples the recogniser hears.
 *
 * The ring in [AudioRecorder] keeps only the last 30 s; this keeps the whole
 * session, so a replay can hear exactly what the engine heard at any verdict
 * instant. It is the local evidence behind the record's
 * `audioFile`/`audioStartMs`/`audioSamples` triple, it lives beside the
 * record in `filesDir/sessions/`, and it never leaves the device.
 *
 * The header's sizes are placeholders until [close] patches them, so a
 * process death mid-session leaves a WAV whose header under-reports its
 * length while every captured sample is present - the record's
 * `audioSamples` (or the file size) is the honest count, and the orphan
 * sweep removes the file once no record references it.
 *
 * `firstSampleAtMs` is stamped at the FIRST write, not at open: the mic can
 * start noticeably after the session does, and aligning the WAV to the
 * record's clock from the open time would place every sample earlier than it
 * was heard. A replay anchors sample n at `firstSampleAtMs` plus n / rate.
 */
class SessionWavWriter private constructor(
    private val file: RandomAccessFile,
    private val sampleRate: Int,
) {
    /** Wall clock of the first sample written; 0 until the first write. */
    var firstSampleAtMs = 0L
        private set

    /** Samples written so far - the record's `audioSamples` while live. */
    var samples = 0L
        private set

    private var closed = false
    private var scratch = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)

    fun write(src: ShortArray, n: Int): Unit = synchronized(this) {
        if (closed || n <= 0) return
        if (firstSampleAtMs == 0L) firstSampleAtMs = System.currentTimeMillis()
        var off = 0
        while (off < n) {
            val chunk = minOf(n - off, scratch.capacity() / 2)
            scratch.clear()
            for (i in 0 until chunk) scratch.putShort(src[off + i])
            file.write(scratch.array(), 0, chunk * 2)
            off += chunk
        }
        samples += n
    }

    /** Patch the header sizes and close. Idempotent; returns the sample count. */
    fun close(): Long = synchronized(this) {
        if (closed) return samples
        closed = true
        runCatching {
            val dataBytes = samples * 2
            file.seek(4)
            file.write(leInt(36 + dataBytes))
            file.seek(40)
            file.write(leInt(dataBytes))
            file.close()
        }
        samples
    }

    private fun leInt(v: Long) = byteArrayOf(
        (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
        (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
    )

    companion object {
        /** Truncate [path], lay down a placeholder header, and hand back the writer. */
        fun open(path: File, sampleRate: Int): SessionWavWriter {
            val raf = RandomAccessFile(path, "rw").apply {
                setLength(0)
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()); h.putInt(36)
                h.put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()); h.putInt(16)
                h.putShort(1); h.putShort(1) // PCM, mono
                h.putInt(sampleRate); h.putInt(sampleRate * 2)
                h.putShort(2); h.putShort(16) // block align, bits per sample
                h.put("data".toByteArray()); h.putInt(0)
                write(h.array())
            }
            return SessionWavWriter(raf, sampleRate)
        }
    }
}

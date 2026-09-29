package com.iqra.quran.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures mono 16 kHz 16-bit PCM from the microphone and exposes it as a
 * normalized float32 buffer in [-1, 1].
 *
 * Storage is a fixed primitive ring, not a growing list. The recogniser polls
 * every 250ms and only ever wants the samples since its last read, but this
 * used to hand out a full copy of everything captured so far on every poll.
 * That is O(n) per poll on an unbounded, boxed ArrayList<Float>: quadratic over
 * a session, with the whole buffer duplicated four times a second and a
 * growing pile of short-lived Float objects for the collector. A five-minute
 * session ended up copying and re-boxing millions of samples for nothing.
 *
 * The ring is 30s - far more than a poll needs - and consumers read by
 * ABSOLUTE sample index, so "what did I not see yet" is a range copy of a few
 * thousand floats rather than a scan of the whole session.
 */
class AudioRecorder(private val sampleRate: Int = 16000) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val lock = Any()

    /** Ring capacity. Only the tail is ever read; older audio is discarded. */
    private val cap = sampleRate * 30
    private val buf = FloatArray(cap)

    /** Next write index into [buf]. */
    private var write = 0

    /** Retained sample count, saturating at [cap]. */
    private var filled = 0

    /** Absolute samples captured since the last [reset]. Never decreases
     *  between resets, so it is the cursor a consumer tracks. */
    private var total = 0L

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
        reset()
        thread = Thread {
            val shortBuf = ShortArray(1024)
            val rec = record ?: return@Thread
            while (running) {
                val read = rec.read(shortBuf, 0, shortBuf.size)
                if (read <= 0) continue
                append(shortBuf, read)
            }
        }.also { it.start() }
    }

    /** Append [n] converted samples, overwriting the oldest on overflow. */
    private fun append(src: ShortArray, n: Int) = synchronized(lock) {
        for (i in 0 until n) {
            buf[write] = src[i] / 32768.0f
            write = (write + 1) % cap
        }
        if (filled < cap) filled = minOf(cap, filled + n)
        total += n
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        record?.stop()
        record?.release()
        record = null
        val n = totalCount()
        Log.i("AudioRecorder", "captured $n samples (${"%.1f".format(n / sampleRate.toFloat())}s)")
    }

    fun isRecording(): Boolean = running

    /** Drop captured audio and restart the absolute cursor (re-anchor recognition). */
    fun reset() = synchronized(lock) {
        write = 0
        filled = 0
        total = 0L
    }

    /** Absolute samples captured since the last [reset]. */
    fun totalCount(): Long = synchronized(lock) { total }

    /**
     * Samples captured at or after absolute index [since], oldest first.
     *
     * Returns null if those samples have already been overwritten (the caller
     * was idle for longer than the ring is deep) and it must resync rather
     * than silently receive a gap of unrelated audio.
     */
    fun readSince(since: Long): FloatArray? = synchronized(lock) {
        val oldest = total - filled
        if (since < oldest) return null
        if (since >= total) return FloatArray(0)
        val from = (since - oldest).toInt()
        val n = (total - since).toInt().coerceAtMost(filled - from)
        val out = FloatArray(n)
        var i = ((write - filled + from) % cap + cap) % cap
        for (k in 0 until n) {
            out[k] = buf[i]
            i = (i + 1) % cap
        }
        out
    }

    /** Everything still retained, oldest first. */
    fun currentSamples(): FloatArray = synchronized(lock) {
        val out = FloatArray(filled)
        var i = ((write - filled) % cap + cap) % cap
        for (k in 0 until filled) {
            out[k] = buf[i]
            i = (i + 1) % cap
        }
        out
    }

    /** Total captured since the last reset, without copying (stall detection). */
    fun sampleCount(): Int = synchronized(lock) { total.toInt() }
}

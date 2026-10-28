package dev.personal.airplayrx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Screen-mirroring audio: AAC-ELD (44.1 kHz, stereo, 480 samples/packet) decoded by MediaCodec,
 * played through a low-latency AudioTrack, "as received" (lip sync is Milestone 10).
 *
 * Threads:
 * - [submit] runs on UxPlay's audio thread (via JNI): one native → input-buffer copy per packet.
 * - MediaCodec callbacks and every AudioTrack call run on "AirPlayRx-audio"
 *   (THREAD_PRIORITY_URGENT_AUDIO).
 *
 * Buffering: playback starts once [targetMs] of audio is queued (jitter buffer). If the queue
 * grows past target + [EXTRA_MS] — the iPhone's clock running slightly fast, or a network burst —
 * a decoded packet (~11 ms) is skipped so delay cannot creep up. If the track underruns while
 * audio is flowing, the target grows a step.
 */
class AudioPlayer {
    private val thread = HandlerThread("AirPlayRx-audio", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)

    private val lock = Any()
    private var codec: MediaCodec? = null  // guarded by lock
    private var failed = false             // guarded by lock; don't retry a broken decoder per packet
    private val freeInputs = ArrayBlockingQueue<Int>(32)
    @Volatile private var gain = 1f

    // Audio-thread state.
    private var track: AudioTrack? = null
    private var sampleRate = SAMPLE_RATE
    private var channels = CHANNELS
    private var framesWritten = 0         // Int on purpose: wraps together with playbackHeadPosition
    private var playing = false
    private var targetMs = START_TARGET_MS
    private var lastUnderruns = 0

    /** Running totals for the stats overlay (never reset). */
    class Totals {
        @Volatile var played = 0L
        @Volatile var trimmed = 0L
        @Volatile var dropped = 0L
        @Volatile var underruns = 0L
        @Volatile var bufferedMs = 0
        @Volatile var targetMs = 0
    }
    val totals = Totals()

    // Window stats. received/dropped are incremented on UxPlay's audio thread and read+reset on
    // the AirPlayRx-audio thread → atomics; the rest live on the audio thread.
    private val received = java.util.concurrent.atomic.AtomicInteger()
    private val dropped = java.util.concurrent.atomic.AtomicInteger()
    private var played = 0
    private var trimmed = 0
    private var windowStartNs = 0L

    // ---- UxPlay audio thread ------------------------------------------------------------------

    fun submit(len: Int, ptsUs: Long) {
        val c = synchronized(lock) { codec ?: if (failed) null else createCodecLocked() } ?: return
        received.incrementAndGet()
        val index = freeInputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
        if (index == null) {
            dropped.incrementAndGet()  // ~11 ms of sound; better than delaying everything after it
            totals.dropped++
            return
        }
        synchronized(lock) {
            if (codec !== c) return
            try {
                val buffer = c.getInputBuffer(index) ?: return
                buffer.clear()
                val n = NativeBridge.nativeCopyFrame(buffer)
                if (n < 0) {
                    freeInputs.offer(index)
                    dropped.incrementAndGet()
                    return
                }
                c.queueInputBuffer(index, 0, n, ptsUs, 0)
            } catch (e: IllegalStateException) {
                dropped.incrementAndGet()
            }
        }
    }

    // ---- Control (any thread) ----------------------------------------------------------------

    fun setVolume(linearGain: Float) {
        gain = linearGain.coerceIn(0f, 1f)
        handler.post { track?.setVolume(gain) }
    }

    /**
     * The iPhone paused or seeked: drop what is buffered and re-prime. Runs entirely on the audio
     * thread, where the codec callbacks and AudioTrack writes happen, so nothing races with an
     * in-flight output buffer.
     */
    fun flush() {
        handler.post {
            synchronized(lock) {
                codec?.let {
                    try {
                        freeInputs.clear()
                        it.flush()
                        it.start()  // async mode: flush() stops input callbacks until start()
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "audio: flush failed (${e.message})")
                    }
                }
            }
            track?.let { it.pause(); it.flush() }
            playing = false
            framesWritten = 0
            Log.i(TAG, "audio: flushed")
        }
    }

    fun onSessionEnd() {
        synchronized(lock) {
            releaseCodecLocked()
            failed = false
        }
        handler.post {
            logStats(System.nanoTime(), final = true)
            releaseTrack()
            targetMs = START_TARGET_MS
        }
    }

    fun shutdown() {
        synchronized(lock) { releaseCodecLocked() }
        handler.post { releaseTrack() }
        thread.quitSafely()
    }

    // ---- Decoder (callers hold lock) --------------------------------------------------------

    private fun createCodecLocked(): MediaCodec? {
        val format = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            // AudioSpecificConfig UxPlay uses for AAC-ELD (renderers/audio_renderer.c, aac_eld_caps):
            // ELD, 44100 Hz, 2 channels, 480-sample frames.
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ELD_CONFIG))
        }
        return try {
            val c = MediaCodec.createDecoderByType(MIME)
            freeInputs.clear()
            c.setCallback(Callbacks(c), handler)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "audio: decoder ${c.name} started for AAC-ELD")
            handler.post { resetStats(System.nanoTime()) }
            c
        } catch (e: Exception) {
            Log.e(TAG, "audio: cannot start AAC-ELD decoder — no sound this session", e)
            failed = true
            null
        }
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        codec = null
        freeInputs.clear()
        try {
            c.stop()
        } catch (_: Exception) {
        }
        c.release()
        Log.i(TAG, "audio: decoder released")
    }

    // ---- Audio thread -----------------------------------------------------------------------

    private inner class Callbacks(private val owner: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec === owner) freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0) {
                    val pcm = codec.getOutputBuffer(index)
                    if (pcm != null) {
                        pcm.limit(info.offset + info.size)  // limit first: position may not exceed it
                        pcm.position(info.offset)
                        play(pcm, info.size)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                return  // codec released concurrently
            }
            val now = System.nanoTime()
            if (now - windowStartNs >= STATS_INTERVAL_NS) logStats(now, final = false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "audio: decoder output $rate Hz, $ch channels")
            if (track == null || rate != sampleRate || ch != channels) {
                sampleRate = rate
                channels = ch
                releaseTrack()
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "audio: codec error transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
            if (e.isTransient) return
            synchronized(lock) { if (this@AudioPlayer.codec === owner) releaseCodecLocked() }  // next packet recreates
        }
    }

    private fun play(pcm: ByteBuffer, size: Int) {
        val t = track ?: createTrack() ?: return
        val bytesPerFrame = 2 * channels
        val queued = framesWritten - t.playbackHeadPosition  // Int arithmetic: wrap-safe
        totals.bufferedMs = queued * 1000 / sampleRate
        totals.targetMs = targetMs
        if (playing && queued > msToFrames(targetMs + EXTRA_MS)) {
            trimmed++
            totals.trimmed++
            return
        }
        val written = t.write(pcm, size, AudioTrack.WRITE_BLOCKING)
        if (written > 0) {
            framesWritten += written / bytesPerFrame
            played++
            totals.played++
        }
        if (!playing && framesWritten - t.playbackHeadPosition >= msToFrames(targetMs)) {
            t.play()
            playing = true
        }
    }

    private fun createTrack(): AudioTrack? {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // 4x the minimum, but always room for the largest jitter target.
        val bytes = maxOf(minBytes * 4, msToFrames(MAX_TARGET_MS + EXTRA_MS + 50) * 2 * channels)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
                .also {
                    it.setVolume(gain)
                    // A streaming track otherwise waits until its whole buffer (~340 ms here) is
                    // full before it starts; we never fill it that far (see EXTRA_MS), so it
                    // would stay silent forever. Start as soon as the jitter target is queued.
                    it.setStartThresholdInFrames(msToFrames(targetMs))
                    track = it
                    framesWritten = 0
                    playing = false
                    lastUnderruns = 0
                    Log.i(TAG, "audio: AudioTrack $sampleRate Hz x$channels, buffer ${it.bufferSizeInFrames} frames " +
                        "(min ${minBytes / (2 * channels)}), start threshold ${it.startThresholdInFrames}, " +
                        "performance mode ${it.performanceMode}, target $targetMs ms")
                }
        } catch (e: Exception) {
            Log.e(TAG, "audio: cannot create AudioTrack", e)
            null
        }
    }

    private fun releaseTrack() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
        playing = false
        framesWritten = 0
        lastUnderruns = 0  // underrunCount belongs to the released track
    }

    private fun msToFrames(ms: Int) = sampleRate * ms / 1000

    // ---- Stats (audio thread) ---------------------------------------------------------------

    private fun resetStats(now: Long) {
        received.set(0); dropped.set(0); played = 0; trimmed = 0
        windowStartNs = now
    }

    private fun logStats(now: Long, final: Boolean) {
        val t = track
        val underruns = t?.underrunCount ?: lastUnderruns
        val newUnderruns = underruns - lastUnderruns
        lastUnderruns = underruns
        totals.underruns += newUnderruns
        val bufferedMs = if (t != null) (framesWritten - t.playbackHeadPosition) * 1000 / sampleRate else 0
        val secs = (now - windowStartNs) / 1e9
        // Grow the jitter buffer only if sound was actually flowing (silence also "underruns").
        val flowing = played >= secs * PACKETS_PER_SECOND * 0.8
        val receivedNow = received.get()
        if (!final && newUnderruns > 0 && flowing && targetMs < MAX_TARGET_MS) {
            targetMs = minOf(targetMs + 10, MAX_TARGET_MS)
            Log.w(TAG, "audio: $newUnderruns underruns → jitter buffer now $targetMs ms")
        }
        if (receivedNow > 0 || played > 0 || (final && t != null)) {
            Log.i(TAG, "audio stats%s: %d in, %d played, %d dropped, %d trimmed, buffer %d ms (target %d), underruns +%d (total %d)".format(
                if (final) " (final)" else "", receivedNow, played, dropped.get(), trimmed, bufferedMs, targetMs, newUnderruns, underruns))
        }
        resetStats(now)
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private val AAC_ELD_CONFIG = byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
        private const val PACKETS_PER_SECOND = 44100.0 / 480

        private const val START_TARGET_MS = 40  // 30-50 ms balances crackles against delay
        private const val MAX_TARGET_MS = 150
        private const val EXTRA_MS = 60         // allowed above target before trimming
        private const val INPUT_WAIT_MS = 5L
        private const val STATS_INTERVAL_NS = 5_000_000_000L
    }
}

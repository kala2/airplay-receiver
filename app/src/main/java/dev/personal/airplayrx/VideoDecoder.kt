package dev.personal.airplayrx

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware H.264 decoder rendering straight to the activity's SurfaceView.
 *
 * Threads:
 * - [submit] runs on UxPlay's mirror thread (via JNI), once per access unit. It takes a free
 *   input buffer and has native code copy the frame directly into it (the only copy).
 * - MediaCodec callbacks run on the "AirPlayRx-video" HandlerThread; output buffers are
 *   rendered the moment they are ready, with no queue of our own.
 * - [onSurface] runs on the main thread.
 *
 * The iPhone sends a single keyframe per session, so the decoder must never lose its state
 * mid-session. It therefore never depends on the UI: without a visible surface it decodes into
 * an invisible placeholder ("parking") surface and switches with setOutputSurface().
 */
class VideoDecoder : MirrorState.SurfaceListener {
    private val thread = HandlerThread("AirPlayRx-video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private val lock = Any()
    private var codec: MediaCodec? = null       // guarded by lock
    private var surface: Surface? = null        // guarded by lock; the real (visible) surface
    private var parking: Surface? = null        // guarded by lock
    private var parkingTexture: SurfaceTexture? = null
    @Volatile private var rendering = false     // true while output goes to a visible surface
    private val freeInputs = ArrayBlockingQueue<Int>(64)

    @Volatile private var boxWidth = 1920   // the display box advertised to the iPhone
    @Volatile private var boxHeight = 1080

    /** Size the next decoder for this display box (call before the server starts). */
    fun setStreamBox(width: Int, height: Int) {
        boxWidth = width
        boxHeight = height
    }
    private var retryAfterNs = 0L          // guarded by lock; 0 = may try now
    private var loggedVendorParams = false
    private var loggedVendorLowLatency = false
    private var reportedNoDecoder = false

    /** Running totals for the stats overlay (never reset; the overlay shows per-second deltas). */
    class Totals {
        @Volatile var rendered = 0L
        @Volatile var dropped = 0L
        @Volatile var bytes = 0L
        @Volatile var latencySumUs = 0L
        @Volatile var latencyCount = 0L
        @Volatile var decoderName = ""
    }
    val totals = Totals()

    // Window stats. queued/notReady/dropped are incremented on the mirror thread and read+reset on
    // the video thread, so they are atomics; the rest live on the video thread.
    private val queued = java.util.concurrent.atomic.AtomicInteger()
    private val notReady = java.util.concurrent.atomic.AtomicInteger()
    private val dropped = java.util.concurrent.atomic.AtomicInteger()
    private var rendered = 0
    private var skipped = 0                     // decoded while no surface was visible
    private var latencySumUs = 0L
    private var latencyMaxUs = 0L
    private var windowStartNs = 0L

    // ---- Called from UxPlay's mirror thread ---------------------------------------------------

    /** Returns one of [QUEUED], [NOT_READY] (native keeps the frame and retries), [DROPPED]. */
    fun submit(len: Int, ptsUs: Long): Int {
        // While the decoder can't start (e.g. another app holds the hardware decoder), keep the
        // frames in the native FIFO instead of dropping them, and retry only every
        // RETRY_BACKOFF_NS rather than 60×/s.
        val c = synchronized(lock) {
            codec ?: if (System.nanoTime() < retryAfterNs) null else createCodecLocked()
        } ?: return NOT_READY
        val index = freeInputs.poll(INPUT_WAIT_MS, TimeUnit.MILLISECONDS)
        if (index == null) {
            notReady.incrementAndGet()
            return NOT_READY
        }
        synchronized(lock) {
            if (codec !== c) return NOT_READY  // codec was replaced while we waited
            try {
                val buffer = c.getInputBuffer(index) ?: return DROPPED.also { freeInputs.offer(index) }
                buffer.clear()
                val n = NativeBridge.nativeCopyFrame(buffer)
                if (n < 0) {
                    Log.e(TAG, "video: frame of $len bytes does not fit input buffer (${buffer.capacity()})")
                    freeInputs.offer(index)
                    dropped.incrementAndGet()
                    totals.dropped++
                    return DROPPED
                }
                c.queueInputBuffer(index, 0, n, ptsUs, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "video: codec not accepting input (${e.message})")
                return NOT_READY
            }
        }
        queued.incrementAndGet()
        totals.bytes += len
        return QUEUED
    }

    // ---- Session lifecycle (any thread) ---------------------------------------------------------

    fun onSessionStart() {
        handler.post { resetStats() }
    }

    fun onSessionEnd() {
        synchronized(lock) {
            releaseCodecLocked("session ended")
            retryAfterNs = 0L
        }
        handler.post { logStats(System.nanoTime(), final = true) }
    }

    /**
     * Creates and immediately releases one decoder so the first real session starts faster
     * (codec list scan, codec libraries, vendor init). Deliberately does not keep a decoder
     * around: the TV has few hardware decoder instances and an idle one could starve other apps.
     * Blocking; call on a worker thread.
     */
    fun prewarm() {
        synchronized(lock) {
            if (codec != null) return
            val start = System.nanoTime()
            createCodecLocked() ?: return
            releaseCodecLocked("prewarm")
            Log.i(TAG, "video: decoder prewarmed in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    /** Releases everything. Called once by ReceiverService.onDestroy. */
    fun shutdown() {
        synchronized(lock) {
            releaseCodecLocked("shutdown")
            parking?.release()
            parkingTexture?.release()
            parking = null
            parkingTexture = null
        }
        thread.quitSafely()
    }

    // ---- Surface hand-off (main thread) ---------------------------------------------------------

    override fun onSurface(surface: Surface?) {
        synchronized(lock) {
            if (surface === this.surface) return
            this.surface = surface
            val c = codec
            if (c == null) {
                rendering = surface != null  // the next codec is configured with this surface
                return
            }
            try {
                // Switch before returning: surfaceDestroyed() must not return while we still draw.
                c.setOutputSurface(surface ?: parkingSurfaceLocked())
                rendering = surface != null
                Log.i(TAG, "video: output → ${if (surface != null) "screen" else "parking surface"}")
            } catch (e: Exception) {
                Log.e(TAG, "video: setOutputSurface failed, recreating decoder", e)
                releaseCodecLocked("surface switch failed")
            }
        }
    }

    // ---- Codec management (callers hold lock) ---------------------------------------------------

    private fun createCodecLocked(): MediaCodec? {
        val info = decoderInfo
        if (info == null) {
            if (!reportedNoDecoder) Log.e(TAG, "video: no H.264 decoder available")
            reportedNoDecoder = true
            return null
        }
        val caps = info.getCapabilitiesForType(MIME)
        val w = boxWidth
        val h = boxHeight
        val edge = maxOf(w, h)
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            // Allow in-stream size changes (iPhone rotation) without reconfiguring. Square limit
            // on the long edge: portrait and landscape streams both fit (proven at 1920x1920).
            if (caps.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)) {
                val max = caps.videoCapabilities
                setInteger(MediaFormat.KEY_MAX_WIDTH, minOf(edge, max.supportedWidths.upper))
                setInteger(MediaFormat.KEY_MAX_HEIGHT, minOf(edge, max.supportedHeights.upper))
            }
            // Keyframes grow with resolution: 1 MB suffices for 1080p (largest seen 156 KB).
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(MIN_INPUT_SIZE, w * h / 2))
            if (caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)  // real-time
        }
        return try {
            val c = MediaCodec.createByCodecName(info.name)
            if (!loggedVendorParams) {
                Log.i(TAG, "video: ${info.name} vendor parameters: ${c.supportedVendorParameters}")
                loggedVendorParams = true
            }
            freeInputs.clear()
            c.setCallback(Callbacks(c), handler)
            val target = surface ?: parkingSurfaceLocked()
            c.configure(format, target, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            applyVendorLowLatency(c)
            rendering = surface != null
            codec = c
            totals.decoderName = info.name
            Log.i(TAG, "video: decoder ${info.name} started (${if (surface != null) "screen" else "parking surface"}), format $format")
            c
        } catch (e: Exception) {
            Log.e(TAG, "video: could not start decoder ${info.name}; retrying in ${RETRY_BACKOFF_NS / 1_000_000_000} s", e)
            retryAfterNs = System.nanoTime() + RETRY_BACKOFF_NS
            null
        }
    }

    /**
     * The Amlogic decoder exposes "vendor.START.low-latency.enable" (integer) but does not report
     * whether KEY_LOW_LATENCY already turned it on, so set it explicitly; harmless if it was.
     * No measurable change in the decode-latency stats on a Chromecast with Google TV (~10 ms).
     */
    private fun applyVendorLowLatency(c: MediaCodec) {
        val key = "vendor.START.low-latency.enable"
        try {
            if (key !in c.supportedVendorParameters) return
            c.setParameters(android.os.Bundle().apply { putInt(key, 1) })
            if (!loggedVendorLowLatency) Log.i(TAG, "video: $key = 1 applied")
        } catch (e: Exception) {
            Log.w(TAG, "video: could not set $key (${e.message})")
        } finally {
            loggedVendorLowLatency = true
        }
    }

    private fun releaseCodecLocked(reason: String) {
        val c = codec ?: return
        codec = null
        freeInputs.clear()
        try {
            c.stop()
        } catch (_: Exception) {
        }
        c.release()
        Log.i(TAG, "video: decoder released ($reason)")
    }

    private fun parkingSurfaceLocked(): Surface =
        parking ?: SurfaceTexture(false).let { texture ->
            texture.setDefaultBufferSize(16, 16)
            parkingTexture = texture
            Surface(texture).also { parking = it }
        }

    // ---- MediaCodec callbacks (video thread) ----------------------------------------------------

    private inner class Callbacks(private val owner: MediaCodec) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (codec === owner) freeInputs.offer(index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val show = rendering
            try {
                codec.releaseOutputBuffer(index, show)  // render now; never hold frames back
            } catch (e: IllegalStateException) {
                return  // codec released concurrently
            }
            if (show) {
                rendered++
                totals.rendered++
            } else {
                skipped++
            }
            val nowNs = System.nanoTime()
            // PTS is the native arrival time on the same CLOCK_MONOTONIC as System.nanoTime().
            val latency = nowNs / 1000 - info.presentationTimeUs
            latencySumUs += latency
            totals.latencySumUs += latency
            totals.latencyCount++
            if (latency > latencyMaxUs) latencyMaxUs = latency
            if (nowNs - windowStartNs >= STATS_INTERVAL_NS) logStats(nowNs, final = false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val width = if (format.containsKey("crop-right")) {
                format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            } else format.getInteger(MediaFormat.KEY_WIDTH)
            val height = if (format.containsKey("crop-bottom")) {
                format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            } else format.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "video: output format ${width}x$height")
            MirrorState.setVideoSize(width, height)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "video: codec error ${e.diagnosticInfo} transient=${e.isTransient} recoverable=${e.isRecoverable}", e)
            if (e.isTransient) return
            // A reset would need a new keyframe, which the iPhone only sends at session start,
            // so release; the next frame recreates the decoder.
            synchronized(lock) { if (this@VideoDecoder.codec === owner) releaseCodecLocked("codec error") }
        }
    }

    // ---- Stats (video thread) -------------------------------------------------------------------

    private fun resetStats() {
        queued.set(0); notReady.set(0); dropped.set(0)
        rendered = 0; skipped = 0; latencySumUs = 0; latencyMaxUs = 0
        windowStartNs = System.nanoTime()
    }

    private fun logStats(nowNs: Long, final: Boolean) {
        val secs = (nowNs - windowStartNs) / 1e9
        val out = rendered + skipped
        if (secs > 0 && (out > 0 || final)) {
            Log.i(TAG, "decoder stats%s: %.1f fps shown, %d decoded (%d hidden), %d queued, %d waited, %d dropped, latency avg %d ms max %d ms".format(
                if (final) " (final)" else "", rendered / secs, out, skipped, queued.get(), notReady.get(), dropped.get(),
                if (out > 0) latencySumUs / out / 1000 else 0, latencyMaxUs / 1000))
        }
        resetStats()
        windowStartNs = nowNs
    }

    companion object {
        // Must match video_sink.cpp.
        const val QUEUED = 0
        const val NOT_READY = 1
        const val DROPPED = 2

        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MIN_INPUT_SIZE = 1024 * 1024
        private const val INPUT_WAIT_MS = 20L
        private const val RETRY_BACKOFF_NS = 2_000_000_000L
        private const val STATS_INTERVAL_NS = 5_000_000_000L

        /** The decoder we use, picked once per process (MediaCodecList scan is slow). */
        val decoderInfo: MediaCodecInfo? by lazy { pickDecoder() }

        /** Whether the chosen H.264 decoder can decode width x height at fps (for [Resolutions]). */
        fun supports(width: Int, height: Int, fps: Int): Boolean = try {
            decoderInfo?.getCapabilitiesForType(MIME)?.videoCapabilities
                ?.areSizeAndRateSupported(width, height, fps.toDouble()) == true
        } catch (e: IllegalArgumentException) {
            false
        }

        /** Prefers a non-secure hardware decoder with low-latency support; logs every option. */
        private fun pickDecoder(): MediaCodecInfo? {
            val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }
            for (info in candidates) {
                val caps = info.getCapabilitiesForType(MIME)
                Log.i(TAG, "video: decoder ${info.name} hw=${info.isHardwareAccelerated} sw=${info.isSoftwareOnly} " +
                    "lowLatency=${caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)} " +
                    "adaptive=${caps.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback)} " +
                    "secureOnly=${caps.isFeatureRequired(CodecCapabilities.FEATURE_SecurePlayback)}")
            }
            val usable = candidates.filter {
                !it.getCapabilitiesForType(MIME).isFeatureRequired(CodecCapabilities.FEATURE_SecurePlayback)
            }
            val hardware = usable.filter { it.isHardwareAccelerated && !it.isSoftwareOnly }
                .sortedByDescending { it.getCapabilitiesForType(MIME).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) }
            hardware.firstOrNull()?.let { return it }
            val fallback = usable.firstOrNull()
            Log.e(TAG, "video: !!! NO HARDWARE H.264 DECODER — falling back to software decoder ${fallback?.name}; " +
                "expect high CPU use and dropped frames !!!")
            return fallback
        }
    }
}

package dev.personal.airplayrx

import android.content.Context
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * On-screen performance numbers, refreshed once a second from the decoders' running totals:
 * fps shown, dropped frames, incoming bitrate, decode latency, audio buffer, trims, underruns.
 * Main thread only. The on/off state is remembered across sessions.
 */
class StatsOverlay(context: Context, private val view: TextView, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private var job: Job? = null

    var enabled: Boolean = prefs.getBoolean(KEY_ENABLED, false)
        private set

    fun toggle() {
        enabled = !enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        update(MirrorState.active.value)
    }

    /** Shows the overlay only while mirroring and enabled. */
    fun update(sessionActive: Boolean) {
        val show = enabled && sessionActive
        view.visibility = if (show) View.VISIBLE else View.GONE
        if (show && job == null) {
            job = scope.launch { refreshLoop() }
        } else if (!show) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun refreshLoop() {
        var prevV = snapshotVideo()
        var prevA = snapshotAudio()
        var prevNs = System.nanoTime()
        view.text = "…"
        while (scope.isActive) {
            delay(1000)
            val now = System.nanoTime()
            val v = snapshotVideo()
            val a = snapshotAudio()
            val secs = (now - prevNs) / 1e9
            val frames = v.rendered - prevV.rendered
            val latCount = v.latencyCount - prevV.latencyCount
            val size = MirrorState.videoSize.value
            view.text = buildString {
                append("%s  %s\n".format(size?.let { "${it.width}x${it.height}" } ?: "-", v.decoder))
                append("video  %.1f fps   %.2f Mbit/s\n".format(frames / secs, (v.bytes - prevV.bytes) * 8 / secs / 1e6))
                append("       decode %d ms   dropped %d (total %d)\n".format(
                    if (latCount > 0) (v.latencySumUs - prevV.latencySumUs) / latCount / 1000 else 0,
                    v.dropped - prevV.dropped, v.dropped))
                append("audio  buffer %d ms (target %d)   underruns %d\n".format(a.bufferedMs, a.targetMs, a.underruns))
                append("       played %d/s   trimmed %d   dropped %d".format(
                    ((a.played - prevA.played) / secs).toInt(), a.trimmed - prevA.trimmed, a.dropped - prevA.dropped))
            }
            prevV = v
            prevA = a
            prevNs = now
        }
    }

    private data class VideoSnap(
        val rendered: Long, val dropped: Long, val bytes: Long,
        val latencySumUs: Long, val latencyCount: Long, val decoder: String,
    )

    private data class AudioSnap(
        val played: Long, val trimmed: Long, val dropped: Long, val underruns: Long,
        val bufferedMs: Int, val targetMs: Int,
    )

    private fun snapshotVideo(): VideoSnap {
        val t = NativeBridge.videoDecoder?.totals ?: return VideoSnap(0, 0, 0, 0, 0, "")
        return VideoSnap(t.rendered, t.dropped, t.bytes, t.latencySumUs, t.latencyCount, t.decoderName)
    }

    private fun snapshotAudio(): AudioSnap {
        val t = NativeBridge.audioPlayer?.totals ?: return AudioSnap(0, 0, 0, 0, 0, 0)
        return AudioSnap(t.played, t.trimmed, t.dropped, t.underruns, t.bufferedMs, t.targetMs)
    }

    private companion object {
        const val KEY_ENABLED = "stats_overlay"
    }
}

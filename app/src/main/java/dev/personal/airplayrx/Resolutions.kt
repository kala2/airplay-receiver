package dev.personal.airplayrx

import kotlin.math.roundToInt

/**
 * Which stream sizes to offer, from what the TV shows and what the hardware decoder can decode.
 * Pure logic (unit-tested); Android lookups are passed in.
 *
 * The size is the "display box" UxPlay advertises to the iPhone (width/height/refreshRate/maxFPS);
 * the iPhone fits its screen inside it (portrait 498x1080 in a 1920x1080 box).
 */
object Resolutions {
    data class Mode(val width: Int, val height: Int, val refreshHz: Float)

    data class Option(val width: Int, val height: Int, val fps: Int) {
        val key get() = "${width}x$height"
        val label get() = "${height}p" + if (fps < 50) " ($fps fps)" else ""
    }

    /** Standard 16:9 sizes worth offering (plus the TV's own native size if different). */
    private val LADDER = listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
    private val FRAME_RATES = listOf(60, 50, 30, 25, 24)
    private const val SMOOTH_FPS = 50

    /**
     * @param displayModes modes the connected TV reports (Display.getSupportedModes()).
     * @param decoderSupports whether the hardware decoder handles width x height at fps.
     * @return options from smallest to largest; never empty (falls back to 1080p@60, the
     *   long-standing default, if nothing can be determined).
     */
    fun options(displayModes: List<Mode>, decoderSupports: (Int, Int, Int) -> Boolean): List<Option> {
        val native = displayModes.maxByOrNull { it.width.toLong() * it.height } ?: return listOf(FALLBACK)
        val maxRefresh = displayModes.maxOf { it.refreshHz }.roundToInt().coerceAtMost(60)
        val sizes = (LADDER + (native.width to native.height)).distinct()
            .filter { (w, h) -> w <= native.width && h <= native.height }
            .sortedBy { (w, h) -> w.toLong() * h }
        val result = sizes.mapNotNull { (w, h) ->
            val fps = FRAME_RATES.firstOrNull { it <= maxRefresh && decoderSupports(w, h, it) } ?: return@mapNotNull null
            Option(w, h, fps)
        }
        return result.ifEmpty { listOf(FALLBACK) }
    }

    /** Auto: the largest option that is still smooth (≥50 fps); else the smoothest one. */
    fun auto(options: List<Option>): Option =
        options.lastOrNull { it.fps >= SMOOTH_FPS } ?: options.maxByOrNull { it.fps } ?: FALLBACK

    /** The stored choice ("auto" or "WxH") resolved against what is available now. */
    fun resolve(choice: String, options: List<Option>): Option =
        options.firstOrNull { it.key == choice } ?: auto(options)

    val FALLBACK = Option(1920, 1080, 60)
}

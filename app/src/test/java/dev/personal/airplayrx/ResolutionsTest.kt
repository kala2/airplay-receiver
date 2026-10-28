package dev.personal.airplayrx

import dev.personal.airplayrx.Resolutions.Mode
import dev.personal.airplayrx.Resolutions.Option
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionsTest {
    /** Amlogic H.264 decoder on the Chromecast: ~1,036,800 16x16 blocks/s, max 4096x4096. */
    private val amlogic: (Int, Int, Int) -> Boolean = { w, h, fps ->
        val blocks = ((w + 15) / 16).toLong() * ((h + 15) / 16)
        w <= 4096 && h <= 4096 && blocks * fps <= 1_036_800
    }

    @Test
    fun currentTv1080pOffers720And1080_autoIs1080p60() {
        val opts = Resolutions.options(listOf(Mode(1920, 1080, 59.94f)), amlogic)
        assertEquals(listOf(Option(1280, 720, 60), Option(1920, 1080, 60)), opts)
        assertEquals(Option(1920, 1080, 60), Resolutions.auto(opts))
    }

    @Test
    fun fourKTvOffers1440p60And2160p30_autoPrefersSmooth1440p() {
        val modes = listOf(Mode(3840, 2160, 60f), Mode(1920, 1080, 60f))
        val opts = Resolutions.options(modes, amlogic)
        assertEquals(
            listOf(Option(1280, 720, 60), Option(1920, 1080, 60), Option(2560, 1440, 60), Option(3840, 2160, 30)),
            opts,
        )
        assertEquals(Option(2560, 1440, 60), Resolutions.auto(opts))
        assertEquals("2160p (30 fps)", opts.last().label)
    }

    @Test
    fun displayRefreshCapsFrameRate() {
        val opts = Resolutions.options(listOf(Mode(1920, 1080, 50f)), amlogic)
        assertEquals(Option(1920, 1080, 50), opts.last())
    }

    @Test
    fun nonStandardNativeSizeIsOffered() {
        val opts = Resolutions.options(listOf(Mode(2560, 1080, 60f)), amlogic)
        assertEquals(listOf(Option(1280, 720, 60), Option(1920, 1080, 60), Option(2560, 1080, 60)), opts)
    }

    @Test
    fun storedChoiceFallsBackToAutoWhenNoLongerAvailable() {
        val opts = Resolutions.options(listOf(Mode(1920, 1080, 60f)), amlogic)
        assertEquals(Option(1280, 720, 60), Resolutions.resolve("1280x720", opts))
        assertEquals(Option(1920, 1080, 60), Resolutions.resolve("3840x2160", opts))  // old 4K TV choice
        assertEquals(Option(1920, 1080, 60), Resolutions.resolve("auto", opts))
    }

    @Test
    fun nothingKnownFallsBackTo1080p60() {
        assertEquals(listOf(Resolutions.FALLBACK), Resolutions.options(emptyList(), amlogic))
        assertEquals(listOf(Resolutions.FALLBACK), Resolutions.options(listOf(Mode(1920, 1080, 60f))) { _, _, _ -> false })
    }
}

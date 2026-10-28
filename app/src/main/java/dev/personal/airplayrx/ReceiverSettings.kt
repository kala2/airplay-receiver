package dev.personal.airplayrx

import android.content.Context

/**
 * User settings for the stream the iPhone sends. The quality choice is "auto" (default) or a
 * size key like "1920x1080"; which sizes exist depends on the TV and decoder (see [Resolutions]).
 */
class ReceiverSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var qualityChoice: String
        get() = when (val v = prefs.getString(KEY_QUALITY, null)) {
            null -> AUTO
            "HD_720" -> "1280x720"        // values stored by the earlier 720p/1080p-only version
            "FULL_HD_1080" -> "1920x1080"
            else -> v
        }
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    companion object {
        const val AUTO = "auto"
        private const val KEY_QUALITY = "quality"
    }
}

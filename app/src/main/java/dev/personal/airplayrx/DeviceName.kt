package dev.personal.airplayrx

/**
 * Rules for the name shown in the iPhone's Screen Mirroring list. Pure logic (unit-tested).
 *
 * - DNS-SD instance names are at most 63 bytes, and the RAOP service is "<12 hex>@<name>", so the
 *   name gets 63 - 13 = 50 bytes of UTF-8.
 * - '@' would confuse the "<mac>@<name>" split; '.' and '\' need DNS escaping that not every
 *   client handles; control characters are never sensible.
 * - It must differ from the TV's own device name: Android 14's mDNS advertiser silently refuses a
 *   second service with the same name, without telling the app.
 */
object DeviceName {
    const val MAX_BYTES = 50

    sealed interface Result {
        data class Ok(val name: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, TOO_LONG, BAD_CHARACTER, SAME_AS_TV }

    fun validate(input: String, tvDeviceName: String?): Result {
        val name = input.trim().replace(Regex("\\s+"), " ")
        return when {
            name.isEmpty() -> Result.Invalid(Reason.EMPTY)
            name.any { it.isISOControl() || it in FORBIDDEN } -> Result.Invalid(Reason.BAD_CHARACTER)
            name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> Result.Invalid(Reason.TOO_LONG)
            tvDeviceName != null && name.equals(tvDeviceName.trim(), ignoreCase = true) ->
                Result.Invalid(Reason.SAME_AS_TV)
            else -> Result.Ok(name)
        }
    }

    private val FORBIDDEN = setOf('@', '.', '\\')

    const val FALLBACK_DEFAULT = "Android TV AirPlay"

    /**
     * Default name for a fresh install: "<TV's own name> AirPlay" (so it can never clash with the
     * TV's name), cleaned to pass [validate] and cut to fit [MAX_BYTES]; [FALLBACK_DEFAULT] if the
     * TV has no usable name.
     */
    fun defaultFor(tvDeviceName: String?): String {
        val suffix = " AirPlay"
        var base = tvDeviceName.orEmpty().filterNot { it.isISOControl() || it in FORBIDDEN }
            .trim().replace(Regex("\\s+"), " ")
        if (base.isEmpty()) return FALLBACK_DEFAULT
        while ((base + suffix).toByteArray(Charsets.UTF_8).size > MAX_BYTES) base = base.dropLast(1).trimEnd()
        return if (base.isEmpty()) FALLBACK_DEFAULT else base + suffix
    }
}

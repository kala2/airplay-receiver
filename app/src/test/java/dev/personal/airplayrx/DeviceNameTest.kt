package dev.personal.airplayrx

import dev.personal.airplayrx.DeviceName.Reason
import dev.personal.airplayrx.DeviceName.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    private fun check(input: String, tv: String? = "Living Room TV") = DeviceName.validate(input, tv)

    @Test
    fun acceptsAndNormalisesWhitespace() {
        assertEquals(Result.Ok("Bedroom TV"), check("  Bedroom   TV "))
        assertEquals(Result.Ok("Living Room TV AirPlay"), check("Living Room TV AirPlay"))
    }

    @Test
    fun rejectsEmpty() {
        assertEquals(Result.Invalid(Reason.EMPTY), check("   "))
    }

    @Test
    fun rejectsTheTvsOwnNameCaseInsensitively() {
        assertEquals(Result.Invalid(Reason.SAME_AS_TV), check("living room tv"))
        assertEquals(Result.Ok("Living Room TV"), check("Living Room TV", tv = null))
    }

    @Test
    fun rejectsCharactersThatBreakServiceNames() {
        for (bad in listOf("TV@home", "Mr. TV", "a\\b", "bell\u0007")) {
            assertEquals(bad, Result.Invalid(Reason.BAD_CHARACTER), check(bad))
        }
    }

    @Test
    fun tabsAndLineBreaksBecomeSpaces() {
        assertEquals(Result.Ok("tab here"), check("tab\there"))
        assertEquals(Result.Ok("new line"), check("new\nline"))
    }

    @Test
    fun limitIsFiftyUtf8Bytes() {
        assertEquals(Result.Ok("x".repeat(50)), check("x".repeat(50)))
        assertEquals(Result.Invalid(Reason.TOO_LONG), check("x".repeat(51)))
        // "Σ" is 2 bytes in UTF-8: 25 fit, 26 do not.
        assertEquals(Result.Ok("Σ".repeat(25)), check("Σ".repeat(25)))
        assertEquals(Result.Invalid(Reason.TOO_LONG), check("Σ".repeat(26)))
    }

    @Test
    fun defaultNameIsTvNamePlusAirPlay() {
        assertEquals("Living Room TV AirPlay", DeviceName.defaultFor("Living Room TV"))
        assertEquals(Result.Ok("Living Room TV AirPlay"), check(DeviceName.defaultFor("Living Room TV")))
        assertEquals("Mr TV AirPlay", DeviceName.defaultFor("Mr. TV@home".replace("@home", "")))
        assertEquals(DeviceName.FALLBACK_DEFAULT, DeviceName.defaultFor(null))
        assertEquals(DeviceName.FALLBACK_DEFAULT, DeviceName.defaultFor("  "))
    }

    @Test
    fun defaultNameIsCutToFit() {
        val name = DeviceName.defaultFor("x".repeat(80))
        assertEquals(DeviceName.MAX_BYTES, name.toByteArray(Charsets.UTF_8).size)
        assertEquals(true, name.endsWith(" AirPlay"))
    }
}

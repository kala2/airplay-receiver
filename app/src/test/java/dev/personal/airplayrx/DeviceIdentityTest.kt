package dev.personal.airplayrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeviceIdentityTest {
    @Test
    fun randomMacHasUxPlayFormat() {
        repeat(1000) {
            val mac = DeviceIdentity.randomMac(Random(it))
            assertTrue(mac, DeviceIdentity.isValidMac(mac))
        }
    }

    @Test
    fun randomMacIsUnicastAndLocallyAdministered() {
        repeat(1000) {
            val first = DeviceIdentity.randomMac(Random(it)).substring(0, 2).toInt(16)
            assertEquals("multicast bit", 0, first and 0x01)
            assertEquals("local bit", 0x02, first and 0x02)
        }
    }

    @Test
    fun validation() {
        assertTrue(DeviceIdentity.isValidMac("02:1a:2b:3c:4d:5e"))
        assertFalse(DeviceIdentity.isValidMac("02:1A:2B:3C:4D:5E")) // UxPlay uses lowercase
        assertFalse(DeviceIdentity.isValidMac("02:1a:2b:3c:4d"))
        assertFalse(DeviceIdentity.isValidMac("021a2b3c4d5e"))
    }
}

package dev.personal.airplayrx

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.net.InetAddress
import java.net.Inet4Address

class BleBeaconTest {
    @Test
    fun payloadMatchesUxPlayBeaconFormat() {
        // uxplay_beacon_module_BlueZ.py: [0x09, 0x08, 0x13, 0x30] + ipv4 + port (big-endian).
        val ip = InetAddress.getByName("192.168.0.10") as Inet4Address
        val expected = byteArrayOf(
            0x09, 0x08, 0x13, 0x30,
            192.toByte(), 168.toByte(), 0, 10,
            (42809 shr 8).toByte(), (42809 and 0xFF).toByte(),
        )
        assertArrayEquals(expected, BleBeacon.payload(ip, 42809))
    }
}

package dev.personal.airplayrx

import android.content.Context
import android.provider.Settings
import kotlin.random.Random

/**
 * The receiver's stable identity: a pseudo-MAC and the name shown on the iPhone.
 *
 * Android apps cannot read the real Wi-Fi MAC, so a random locally-administered one is generated
 * once and kept in SharedPreferences. UxPlay derives the RAOP service-name prefix, the AirPlay
 * `deviceid` and the pairing key from it, so it must never change between runs — otherwise the
 * iPhone sees a "new" device and pairing breaks.
 */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    /** "<TV name> AirPlay", computed when no name was ever saved (follows a TV rename). */
    private val defaultName: String
        get() = DeviceName.defaultFor(Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME))

    /** Lowercase "xx:xx:xx:xx:xx:xx", the format UxPlay's own random_mac() produces. */
    val mac: String
        get() = prefs.getString(KEY_MAC, null)?.takeIf(::isValidMac)
            ?: randomMac(Random.Default).also { prefs.edit().putString(KEY_MAC, it).apply() }

    var name: String
        get() = prefs.getString(KEY_NAME, null) ?: defaultName
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    companion object {
        private const val KEY_MAC = "mac"
        private const val KEY_NAME = "name"
        private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        fun isValidMac(mac: String) = MAC_REGEX.matches(mac)

        /** Same rule as uxplay.cpp random_mac(): unicast (bit 0 clear), locally administered (bit 1 set). */
        fun randomMac(random: Random): String {
            val bytes = random.nextBytes(6)
            bytes[0] = ((bytes[0].toInt() and 0x01.inv()) or 0x02).toByte()
            return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

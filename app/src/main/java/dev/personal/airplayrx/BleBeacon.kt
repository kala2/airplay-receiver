package dev.personal.airplayrx

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.net.Inet4Address

/**
 * Bluetooth LE "AirPlay beacon": the same advertisement UxPlay's Bluetooth_LE_beacon/uxplay-beacon.py
 * sends (v1.73.7): Apple manufacturer data (company 0x004C) = 09 08 13 30 + IPv4 (4 bytes) +
 * TCP port (2 bytes, big-endian). Nearby iPhones learn the receiver's address over Bluetooth,
 * which can list it much faster than mDNS alone (Apple TVs are found the same way).
 *
 * Needs the BLUETOOTH_ADVERTISE ("Nearby devices") runtime permission, requested by MainActivity.
 * Without it, or with Bluetooth off, the beacon is skipped and mDNS still works.
 * Called on ReceiverService's control thread.
 */
class BleBeacon(private val context: Context) {
    @Volatile  // written by the binder thread on failure, used on the control thread
    private var advertising: Pair<Inet4Address, Int>? = null

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE AirPlay beacon on: ${advertising?.first?.hostAddress}:${advertising?.second}")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE AirPlay beacon failed to start: error $errorCode")
            advertising = null
        }
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    /** Starts, or restarts if the address or port changed. No-op if already advertising them. */
    fun start(ipv4: Inet4Address, port: Int) {
        if (advertising == ipv4 to port) return
        stop()
        if (!hasPermission()) {
            Log.i(TAG, "BLE beacon skipped: \"Nearby devices\" permission not granted (mDNS still used)")
            return
        }
        val advertiser = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.i(TAG, "BLE beacon skipped: Bluetooth off or LE advertising unsupported")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)  // ~100 ms: found quickly
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)     // room-sized range
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(APPLE_COMPANY_ID, payload(ipv4, port))
            .build()
        try {
            advertising = ipv4 to port
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE beacon not allowed: ${e.message}")
            advertising = null
        }
    }

    fun stop() {
        if (advertising == null) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser
                ?.stopAdvertising(callback)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile; the system stops our advertising anyway.
        }
        advertising = null
        Log.i(TAG, "BLE AirPlay beacon off")
    }

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C

        /** 09 08 13 30 = Apple data unit "AirPlay target", length 8, flags, seed (as UxPlay). */
        fun payload(ipv4: Inet4Address, port: Int): ByteArray =
            byteArrayOf(0x09, 0x08, 0x13, 0x30) + ipv4.address +
                byteArrayOf((port shr 8).toByte(), port.toByte())
    }
}

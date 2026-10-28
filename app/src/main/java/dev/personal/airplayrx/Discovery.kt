package dev.personal.airplayrx

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises UxPlay's `_raop._tcp` and `_airplay._tcp` services with NsdManager, using exactly
 * the names and TXT records UxPlay built (they arrive via [NativeBridge.ServiceListener]).
 *
 * Methods may be called from any thread; NsdManager callbacks arrive on its own thread.
 * Owned by [ReceiverService].
 */
class Discovery(context: Context) : NativeBridge.ServiceListener {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val registrations = HashMap<String, NsdManager.RegistrationListener>()  // by type
    private val lastServices = HashMap<String, NsdServiceInfo>()  // by type, for reannounce()

    override fun onServiceRegistered(name: String, type: String, port: Int, txt: List<Pair<String, String?>>) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            setPort(port)
            for ((key, value) in txt) setAttribute(key, value)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // NsdManager may rename on a conflict; for _raop the name must stay "<mac>@<name>".
                if (info.serviceName != name) {
                    Log.w(TAG, "$type registered under a different name: \"${info.serviceName}\" (wanted \"$name\")")
                } else {
                    Log.i(TAG, "advertising $type \"$name\" on port $port")
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registering $type failed: error $errorCode")
                // Only forget this registration; a newer one for the same type may exist.
                synchronized(registrations) { registrations.remove(type, this) }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "stopped advertising $type")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "unregistering $type failed: error $errorCode")
            }
        }

        onServiceUnregistered(name, type)  // never two registrations of one type
        synchronized(registrations) {
            registrations[type] = listener
            lastServices[type] = info
        }
        Log.i(TAG, "registering $type \"$name\" port $port with ${txt.size} TXT keys: " +
            txt.joinToString(" ") { (k, v) -> if (k == "pk") "pk=…" else "$k=$v" })
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun onServiceUnregistered(name: String, type: String) {
        val listener = synchronized(registrations) {
            lastServices.remove(type)  // a withdrawn service must not come back via reannounce()
            registrations.remove(type)
        } ?: return
        try {
            nsd.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            // Already gone (e.g. registration had failed); nothing to undo.
        }
    }

    fun unregisterAll() {
        val types = synchronized(registrations) {
            lastServices.clear()
            registrations.keys.toList()
        }
        for (type in types) onServiceUnregistered("", type)
    }

    /**
     * Registers the current services again. Android 14's mDNS advertiser can stop answering after
     * hours of standby (even for the system's own services) until a service is re-registered;
     * ReceiverService calls this after a long standby and on Wi-Fi reconnects. Each call briefly
     * withdraws the receiver (iOS may take up to a minute to re-list it), so keep it rare. Does
     * not touch the AirPlay server, so an ongoing session is unaffected.
     */
    fun reannounce(reason: String) {
        val services = synchronized(registrations) { lastServices.values.toList() }
        if (services.isEmpty()) return
        Log.i(TAG, "re-announcing ${services.size} services ($reason)")
        for (info in services) {
            val txt = info.attributes.map { (k, v) -> k to v?.toString(Charsets.UTF_8) }
            onServiceRegistered(info.serviceName, info.serviceType, info.port, txt)
        }
    }
}

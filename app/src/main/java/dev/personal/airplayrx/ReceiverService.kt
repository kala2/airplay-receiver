package dev.personal.airplayrx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Foreground service that owns every long-lived resource: the native AirPlay server, service
 * discovery and the Wi-Fi multicast lock. Keeps running while other apps are in front.
 *
 * Lifecycle callbacks run on the main thread; native start/stop run serialized on a single
 * "AirPlayRx-control" thread so they never block the UI and never overlap.
 */
class ReceiverService : Service() {
    private val controlDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "AirPlayRx-control") }.asCoroutineDispatcher()
    // A failure in one control task is logged, never allowed to kill the process (and with it
    // a mirroring session).
    private val scope = CoroutineScope(
        SupervisorJob() + controlDispatcher +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "ReceiverService task failed", e) }
    )

    private lateinit var identity: DeviceIdentity
    private lateinit var discovery: Discovery
    private lateinit var videoDecoder: VideoDecoder
    private lateinit var audioPlayer: AudioPlayer
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lowLatencyLock: WifiManager.WifiLock? = null  // control thread
    private var started = false  // touched only on the control thread
    private var serverPort = 0   // control thread
    private var activeOption: Resolutions.Option? = null  // control thread; size the server runs with
    private var qualityRecheckPending = false             // control thread
    private lateinit var beacon: BleBeacon
    private var reannounceJob: Job? = null  // control thread

    /**
     * Wake-up after a long standby: the one case where Android's mDNS advertiser was seen to go
     * stale. Short screen-offs are ignored, because every re-announce briefly withdraws the
     * receiver and iOS can take up to a minute to list it again. Main thread.
     */
    private var screenOffAtMs = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAtMs = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    val asleepMs = if (screenOffAtMs == 0L) 0L else SystemClock.elapsedRealtime() - screenOffAtMs
                    if (asleepMs >= LONG_STANDBY_MS) scheduleReannounce("woke after ${asleepMs / 60_000} min standby")
                }
            }
        }
    }

    /**
     * A real Wi-Fi (re)connection. Address changes (e.g. rotating IPv6 privacy addresses) are left
     * to Android's advertiser, which handles them itself. ConnectivityManager's thread.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var first = true  // the initial onAvailable is just the current network
        override fun onAvailable(network: Network) {
            if (first) first = false else scheduleReannounce("network reconnected")
        }

        // The BLE beacon carries the IPv4 address, so follow address changes (no mDNS re-announce).
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            scope.launch { updateBeacon() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentity(this)
        discovery = Discovery(this)
        beacon = BleBeacon(this)
        videoDecoder = VideoDecoder()
        NativeBridge.videoDecoder = videoDecoder
        audioPlayer = AudioPlayer()
        NativeBridge.audioPlayer = audioPlayer
        MirrorState.surfaceListener = videoDecoder
        // Wi-Fi power saving adds latency; keep the radio in low-latency mode only while mirroring.
        scope.launch { MirrorState.active.collect { active -> setLowLatencyWifi(active) } }
        scope.launch { MirrorState.active.collect { active -> if (active) bringMirrorToFront() } }
        scope.launch {
            MirrorState.active.collect { active -> if (!active && qualityRecheckPending) recheckQuality("after session") }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Log.i(TAG, "ReceiverService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_BEACON) {
            scope.launch { updateBeacon() }  // e.g. the "Nearby devices" permission was just granted
        } else if (intent?.action == ACTION_RESTART) {
            // Re-advertise under the new name. Same MAC, so the iPhone keeps its pairing.
            scope.launch {
                Log.i(TAG, "restarting server (name or quality changed)")
                stopServer()
                startServer()
            }
        } else {
            scope.launch { startServer() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ReceiverService destroyed")
        unregisterReceiver(screenReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        // Stop on the control thread (after any pending start), then shut the thread down.
        scope.launch {
            stopServer()
            setLowLatencyWifi(false)
            // Only clear globals that still point to this instance.
            if (MirrorState.surfaceListener === videoDecoder) MirrorState.surfaceListener = null
            if (NativeBridge.videoDecoder === videoDecoder) NativeBridge.videoDecoder = null
            if (NativeBridge.audioPlayer === audioPlayer) NativeBridge.audioPlayer = null
            videoDecoder.shutdown()
            audioPlayer.shutdown()
        }.invokeOnCompletion {
            scope.cancel()
            controlDispatcher.close()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Control thread.
    private fun startServer() {
        if (started) return
        acquireMulticastLock()
        NativeBridge.serviceListener = discovery
        Log.i(TAG, NativeBridge.nativeInfo())
        val name = identity.name
        warnIfNameClashesWithTv(name)
        val q = currentQuality()
        MirrorState.setQuality(q)
        activeOption = q.active
        videoDecoder.setStreamBox(q.active.width, q.active.height)
        val port = NativeBridge.nativeStart(name, identity.mac, q.active.width, q.active.height, q.active.fps)
        if (port > 0) {
            started = true
            serverPort = port
            Log.i(TAG, "AirPlay receiver \"$name\" running on port $port (${q.active.key} @ ${q.active.fps} fps, " +
                "choice ${q.choice}, offered ${q.options.joinToString { "${it.key}@${it.fps}" }})")
            videoDecoder.prewarm()
            updateBeacon()
        } else {
            Log.e(TAG, "native server failed to start: error $port")
            NativeBridge.serviceListener = null
            releaseMulticastLock()
        }
    }

    // Control thread.
    private fun stopServer() {
        if (!started) return
        beacon.stop()
        NativeBridge.nativeStop()  // unregisters both services through Discovery
        discovery.unregisterAll()  // safety net if native skipped an unregister
        NativeBridge.serviceListener = null
        releaseMulticastLock()
        started = false
    }

    /**
     * Android 14's mDNS advertiser requires service names to be unique on the device across all
     * service types, and the system already advertises the TV's own device name (Android TV
     * remote service). A clash makes `_airplay._tcp` fail with only a system-side log
     * (NameConflictException) and no callback to us — so say it loudly here.
     */
    private fun warnIfNameClashesWithTv(name: String) {
        val tvName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: return
        if (tvName.equals(name, ignoreCase = true)) {
            Log.e(TAG, "AirPlay name \"$name\" equals the TV's own device name; the iPhone will " +
                "not see it. Choose a different name.")
        }
    }

    /**
     * Quality options from the TV's display modes and the decoder's limits, and the stored choice
     * resolved against them (an unavailable choice — e.g. 4K after moving to a 1080p TV — falls
     * back to Auto). The decoder lookup is slow the first time; call on the control thread.
     */
    private fun currentQuality(): MirrorState.Quality {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val modes = display?.supportedModes?.map {
            Resolutions.Mode(it.physicalWidth, it.physicalHeight, it.refreshRate)
        }.orEmpty()
        val options = Resolutions.options(modes, VideoDecoder::supports)
        val choice = ReceiverSettings(this).qualityChoice
        return MirrorState.Quality(options, choice, Resolutions.resolve(choice, options))
    }

    /**
     * The TV's modes changed (another TV, different HDMI port, resolution switched). Re-advertise
     * if that changes the size we'd use; never in the middle of a session (done after it ends).
     * Control thread.
     */
    private fun recheckQuality(reason: String) {
        if (!started) return
        if (MirrorState.active.value) {
            qualityRecheckPending = true
            return
        }
        qualityRecheckPending = false
        val q = currentQuality()
        MirrorState.setQuality(q.copy(active = activeOption ?: q.active))
        if (q.active != activeOption) {
            Log.i(TAG, "display changed ($reason): ${activeOption?.key} → ${q.active.key}, restarting server")
            stopServer()
            startServer()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) scope.launch { recheckQuality("display modes changed") }
        }
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    /** Control thread. Advertises the current Wi-Fi IPv4 address and server port over BLE. */
    private fun updateBeacon() {
        if (!started) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val ipv4 = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<java.net.Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
        if (ipv4 == null) {
            beacon.stop()
            return
        }
        beacon.start(ipv4, serverPort)
    }

    /**
     * Any thread. Coalesces bursts of events (a wake-up often brings several network callbacks)
     * into one re-announcement a few seconds later, once the network has settled.
     */
    private fun scheduleReannounce(reason: String) {
        scope.launch {
            reannounceJob?.cancel()
            reannounceJob = scope.launch {
                delay(REANNOUNCE_SETTLE_MS)
                if (started) discovery.reannounce(reason)
            }
        }
    }

    /** mDNS needs multicast; Wi-Fi drivers drop it to save power unless a lock is held. */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi.createMulticastLock("AirPlayRx-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Shows the mirror screen over whatever app is in front. Starting an activity from the
     * background is only allowed because the owner granted SYSTEM_ALERT_WINDOW over ADB
     * (Milestone 7); without it Android silently blocks the launch, and mirroring continues
     * audio-only into the parking surface until the owner opens the app.
     */
    private fun bringMirrorToFront() {
        if (MirrorState.uiVisible) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "cannot bring mirror screen to front: SYSTEM_ALERT_WINDOW not granted " +
                "(adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow)")
        }
        wakeDevice()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FOR_SESSION, true)
        try {
            startActivity(intent)
            Log.i(TAG, "bringing mirror screen to front")
        } catch (e: Exception) {
            Log.e(TAG, "could not start MainActivity for the session", e)
        }
    }

    /**
     * Wakes the Chromecast if it is in standby (e.g. the TV was switched to another input and
     * the Chromecast went to sleep). Waking makes Android TV send HDMI-CEC One Touch Play, so the
     * TV switches back to the Chromecast. MainActivity.setTurnScreenOn() does the same; this
     * short wake lock covers the moment before the activity exists. A no-op when already awake.
     */
    @Suppress("DEPRECATION") // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP is still the way to wake from a service
    private fun wakeDevice() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        Log.i(TAG, "device asleep → waking it for the session (TV should switch input via CEC)")
        power.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "AirPlayRx:session-wake"
        ).acquire(3_000)
    }

    // Control thread.
    private fun setLowLatencyWifi(enabled: Boolean) {
        if (enabled && lowLatencyLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AirPlayRx-session")
            lock.setReferenceCounted(false)
            try {
                lock.acquire()
            } catch (e: SecurityException) {
                // An optimisation only; mirroring works without it.
                Log.w(TAG, "Wi-Fi low-latency lock refused: ${e.message}")
                return
            }
            lowLatencyLock = lock
            Log.i(TAG, "Wi-Fi low-latency lock acquired")
        } else if (!enabled && lowLatencyLock != null) {
            lowLatencyLock?.let { if (it.isHeld) it.release() }
            lowLatencyLock = null
            Log.i(TAG, "Wi-Fi low-latency lock released")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_RESTART = "dev.personal.airplayrx.RESTART"
        private const val REANNOUNCE_SETTLE_MS = 3_000L
        private const val LONG_STANDBY_MS = 5 * 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        private const val ACTION_REFRESH_BEACON = "dev.personal.airplayrx.REFRESH_BEACON"

        /** Re-evaluates the BLE beacon, e.g. after the Bluetooth permission was granted. */
        fun refreshBeacon(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ACTION_REFRESH_BEACON))
        }

        /** Stops and restarts the server, e.g. after a rename or a quality change. */
        fun restart(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ACTION_RESTART))
        }
    }
}

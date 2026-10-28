package dev.personal.airplayrx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Single tag for all app logging, Kotlin and native: `adb logcat -s AirPlayRx`. */
const val TAG = "AirPlayRx"

/**
 * Full-screen idle screen, and the SurfaceView the mirrored video is drawn on.
 * All methods run on the main thread. The activity never touches the decoder: it publishes its
 * surface through [MirrorState] and reacts to [MirrorState.active] / [MirrorState.videoSize].
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var videoSurface: SurfaceView
    private lateinit var idleScreen: View
    private lateinit var statsOverlay: StatsOverlay

    /** True if a mirroring session opened this screen; then it closes when the session ends. */
    private var openedForSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If a session starts while the Chromecast is in standby, wake it. Android TV then sends
        // HDMI-CEC "One Touch Play", so the TV switches to this input by itself.
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        setContentView(R.layout.activity_main)
        videoSurface = findViewById(R.id.video_surface)
        idleScreen = findViewById(R.id.idle_screen)
        videoSurface.holder.addCallback(this)
        statsOverlay = StatsOverlay(this, findViewById(R.id.stats_overlay), uiScope)
        openedForSession = intent.getBooleanExtra(EXTRA_FOR_SESSION, false)

        val name = DeviceIdentity(this).name
        showName(name)
        findViewById<View>(R.id.rename_button).setOnClickListener { showRenameDialog() }
        setUpQualityButton()

        uiScope.launch { MirrorState.active.collect(::showSession) }
        uiScope.launch { MirrorState.videoSize.collect(::fitVideo) }

        Log.i(TAG, "MainActivity created, idle screen shown for \"$name\"")
        ReceiverService.start(this)
        requestBeaconPermission()
    }

    /**
     * The BLE AirPlay beacon (faster discovery on iPhones) needs "Nearby devices". Asked once on
     * the idle screen; never while a session is starting, so the dialog can't cover the mirror.
     */
    private fun requestBeaconPermission() {
        if (openedForSession) return
        if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE), REQUEST_BLUETOOTH)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "\"Nearby devices\" permission ${if (granted) "granted" else "denied"}")
            if (granted) ReceiverService.refreshBeacon(this)
        }
    }

    // singleTask: a session start while we are in the background lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FOR_SESSION, false)) openedForSession = true
    }

    override fun onStart() {
        super.onStart()
        MirrorState.uiVisible = true
    }

    override fun onStop() {
        MirrorState.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-apply on focus because the system can restore bars after dialogs or overlays.
        if (hasFocus) hideSystemBars()
    }

    // ---- SurfaceHolder.Callback ---------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Match the display to the iPhone's 60 fps so frames are not judder-paced.
        holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        MirrorState.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MirrorState.setSurface(null)  // returns only after the decoder has switched away
    }

    // ---- UI state -----------------------------------------------------------------------------

    /** While mirroring, OK (or Info/Menu) on the remote toggles the stats overlay. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (MirrorState.active.value && keyCode in OVERLAY_KEYS) {
            statsOverlay.toggle()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showSession(active: Boolean) {
        idleScreen.visibility = if (active) View.GONE else View.VISIBLE
        statsOverlay.update(active)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (openedForSession) {
                // Hand the TV back to whatever was showing before (e.g. YouTube): finishing
                // this task's only activity reveals the task underneath.
                Log.i(TAG, "session ended → returning to the previous app")
                openedForSession = false
                finish()
            }
        }
    }

    private fun showName(name: String) {
        findViewById<TextView>(R.id.device_name).text = name
        findViewById<TextView>(R.id.idle_hint).text = getString(R.string.idle_hint, name)
    }

    /**
     * Each press moves to the next choice: Auto, then every size this TV + decoder support (from
     * ReceiverService via MirrorState). Changing it restarts the server with the new display box.
     */
    private fun setUpQualityButton() {
        val settings = ReceiverSettings(this)
        val button = findViewById<TextView>(R.id.quality_button)
        uiScope.launch {
            MirrorState.quality.collect { q -> button.text = getString(R.string.quality_button, qualityLabel(q)) }
        }
        button.setOnClickListener {
            val q = MirrorState.quality.value ?: return@setOnClickListener
            val choices = listOf(ReceiverSettings.AUTO) + q.options.map { it.key }
            val next = choices[(choices.indexOf(q.choice).coerceAtLeast(0) + 1) % choices.size]
            settings.qualityChoice = next
            val active = Resolutions.resolve(next, q.options)
            MirrorState.setQuality(q.copy(choice = next, active = active))
            Log.i(TAG, "quality set to $next (${active.key} @ ${active.fps} fps)")
            ReceiverService.restart(this)
        }
    }

    private fun qualityLabel(q: MirrorState.Quality?): String = when {
        q == null -> "…"
        q.choice == ReceiverSettings.AUTO -> "Auto (${q.active.label})"
        else -> q.active.label
    }

    /** Text box for the name; stays open with an error message until the name is valid. */
    private fun showRenameDialog() {
        val identity = DeviceIdentity(this)
        val input = EditText(this).apply {
            setText(identity.name)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.rename_save, null)  // listener set below: don't auto-close
            .setNegativeButton(R.string.rename_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val tvName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                when (val result = DeviceName.validate(input.text.toString(), tvName)) {
                    is DeviceName.Result.Ok -> {
                        if (result.name != identity.name) {
                            identity.name = result.name
                            showName(result.name)
                            Log.i(TAG, "renamed to \"${result.name}\"")
                            ReceiverService.restart(this)
                        }
                        dialog.dismiss()
                    }
                    is DeviceName.Result.Invalid -> input.error = getString(
                        when (result.reason) {
                            DeviceName.Reason.EMPTY -> R.string.rename_error_empty
                            DeviceName.Reason.TOO_LONG -> R.string.rename_error_too_long
                            DeviceName.Reason.BAD_CHARACTER -> R.string.rename_error_character
                            DeviceName.Reason.SAME_AS_TV -> R.string.rename_error_same_as_tv
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    /** Largest rectangle with the video's aspect ratio that fits the screen, centred. */
    private fun fitVideo(size: Size?) {
        val parent = videoSurface.parent as View
        val pw = parent.width
        val ph = parent.height
        val params = videoSurface.layoutParams as FrameLayout.LayoutParams
        if (size == null || pw == 0 || ph == 0) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            if (size != null) parent.post { fitVideo(MirrorState.videoSize.value) }  // not laid out yet
        } else {
            val scale = minOf(pw.toFloat() / size.width, ph.toFloat() / size.height)
            params.width = (size.width * scale).toInt()
            params.height = (size.height * scale).toInt()
        }
        params.gravity = Gravity.CENTER
        videoSurface.layoutParams = params
    }

    companion object {
        private const val REQUEST_BLUETOOTH = 1

        /** Intent extra: this launch was triggered by a mirroring session. */
        const val EXTRA_FOR_SESSION = "for_session"

        private val OVERLAY_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU,
        )
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

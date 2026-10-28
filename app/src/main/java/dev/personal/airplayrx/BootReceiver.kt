package dev.personal.airplayrx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [ReceiverService] when the Chromecast boots or the app is updated, so the TV is an
 * AirPlay target without opening the app. Main thread. Starting a foreground service from
 * BOOT_COMPLETED is allowed on Android 14 for targetSdk 34 (Android 15 restricts mediaPlayback
 * services here, which is why targetSdk stays at 34).
 *
 * Android only delivers this after the app has been opened at least once since installation.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Log.i(TAG, "boot completed → starting ReceiverService")
            // An app update kills the process; restart the receiver without opening the screen
            // (opening it would wake the Chromecast and switch the TV's input).
            Intent.ACTION_MY_PACKAGE_REPLACED -> Log.i(TAG, "app updated → starting ReceiverService")
            else -> return
        }
        ReceiverService.start(context)
    }
}

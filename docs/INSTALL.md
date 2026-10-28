# Installing on your TV

Tested on a **Chromecast with Google TV** (Android TV 14). Other Android TV / Google TV devices
with Android 12 or newer (API 31+) and an ARM CPU should work.

You need a computer with **ADB** (Android platform-tools:
[download](https://developer.android.com/tools/releases/platform-tools)), on the **same Wi-Fi**
as the TV.

## 1. Enable developer options on the TV
1. **Settings → System → About** → select **Android TV OS build** 7 times
   ("You are now a developer").
2. **Settings → System → Developer options** → turn on **USB debugging** and, if present,
   **Wireless debugging**.

## 2. Connect ADB over Wi-Fi
Newer devices (Android 11+, e.g. Chromecast with Google TV) use **Wireless debugging**:
1. Developer options → **Wireless debugging** → **Pair device with pairing code**. Then on the
   computer (values from the TV screen):
   ```bash
   adb pair <TV-IP>:<pairing-port> <6-digit-code>
   ```
2. Connect with the port shown on the main Wireless debugging screen:
   ```bash
   adb connect <TV-IP>:<port>
   adb devices          # the TV should be listed as "device"
   ```
Older devices: `adb connect <TV-IP>:5555` and accept the prompt on the TV.

Wireless debugging switches itself off when the TV restarts; turn it on again when you need ADB
(pairing is remembered). The app itself does **not** need ADB to keep working.

## 3. Install the app
```bash
adb install -r airplay-receiver-X.Y.Z.apk
```
Then open **AirPlay Receiver** once from the TV's Apps row (Android only starts apps on boot after
they were opened once). When asked to allow **"Nearby devices"**, choose **Allow** — it is used
only to broadcast a Bluetooth LE AirPlay beacon so iPhones find the TV faster.

## 4. Recommended: let mirroring take over the screen
So that mirroring appears even while another app (e.g. YouTube) is open, allow the app to "display
over other apps". Android TV has no settings screen for this, so grant it once over ADB:
```bash
adb shell appops set dev.personal.airplayrx SYSTEM_ALERT_WINDOW allow
```
Undo: `adb shell appops set dev.personal.airplayrx SYSTEM_ALERT_WINDOW default`.

## 5. Optional: switch the TV's HDMI input automatically
If you watch another HDMI source (satellite box, console), the app can make the TV switch to the
Android TV device when you start mirroring. This uses HDMI-CEC and needs the device to go to
standby when the TV switches away (the Android default; some devices have it turned off):
```bash
adb shell cmd hdmi_control cec_setting get power_state_change_on_active_source_lost
# if it says "none":
adb shell cmd hdmi_control cec_setting set power_state_change_on_active_source_lost standby_now
```
HDMI-CEC must be enabled on the TV (brand names: Anynet+, Bravia Sync, SimpLink, …). Note: it takes
the device ~40 s to fall asleep after you switch away; mirroring within that time won't switch.

## 6. Use it
- iPhone/iPad: **Control Center → Screen Mirroring → "<TV name> AirPlay"**.
  Mac: Control Center → Screen Mirroring.
- On the TV's idle screen: **Rename** the receiver, change **Quality** (Auto recommended).
- While mirroring: press **OK** on the remote to show/hide the stats overlay.

## Troubleshooting
| Problem | Try |
|---|---|
| Not listed on the iPhone | Same Wi-Fi (not a guest network)? Router "client/AP isolation" off? Open the app once. Wait ~10 s. |
| Listed, but connecting fails | Restart the app (or the TV). Check that no other AirPlay receiver uses the same name. |
| Name rejected as "the TV's own name" | Android doesn't allow the same network name twice — pick another (the default adds " AirPlay"). |
| Black picture from Netflix / Disney+ / Apple TV+ | Expected: DRM-protected content can't be mirrored. |
| Mirroring doesn't come to the front over other apps | Do step 4. |
| TV doesn't switch input | Do step 5; enable HDMI-CEC on the TV; wait ~40 s after switching away. |

Logs for bug reports: `adb logcat -d -s AirPlayRx:V AndroidRuntime:E > airplay.log`
(they contain no keys or passwords, but do include device names on your network).

## Uninstall
`adb uninstall dev.personal.airplayrx`

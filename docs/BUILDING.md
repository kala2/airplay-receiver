# Building from source

Works on **macOS** (Intel or Apple Silicon) and **Linux** (x86-64). On Windows use **WSL2**
(Ubuntu). No Android Studio needed; everything runs from a terminal.

## 1. Prerequisites
| Tool | Notes |
|---|---|
| **JDK 17** | e.g. [Eclipse Temurin 17](https://adoptium.net); `java -version` must say 17. |
| **Android SDK command-line tools** | [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only) → "Command line tools only". Unzip to `$ANDROID_HOME/cmdline-tools/latest/` (default `ANDROID_HOME=~/Android/Sdk`). |
| `make`, `perl`, `curl`, `tar`, `clang` | macOS: `xcode-select --install`. Debian/Ubuntu: `sudo apt install build-essential perl curl clang bzip2`. |
| `adb` | Installed by the next step (platform-tools). |

Shell setup (e.g. in `~/.zshrc` or `~/.bashrc`):
```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

## 2. Install the pinned SDK packages
```bash
scripts/setup-sdk.sh
```
Checks the prerequisites and installs exactly the NDK, CMake, platform and build-tools versions
the project uses (read from `app/build.gradle.kts`). Re-run any time.

## 3. Dependencies (once)
```bash
scripts/fetch-deps.sh        # UxPlay, libplist, OpenSSL release archives → third_party/ (SHA-256 checked)
scripts/build-openssl.sh     # static libcrypto per CPU architecture → third_party/openssl-android/
```
`third_party/` is not committed; these scripts recreate it reproducibly. Both skip work that is
already done.

**CPU architectures:** builds include 32- and 64-bit ARM by default. To build only what your TV
needs (faster), check `adb shell getprop ro.product.cpu.abilist` and put e.g.
`airplayrx.abis=armeabi-v7a` in `~/.gradle/gradle.properties` (applies to Gradle and
`build-openssl.sh`).

## 4. Build, test, install
```bash
scripts/test-native.sh       # native unit tests (ASan + UBSan) on the host
./gradlew testDebugUnitTest  # Kotlin unit tests
./gradlew lintDebug          # Android Lint
./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
scripts/deploy.sh debug      # build + install on the connected TV + launch + follow logs
```
`deploy.sh` options: `--no-logs`, `--no-launch` (install only; the receiver restarts itself
without opening its screen). Choose a device with `ADB_SERIAL=<serial>`.

Connect the TV first as described in [INSTALL.md](INSTALL.md#2-connect-adb-over-wi-fi).

## 5. Release builds (optional)
Release builds are signed with **your own** key, kept outside the repository:
```bash
mkdir -p ~/airplayrx-keys && chmod 700 ~/airplayrx-keys
keytool -genkeypair -keystore ~/airplayrx-keys/airplayrx-release.jks -alias airplayrx \
        -keyalg RSA -keysize 4096 -validity 10000
```
and `~/airplayrx-keys/signing.properties` (mode 600):
```properties
storeFile=/absolute/path/to/airplayrx-keys/airplayrx-release.jks
storePassword=...
keyAlias=airplayrx
keyPassword=...
```
Then `scripts/deploy.sh release`. Without that file, `./gradlew assembleRelease` produces an
unsigned APK. Note: Android can't update an app over one signed with a different key — uninstall
first when switching between debug, your release and official release builds.

## Useful commands
```bash
adb logcat -s AirPlayRx:V AndroidRuntime:E           # app logs
scripts/soak-stats.sh                                # memory / threads of the running app
adb logcat -d | $ANDROID_HOME/ndk/<ndk>/ndk-stack -sym app/build/intermediates/cxx/Debug/<hash>/obj/<abi>
```

## Layout
```
app/src/main/cpp/        native: JNI bridge, UxPlay server glue, dns_sd shim, media sinks
app/src/main/java/...    Kotlin: service, decoders, discovery, UI
app/src/test/            JUnit + native host tests
scripts/                 setup, dependencies, build, deploy, CI helpers, git hook
docs/                    install/build guides, decisions, review, development log
```

# AirPlay Receiver for Android TV / Google TV

Turn a **Chromecast with Google TV** (or another Android TV / Google TV device) into an **AirPlay
screen-mirroring receiver** for iPhone, iPad and Mac — using the phone's built-in
Control Center → Screen Mirroring. No app on the iPhone.

```
iPhone / Mac (built-in AirPlay) ──Wi-Fi──> Android TV device running this app ──HDMI──> TV
```

Built on the open-source [UxPlay](https://github.com/FDH2/UxPlay) AirPlay server (C), ported to
Android with the NDK; video and audio use Android's own hardware decoders.

## Features
- **Screen mirroring** with hardware H.264 decoding (~10 ms decode time on a Chromecast), up to
  60 fps, rotation, and **audio** (AAC-ELD) with iPhone volume control.
- **Shows up fast** in Screen Mirroring: mDNS/Bonjour plus a Bluetooth LE AirPlay beacon.
- **Feels built-in**: starts on boot, takes over the screen when you start mirroring (even from
  another app), hands it back when you stop, and can switch the TV's HDMI input for you (CEC).
- **Automatic quality**: offers the resolutions your TV and decoder support (720p … 2160p); Auto
  picks the sharpest smooth (≥50 fps) one.
- Rename the receiver and change quality from the TV remote; stats overlay (press OK while
  mirroring).

**Not supported:** protected content (Netflix, Disney+, Apple TV+ video shows black — that's DRM),
audio-only AirPlay (music), AirPlay passwords/PINs, H.265.

## Quick start — install a release
1. Download the latest `airplay-receiver-X.Y.Z.apk` from
   [Releases](../../releases) (check it against `SHA256SUMS`).
2. Install it on your TV over ADB and open it once — step-by-step guide:
   **[docs/INSTALL.md](docs/INSTALL.md)**.
3. On your iPhone: Control Center → Screen Mirroring → **"<your TV's name> AirPlay"**.

## Build from source
See **[docs/BUILDING.md](docs/BUILDING.md)** (macOS or Linux; JDK 17, Android SDK command-line
tools — no Android Studio needed). In short:

```bash
scripts/setup-sdk.sh                      # checks tools, installs the pinned NDK/CMake/SDK
scripts/fetch-deps.sh                     # pinned UxPlay / libplist / OpenSSL (SHA-256 checked)
scripts/build-openssl.sh                  # static libcrypto per CPU architecture (cached)
./gradlew assembleDebug                   # → app/build/outputs/apk/debug/app-debug.apk
scripts/deploy.sh debug                   # build, install on the connected TV, show logs
```

## Security & privacy
- Works on your **local network only**; nothing is sent to the internet.
- There is **no PIN/password**: anyone on your Wi-Fi can mirror to the TV while the app runs.
  Keep guests on a guest network if that matters to you.
- Known risks and how to report vulnerabilities: [SECURITY.md](SECURITY.md).

## Project documents
| File | What |
|---|---|
| [docs/INSTALL.md](docs/INSTALL.md) | Install and set up on a TV |
| [docs/BUILDING.md](docs/BUILDING.md) | Build from source, run tests, deploy |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Commit conventions, workflow, releases |
| [SECURITY.md](SECURITY.md) | Security model, known risks, reporting |

## Versioning & releases
[Conventional Commits](https://www.conventionalcommits.org) + **semantic-release**: every push to
`main` with a `fix:`/`feat:`/breaking commit is released automatically: tag `vX.Y.Z` with release
notes and the signed APK on [Releases](../../releases) (the changelog). Nothing is committed back.
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Legal
- License: **GPL-3.0** ([LICENSE](LICENSE)), because UxPlay is GPL-3.0. Bundled third-party
  code: UxPlay (GPL-3.0), llhttp (MIT), libplist (LGPL-2.1), OpenSSL (Apache-2.0).
- "AirPlay", "Apple TV", "iPhone" and "Mac" are trademarks of Apple Inc. This project is **not
  affiliated with, endorsed or certified by Apple**. It is an independent, non-commercial
  open-source project based on UxPlay's reverse-engineered protocol implementation; it is not
  distributed through app stores. Use it at your own risk.

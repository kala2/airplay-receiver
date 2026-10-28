# Security policy

## Reporting a vulnerability
Please **do not open a public issue**. Use GitHub's private reporting instead:
**Security → Report a vulnerability** on this repository. Include the affected version, device,
and steps to reproduce. You'll get an answer as soon as possible; this is a volunteer project.

Vulnerabilities in the AirPlay protocol handling itself usually come from
[UxPlay](https://github.com/FDH2/UxPlay) — please report those upstream as well
(UxPlay publishes security advisories).

## Scope and threat model
- The receiver listens on the **local network** only and has **no PIN/password**: any device on
  the same network can start mirroring. That is a known, accepted property, not a vulnerability.
- In scope: memory-safety bugs reachable from the network (RTSP/HTTP, plist, FairPlay, mirror and
  audio packet handling), crashes/hangs triggered remotely, leaks of keys or secrets, issues in the
  release pipeline (signing).

## Known risks (by design of AirPlay / UxPlay)
- **No access control:** any device on the LAN can mirror to the TV, or keep it busy. Keep guests
  and untrusted IoT devices on a separate network if that matters.
- **Plaintext control channel:** AirPlay's RTSP/HTTP requests are not encrypted; the mirrored
  video (AES-CTR) and audio (AES-CBC) are.
- **Unauthenticated parsing:** network data (HTTP, plists, FairPlay setup, packet framing) is parsed
  by UxPlay inside the app before any authentication; video/audio bitstreams are decoded by
  Android's sandboxed media processes. Keep the app (UxPlay) and the TV's security updates current.
- Not affected by Apple's 2025 "AirBorne" AirPlay CVEs (those concern Apple's own AirPlay stack/SDK).

## Dependencies
UxPlay, libplist and OpenSSL (3.5 LTS, libcrypto only) are pinned by version and SHA-256 in
`scripts/fetch-deps.sh` and updated when upstream publishes fixes; GitHub Actions and Gradle plugins
are kept current by Dependabot.

## Supported versions
Only the latest release receives fixes.

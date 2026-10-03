# Aether Companion VPN for Android

Aether Companion is a one-tap Android VPN front end for
[CluvexStudio/Aether](https://github.com/CluvexStudio/Aether). It starts Aether in
Termux, waits for Aether's local SOCKS5 listener, verifies that the proxy can
reach the internet, and then routes Android traffic through it with Android's
`VpnService` and `hev-socks5-tunnel`.

No V2Ray app or manual SOCKS5 configuration is required during normal use.

> This is a beta. The build and automated tests pass, but the complete VPN path
> still needs testing on representative physical Android devices before it can
> be described as production-ready or leak-proof.

## What is implemented

- Android `VpnService` with a foreground-service notification and Android's VPN
  permission flow.
- Full IPv4 (`0.0.0.0/0`) and IPv6 (`::/0`) routes into the TUN interface.
- IPv4 and IPv6 DNS servers whose traffic follows the same captured routes.
- `hev-socks5-tunnel` 2.18.0 for TCP and UDP forwarding into Aether's SOCKS5
  listener at `127.0.0.1:1819` (or the selected local port).
- Aether and the native bridge are excluded from the VPN path to prevent routing
  loops. Android performs this exclusion by application UID, so all Termux
  traffic—not only Aether—is outside the VPN.
- A fixed, allow-listed command interface for MASQUE, WireGuard, gool and MIM,
  plus scan, IP-family, obfuscation, carrier, fragmentation, reconnect and port
  settings supported by Aether's CLI.
- Readiness and end-to-end HTTPS checks through SOCKS5 before the UI reports
  **Connected**, followed by ongoing health checks.
- Exit IP, connection duration, status, logs and actionable error messages.
- Clean disconnect of the TUN interface, tun2socks, and only the Aether process
  whose PID this app recorded. Other Termux processes are not signalled.
- First-run requirement checklist and in-app Termux download through Android's
  `DownloadManager` and standard `PackageInstaller` confirmation UI.
- Verification of the exact F-Droid Termux APK SHA-256 and signing certificate,
  plus validation of already-installed official F-Droid/GitHub Termux signatures.
- Automatic installation of missing Termux packages and Aether after Termux
  authorization. The official Aether installer is commit-pinned, hash-checked,
  and then verifies the selected Aether release archive's checksum itself.

## First-time setup

Requirement: Android 10 or newer.

1. Tap **Download Termux** if the checklist reports that it is missing. The app
   downloads the requested F-Droid build, verifies it, and opens Android's normal
   installer. Silent installation is neither attempted nor possible.
2. Allow Aether Companion's Termux command permission.
3. Use **Copy one-time authorization**, open Termux once, paste the command and
   return. This enables Termux's mandatory `allow-external-apps=true` setting.
4. Tap **Connect**. If Aether is missing, the companion installs its dependencies
   and the verified official Aether release automatically, then continues into
   the VPN permission and connection flow.

After setup, day-to-day use is: open Aether Companion, tap **Connect**, and wait
for the verified **Connected** state.

Some Android vendors aggressively suspend Termux. If connections die in the
background, remove battery restrictions for both Termux and Aether Companion.

## Connection sequence

1. Verify Termux's signing certificate, command permission and Aether executable.
2. Install Aether automatically when it is missing, while preserving an existing
   executable and configuration.
3. Launch Aether with non-interactive CLI arguments through Termux's documented
   `RUN_COMMAND` service.
4. Wait for the SOCKS5 listener and perform an HTTPS request through it.
5. Establish the Android TUN interface and start `hev-socks5-tunnel`.
6. Report **Connected** only when all stages are running.

Disconnect reverses those steps and validates the recorded PID's command line
before sending it a termination signal.

## Security boundaries and limitations

- The current release is not yet verified with physical-device packet captures,
  captive portals, network changes, OEM background restrictions, or every Aether
  protocol. It therefore does **not** claim verified DNS-leak protection.
- Android's per-application VPN exclusion is used for `com.termux` and this app.
  Consequently, unrelated Termux traffic also bypasses the tunnel.
- If Aether or the SOCKS/HTTPS health check fails repeatedly, the app tears down
  the VPN instead of leaving a false **Connected** state. This is not Android's
  always-on lockdown mode.
- Android's application sandbox prevents Aether Companion from editing Termux's
  private properties before Termux authorizes external commands. One copy/paste
  authorization inside Termux remains required; the app cannot safely remove or
  silently bypass that step. Aether installation itself is automatic afterwards.
- This is not yet a Termux-free architecture. Aether's Android release is a
  Termux executable, not an Android library. Bundling it directly would require
  an upstream library/JNI boundary and device testing of socket protection and
  lifecycle handling; executing a copied binary is not a reliable substitute.
- The beta APK published on GitHub is debug-signed. Android will not treat a
  later production-signed APK as an in-place update to that debug build.

## Native dependency

The first-run downloader is pinned to F-Droid `com.termux_1022.apk` with
SHA-256 `fdd476982cd74f2f00aac12d3683b1fa260a0b2d146411b94e09d773be3a7b56`
and F-Droid signing-certificate digest
`228fb2cfe90831c1499ec3ccaf61e96e8e1ce70766b9474672ce427334d41c42`.
The Aether installer is pinned to commit
`ff74eebb77f9fde126d7dd6bc524874e7be7bed9` with SHA-256
`337f45594fa33f20a0c83c8acd4551587dcae32c9e1531c551335063cf3974ad`.

The bundled AAR is the official `hev-socks5-tunnel` 2.18.0 release under the MIT
license. Its SHA-256 is:

```text
15ec8ed121663b562c99caa5bb602d1009f24e5b09e733438b81988f12feaaab
```

The license text is included at `app/src/main/assets/third_party_licenses.txt`.

## Build and test

Requirements: JDK 17, Android SDK Platform 35 and Build Tools 35.0.0.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions
runs the same tests, lint checks and build on each push.

## Project details

- Minimum Android version: Android 10 (API 29), required by the official native
  tunnel AAR.
- Application ID: `app.aether.companion`
- Native ABIs: arm64-v8a, armeabi-v7a, x86 and x86_64
- No analytics and no hardcoded VPN credentials

## Primary documentation

- [Android VPN developer guide](https://developer.android.com/develop/connectivity/vpn)
- [Android VpnService.Builder reference](https://developer.android.com/reference/android/net/VpnService.Builder)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [Aether README](https://github.com/CluvexStudio/Aether)
- [Termux RUN_COMMAND contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

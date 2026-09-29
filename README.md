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

## First-time setup

Requirements: Android 10 or newer, a current Termux build, and Aether installed
inside Termux.

1. Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or
   the [official Termux releases](https://github.com/termux/termux-app/releases).
   Do not use the obsolete Play Store build.
2. Open Aether Companion and use **Copy setup command**.
3. Open Termux once, paste the command, and let Aether's official installer
   finish. The setup also enables Termux's `allow-external-apps=true` option.
4. Return to Aether Companion and allow Termux command access and Android VPN
   access when prompted.
5. Select a protocol and tap **Connect**.

After setup, day-to-day use is: open Aether Companion, tap **Connect**, and wait
for the verified **Connected** state.

Some Android vendors aggressively suspend Termux. If connections die in the
background, remove battery restrictions for both Termux and Aether Companion.

## Connection sequence

1. Verify Termux, its command permission, and the Aether executable.
2. Launch Aether with non-interactive CLI arguments through Termux's documented
   `RUN_COMMAND` service.
3. Wait for the SOCKS5 listener and perform an HTTPS request through it.
4. Establish the Android TUN interface and start `hev-socks5-tunnel`.
5. Report **Connected** only when all stages are running.

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
- Aether still requires its one-time interactive installation and any upstream
  configuration/identity it normally needs. The companion does not embed
  credentials or secrets.
- The beta APK published on GitHub is debug-signed. Android will not treat a
  later production-signed APK as an in-place update to that debug build.

## Native dependency

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

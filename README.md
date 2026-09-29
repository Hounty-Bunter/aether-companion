# Aether Companion for Android

A small Android controller for [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) running inside Termux. It is designed for a nontechnical family member: choose a route, tap **Start**, and read one clear status.

## What it does

- Starts Aether through Termux's documented `RUN_COMMAND` service.
- Passes non-interactive Aether v2.1.0 flags for protocol, scan mode, IP family, obfuscation, MASQUE carrier, TLS fragmentation, quick reconnect, and local port.
- Keeps the Aether identity files in Termux, where the official installer puts the executable and Aether writes its persistent configuration.
- Detects readiness by completing a SOCKS5 method negotiation with `127.0.0.1:<port>`.
- Stops only the process whose PID this companion recorded and whose command line still contains `aether`.

## Important limitation

This is **not** an Android `VpnService`. Aether v2.1.0 exposes an unauthenticated local SOCKS5 listener; it does not install an Android network interface or route every app automatically. Only apps that support SOCKS5 and are configured to use `127.0.0.1:1819` (or the selected port) will use Aether.

A real device-wide mode would require a separately implemented and tested `VpnService` plus a packet-to-SOCKS bridge such as tun2socks, including protecting Aether's own outbound sockets from the VPN to avoid a routing loop. This project deliberately does not claim that capability.

## First run on the phone

1. Install a current Termux build from [F-Droid](https://f-droid.org/packages/com.termux/) or the [official Termux releases](https://github.com/termux/termux-app/releases). The obsolete Play Store build is not suitable.
2. Open Aether Companion and tap **Copy setup command**.
3. Tap **Open Termux**, paste, and run the command. It:
   - enables Termux's required `allow-external-apps=true` setting;
   - downloads Aether's official installer;
   - lets the installer select and verify the correct Android binary.
4. Return to Aether Companion and tap **Allow Termux control**. Android should show the custom **Run commands in Termux environment** permission.
5. Leave the recommended settings selected and tap **Start**.

If a phone vendor aggressively stops Termux, remove battery restrictions for Termux. Aether also needs Termux to remain installed. The app never receives Aether's config files, private keys, or terminal output.

## How commands are passed

The companion invokes Termux's Bash by absolute path and passes Aether as positional arguments—there is no concatenation of UI text into a shell command. All selectable values come from fixed allow-lists. The effective command is equivalent to:

```text
aether --masque --scan balanced -4 --noize firewall \
  --bind 127.0.0.1:1819 --quick-reconnect --h3
```

For MASQUE, the friendly camouflage choices map to Aether's `firewall`, `gfw`, and `off` profiles. For WireGuard/gool they map to `balanced`, `aggressive`, `light`, and `off`.

## Build

Requirements:

- JDK 17 or newer
- Android SDK Platform 35 and Build Tools

Open the folder in Android Studio and build the `app` module, or run:

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Project notes

- Minimum Android version: 8.0 (API 26)
- Application ID: `app.aether.companion`
- No third-party Android libraries or analytics
- Network permission is used only to probe the local SOCKS5 listener
- Termux package visibility and `com.termux.permission.RUN_COMMAND` are declared in the manifest

## Sources checked

- [Aether README and Android installer](https://github.com/CluvexStudio/Aether/blob/main/README.md)
- [Aether complete guide and v2.1.0 flags](https://github.com/CluvexStudio/Aether/blob/main/Docs/GUIDE.en.md)
- [Termux RUN_COMMAND intent contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

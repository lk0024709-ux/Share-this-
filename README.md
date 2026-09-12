# ShareThis — High-Throughput Offline P2P File Transfer Engine

100% offline, peer-to-peer file transfer for Android. No internet, no cloud,
no accounts, no Google Play Services. Two phones link over a local Wi-Fi
hotspot and files fly over a tuned TCP socket — up to **80+ MB/s** on 5 GHz
hardware, with graceful 2.4 GHz fallback.

Supports **Android 5.0 (API 21) → Android 15+ (API 35)** from one codebase.

---

## Features

| Area | What you get |
|---|---|
| Pairing | 6-digit PIN (UDP broadcast), QR code (CameraX + ZXing, offline), Bluetooth handshake (auto radio kill) |
| Throughput | `tcpNoDelay`, 512 KiB socket buffers, adaptive 64→256 KiB copy buffer, single pipelined socket, Wi-Fi high-perf lock |
| Integrity | Streaming CRC32 trailing-checksum frame per file (zero extra I/O), corrupt partials auto-deleted |
| Storage | SAF picker for sends; MediaStore `Download/ShareThis` on API 29+, legacy paths on API 21–28 |
| Permissions | Version-bounded manifest (`maxSdkVersion`, `neverForLocation`), runtime requests only for what's needed |
| UI | Live MB/s + ETA + radial gauge, throttled to 200 ms updates |

## Project structure

```
ShareThis/
├── .github/workflows/build-apk.yml      # CI: test → debug+release APK → GitHub Release on tags
├── gradle/wrapper/                      # wrapper (jar bootstrapped automatically in CI)
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts                 # minSdk 21 · targetSdk 35 · Java 17
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/sharethis/app/
│       │   │   ├── core/engine/      FastTransferEngine, FrameProtocol, TransferProgress, JsonCodec
│       │   │   ├── core/network/     NetworkBandManager, WifiLockManager, PinPairingEngine
│       │   │   ├── core/pairing/     BluetoothPairingManager, QrCodePayloadHandler
│       │   │   ├── core/storage/     StorageBridge, ChecksumVerifier
│       │   │   ├── core/permissions/ PermissionManager, SystemSettingsHelper
│       │   │   ├── data/models+enums DevicePeer, FileMetadata, NetworkConfig, TransferState, PairingMode
│       │   │   └── ui/               MainActivity, send/, receive/, components/, viewmodels/
│       │   └── res/                  layouts, drawables, themes, adaptive icon
│       └── test/                        # pure-JVM unit tests (protocol, CRC, QR, PIN, progress)
```

## How a session works

1. **Receiver** taps *Receive* → local-only hotspot starts → screen shows
   hotspot SSID + password, a 6-digit PIN and a QR code.
2. **Sender** taps *Send*, picks files, then pairs one of three ways:
   - **QR**: scan → auto-joins the hotspot → transfer starts.
   - **Bluetooth**: tap the receiver → 200-byte handshake → radio killed
     instantly → auto-joins the hotspot → transfer starts.
   - **PIN**: join the hotspot in system Settings → enter the PIN → UDP
     broadcast discovers the receiver → transfer starts.
3. Bytes stream over one pipelined TCP socket
   (`MANIFEST → (FILE → CHECKSUM)* → END`) with live speed/ETA.
4. Receiver verifies CRC32 per file and lands them in `Download/ShareThis`.

## Permissions — why each one exists

- `INTERNET` — **required by Android for any socket**, including offline LAN.
  The app opens zero internet connections; this is a socket-capability flag.
- `CHANGE_WIFI_STATE` / `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` /
  `CHANGE_NETWORK_STATE` — hotspot start/join (normal, auto-granted).
- `ACCESS_FINE_LOCATION` (≤ API 32) / `NEARBY_WIFI_DEVICES` (33+) — Wi-Fi
  scanning/hotspot APIs require these; never used for location.
- `BLUETOOTH*` — 2-second credential handshake, then the radio is shut down.
- `CAMERA` — QR scanning only. `WAKE_LOCK` — Wi-Fi high-perf lock.
- `READ/WRITE_EXTERNAL_STORAGE` — strictly legacy (≤ 28/32); modern Android
  uses SAF + MediaStore. `WRITE_SETTINGS` — legacy hotspot toggle (API 23–25).
- **Omitted on purpose**: contacts, telephony, background location.

## Build locally

Requirements: **JDK 17** + Android SDK (API 35 platform, build-tools 35).

```bash
# first run only (needs network once): generate the wrapper jar
gradle wrapper --gradle-version 8.9   # or download any Gradle 8.7+ distro

./gradlew testDebugUnitTest           # unit tests
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease             # unsigned unless a keystore is configured
```

Optional local signing via `~/.gradle/gradle.properties`:

```properties
KEYSTORE_PATH=/path/to/sharethis.jks
KEYSTORE_PASSWORD=***
KEY_ALIAS=sharethis
KEY_PASSWORD=***
```

## CI/CD — automated APK releases

`.github/workflows/build-apk.yml` runs on every push/PR and on `v*` tags:

1. JDK 17 + Android SDK 35 setup
2. Bootstrap `gradle-wrapper.jar` if absent (official `services.gradle.org` distro)
3. Unit tests → debug APK → release APK
4. Artifacts uploaded (debug 14d, release 30d, test report 7d)
5. **On `v*` tags**: APKs published to GitHub Releases with generated notes

### Signed release APKs (optional, recommended)

Without secrets the release APK builds **unsigned**. The fastest path is the
one-click generator — it creates the keystore, prints all four secret values,
and copies the Base64 to your clipboard (also saved as `*.base64.txt`):

```bash
# Linux / macOS
./scripts/generate-keystore.sh
```

```powershell
# Windows (PowerShell)
.\scripts\generate-keystore.ps1
```

Both accept `--help` with options for `--alias`, `--storepass`, `--keypass`,
`--dname` and `--output` (`-Alias`, `-StorePass`, … on PowerShell). Then add
four repository secrets (*Settings → Secrets → Actions*):

| Secret | Value (printed by the script) |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | single-line Base64 of the `.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (`upload` by default) |
| `ANDROID_KEY_PASSWORD` | key password |

Then tag and push: `git tag v1.0.0 && git push origin v1.0.0`.

> **Back up the `.jks` offline** (USB drive / password manager) — losing it
> means you can never publish updates under the same app signature again.
> Never commit the `.jks` or `.base64.txt` files; both are git-ignored.

Manual alternative (classic `keytool` invocation):

```bash
keytool -genkeypair -v -keystore sharethis-release.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
# Portable single-line Base64 (works on GNU + macOS):
base64 sharethis-release.jks | tr -d '\n\r'
```

## API-level behavior matrix

| Capability | API 21–25 | API 26–28 | API 29–32 | API 33–35 |
|---|---|---|---|---|
| Hotspot host | legacy AP (best-effort) | local-only hotspot | local-only hotspot | local-only hotspot |
| Hotspot join | WifiConfiguration | WifiConfiguration | WifiNetworkSpecifier | WifiNetworkSpecifier |
| Wi-Fi permission | Fine location | Fine location | Fine location | Nearby devices |
| Bluetooth | BT/BT_ADMIN | BT/BT_ADMIN | SCAN/ADV/CONNECT | SCAN/ADV/CONNECT |
| Receive path | legacy Downloads | legacy Downloads | MediaStore | MediaStore |
| QR scan | CameraX | CameraX | CameraX | CameraX |

## Roadmap

- Foreground-service session (screen-off bulletproofing)
- Multi-file resume after drop + sender-side retry
- Optional WPA3 / 6 GHz advertisement where the framework allows
- F-Droid metadata + reproducible-build verification

## License

MIT — do anything, just keep it offline-friendly.

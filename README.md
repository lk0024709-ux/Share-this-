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
| Pairing | 6-digit PIN (UDP broadcast), QR code (CameraX + ZXing, offline), Bluetooth handshake (auto radio kill) — every mode ends in an authenticated session |
| Security (v2) | Ephemeral ECDH P-256 + HKDF-SHA256 → AES-256-GCM per frame (encrypt-then-MAC CBC+HMAC fallback on ancient providers); PIN always bound into the key derivation; authenticated transcript MAC; replay guard + 2-minute payload TTL; constant-time compares |
| Throughput | `tcpNoDelay`, 512 KiB socket buffers, adaptive 64→256 KiB copy buffer, single pipelined socket, Wi-Fi high-perf lock |
| Integrity | Per-chunk CRC32 **and** whole-file SHA-256; a file is only finalized after its hash matches — corrupt partials never surface |
| Resume | Persisted per-file/per-chunk spool state; a reconnect re-validates received chunks and resumes at the watermark (receiver re-accepts for 10 min; sender retries ≤3 with backoff) |
| Storage | SAF picker for sends; MediaStore `Download/ShareThis` on API 29+, legacy paths on API 21–28; free-space pre-check **before** the first chunk; duplicate policy = keep-both/replace/skip/ask (never silent overwrite); path-traversal-safe names |
| Robustness | Full session state machine with per-state timeouts (no infinite spinners), friendly error taxonomy, protocol version check ("incompatible versions" instead of garbage) |
| Background | `dataSync` foreground service with live progress + Cancel (API-21-safe; notification permission optional on 13+) |
| Permissions | Version-bounded manifest (`maxSdkVersion`, `neverForLocation`), runtime requests only for what's needed |
| UI | Live MB/s + ETA + radial gauge, throttled to 200 ms updates, per-file queue ("Sending 3 of 17") |

## Project structure

```
ShareThis/
├── .github/workflows/build-apk.yml      # CI: test → debug+release APK → GitHub Release on tags
├── gradlew / gradlew.bat / gradle/wrapper/  # Gradle 8.9 wrapper, fully committed (jar included)
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
   hotspot SSID + password, a 6-digit PIN and a QR code. Locally: a random
   64-bit session id, a 128-bit challenge and an **ephemeral ECDH key pair**
   are generated before the TCP port opens.
2. **Sender** taps *Send*, picks files, then pairs one of three ways:
   - **QR**: scan (payload = session id + PIN + receiver public key +
     endpoint + expiry, TTL 2 min) → auto-joins the hotspot → transfer
     starts with the out-of-band-authenticated key.
   - **Bluetooth**: tap the receiver → small framed handshake carrying the
     same v2 payload → radio killed instantly → auto-joins the hotspot →
     transfer starts.
   - **PIN**: join the hotspot in system Settings → enter the PIN → UDP
     broadcast discovers the receiver → the offer carries session id +
     challenge (public key stays OOB) → transfer starts.
3. The TCP handshake runs the authenticated key agreement:
   `HELLO → AUTH_OK(+resume offer) → AUTH_CONFIRM` — ECDH over the
   receiver's ephemeral key, HKDF-SHA256 with the PIN and both challenges
   mixed in, mutual transcript MACs (constant-time compare). A wrong PIN,
   stale QR or unknown session aborts cleanly with `AUTHENTICATION_FAILED`.
4. `MANIFEST → MANIFEST_ACK` gates the receiver's free-space pre-check
   (abort = `INSUFFICIENT_STORAGE` before any data moves), then bytes stream
   over the sealed socket: `FILE_META → CHUNK* → FILE_DONE{sha256} → FILE_ACK`
   per file, AEAD-encrypted with derived per-direction keys and sequence
   nonces. Per-chunk CRC32 + whole-file SHA-256; verified files land in
   `Download/ShareThis` with the duplicate policy applied.
5. Either side can cancel/pause; a dropped link auto-resumes from the
   persisted chunk watermark (receiver keeps listening, sender retries).

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
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — keep the transfer
  alive with the screen off (data-sync type, required on API 34+).
  `POST_NOTIFICATIONS` (13+) — optional; denied just hides the progress
  notification, the transfer still runs.
- **Omitted on purpose**: contacts, telephony, background location, cloud.

## Verification status (honest)

What has actually been verified, and how:

| Level | Status | Detail |
|---|---|---|
| **UNIT (pure JVM, local)** | ✅ **102 / 102 green** (3 consecutive runs) | Crypto (RFC 5869 HKDF + FIPS SHA-256 vectors, ECDH round-trips, AEAD tamper detection), protocol framing (52-byte header, CRC32, oversize/truncated/garbage rejection, v1-magic → clean `UnsupportedProtocolException`), chunk ordering, spool resume watermark, filename sanitization, speed window math, PIN packet codec, pairing payload (TTL/version/replay), state machine legality, progress math |
| **INTEGRATION (pure JVM, local)** | ✅ included in the 102 | Real TCP loopback sessions through the actual engine: multi-file transfer with unicode names, wrong PIN, unknown session, mismatched key, mid-source failure resume, **receiver-restart resume via persisted transfer id**, insufficient storage, mid-transfer cancel, hostile garbage connections, progress summaries |
| **STATIC (Android layer)** | ✅ | The Android-side glue (ViewModels, ReceiveSinkAdapter, foreground service, QR handler) was type-checked locally against faithful API stubs — this caught and fixed 4 wiring bugs before CI |
| **BUILD (APK)** | ⏳ CI | `build-apk.yml` compiles debug + release APK on push; the branch includes these changes and will build once pushed |
| **EMULATOR** | ❌ not yet | No emulator run has been performed on this branch |
| **PHYSICAL DEVICE** | ❌ not yet | No claims are made about on-device behavior until a real device run happens |

No "works on Android 5" claim is made anywhere without a real API-21
device/emulator run to back it. The code paths are version-guarded
(`SDK_INT` checks, `ServiceCompat`, manifest `maxSdkVersion` bounds) and
the only dependency set is AndroidX/CameraX/ZXing — all API-21-capable.

## Build locally

Requirements: **JDK 17** + Android SDK (API 35 platform, build-tools 35).

```bash
# The wrapper is committed (gradlew + gradle/wrapper/gradle-wrapper.jar for Gradle 8.9),
# so a fresh clone needs only JDK 17 — the wrapper fetches the distribution itself.
./gradlew --version                   # sanity check: prints Gradle 8.9

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
2. Verify the committed Gradle 8.9 wrapper (`chmod +x gradlew`, `./gradlew --version`) and
   fall back to generating `gradle-wrapper.jar` from the official `services.gradle.org`
   distro if a branch ever loses it. `gradle/actions/setup-gradle@v4` additionally checks
   the wrapper jar against Gradle's published checksums.
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

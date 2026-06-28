# Simple ws proxy

Android client for the **simple ws proxy** protocol. The app runs a local **SOCKS5**
server on the phone (with username/password authentication) and tunnels traffic to a
remote server over **WebSocket**.

The UI has three tabs:

- **Play** — a single start/stop button (green for start, red for stop); status and
  errors are shown below the button;
- **Settings** — server address `host:port`, local listen port, secret key, SOCKS5
  username/password (password fields can be revealed with the eye button), and a
  **Route all traffic (VPN)** switch;
- **Logs** — a runtime log (last lines) that you can copy or clear.

The tunnel runs in a foreground service, so it keeps working while the app is in the
background.

With **Route all traffic** enabled, pressing Play brings up a system VPN (Android
`VpnService`) instead of the local SOCKS5 listener: a userspace tun2socks engine
captures the whole device's traffic and forwards every TCP flow through the same
WebSocket tunnel, so no per-app proxy configuration is needed. Because the tunnel
only carries TCP, DNS is translated to DNS-over-TCP and other UDP (e.g. QUIC) is
dropped so apps fall back to TCP; IPv6 is captured and dropped to avoid leaks.

## Project dependencies

### Build tooling

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ (project uses **21**) | required to run Gradle. The JDK 21 toolchain is downloaded automatically if missing (via foojay) |
| Gradle | **9.4.1** | bundled with the project through the Gradle Wrapper (`./gradlew`); no separate install needed |
| Android Gradle Plugin (AGP) | **9.2.1** | |
| Kotlin | **2.2.10** | |
| Android SDK | Platform **API 36**, build-tools | configured via `local.properties` (`sdk.dir`) or the `ANDROID_HOME` environment variable |

App build parameters: `compileSdk = 36`, `minSdk = 24` (Android 7.0),
`targetSdk = 36`, `applicationId = com.logioniz.simplewsproxy`.

### Runtime libraries

- **Jetpack Compose** (BOM `2025.12.00`) — UI: Material 3, Activity Compose,
  Lifecycle (runtime/viewmodel), adaptive navigation suite;
- **OkHttp** `4.12.0` — WebSocket transport;
- **kotlinx-coroutines-android** `1.8.1` — concurrency.

All versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
(version catalog) and resolved automatically from Google Maven and Maven Central.

## Building from the command line

### 1. Install the environment dependencies

1. **JDK 17 or newer** (21 recommended):

   ```bash
   # Debian/Ubuntu
   sudo apt install openjdk-21-jdk
   # macOS (Homebrew)
   brew install openjdk@21
   ```

2. **Android SDK.** The easiest way is to install the
   [command line tools](https://developer.android.com/studio#command-line-tools-only),
   unpack them into `~/Android/Sdk`, and install the required components:

   ```bash
   export ANDROID_HOME="$HOME/Android/Sdk"
   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
   sdkmanager --licenses   # accept the licenses
   ```

3. Point the build at the SDK — either via the `ANDROID_HOME` environment variable, or a
   `local.properties` file in the project root:

   ```properties
   sdk.dir=/home/<user>/Android/Sdk
   ```

   > Gradle (9.4.1) does not need to be installed separately — it is fetched by `./gradlew`.

### 2. Build the project

```bash
# Linux/macOS
./gradlew assembleDebug      # debug build
./gradlew assembleRelease    # release build (unsigned, no minification)

# Windows
gradlew.bat assembleDebug
```

Useful commands:

```bash
./gradlew build      # full build + checks
./gradlew test       # unit tests
./gradlew clean      # remove build artifacts
./gradlew tasks      # list all tasks
```

## Building in Android Studio

1. Install **Android Studio** (a recent version with AGP 9.2.x support).
2. `File → Open` and select the project root folder.
3. Wait for the **Gradle Sync** — the IDE downloads Gradle, SDK components, and
   dependencies (if SDK platform 36 is missing, it offers to install it).
4. To run on a device/emulator: select the **app** configuration and press **Run ▶**.

## Generating an APK

### From the command line

```bash
./gradlew assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

For the release (unsigned) variant:

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

Install the APK on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Signed release APK

1. Create a keystore (once):

   ```bash
   keytool -genkey -v -keystore release.keystore \
     -alias simplewsproxy -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Sign and align the built APK:

   ```bash
   zipalign -v -p 4 app-release-unsigned.apk app-release-aligned.apk
   apksigner sign --ks release.keystore --out app-release.apk app-release-aligned.apk
   ```

   (`zipalign` and `apksigner` live in `$ANDROID_HOME/build-tools/<version>/`.)

### From Android Studio

`Build → Generate Signed Bundle / APK… → APK`, then select/create a keystore and the
build variant. For a debug APK: `Build → Build Bundle(s) / APK(s) → Build APK(s)`.

## License

This project is distributed under the [MIT](LICENSE) license.

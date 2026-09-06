# Android mdoc Reader

A Jetpack Compose app that reads mobile documents using NFC negotiated handover and Bluetooth LE. Protocol processing, session encryption and document verification use [zhuolabs/mdoc-reader](https://github.com/zhuolabs/mdoc-reader). Kotlin calls Rust through **UniFFI**, with a generated **suspend** API.

The reader implements these upstream extension points:

- `mdoc-ui-android`: progress and authenticated results for Compose.
- `nfc-reader-android`: Android NFC reader mode and IsoDep.
- `mdoc-transport-ble-android`: Android BLE peripheral / GATT server transport.

See [ARCHITECTURE.md](ARCHITECTURE.md) for component boundaries, lifecycle behavior and the implementation plan. See [TESTING.md](TESTING.md) for physical wallet presentation and current validation results.

## Requirements

- An Android 12+ device with NFC and BLE peripheral advertising; the initial build supports **arm64-v8a**.
- Android SDK platform 36 and **NDK 27.0.12077973**.
- A JDK compatible with the included Gradle 9.1 wrapper; Android Studio's bundled JDK works in the tested environment.
- Rust stable with `aarch64-linux-android` installed, and `cargo-ndk` 4.1.2 or compatible.
- Internet access for build dependencies, the configured IACA certificate and revocation information.

```powershell
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
android sdk install platforms/android-36 ndk/27.0.12077973
```

Open the `android` directory in Android Studio. Set the SDK location through Android Studio or `android/local.properties` (not committed). Ensure `cargo` is on the PATH inherited by Android Studio and Gradle.

## Build

From the repository root on Windows:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./android/gradlew.bat -p android :app:assembleDebug
```

On other platforms, use `./android/gradlew -p android :app:assembleDebug` and set JAVA_HOME as appropriate.

**Gradle builds Rust automatically.** Each variant runs cargo-ndk, generates Kotlin bindings with the workspace's matching UniFFI bindgen, compiles Kotlin and packages the native library. No manual Cargo build, bindgen invocation or `.so` copying is needed. The integration follows the [UniFFI Gradle guide](https://mozilla.github.io/uniffi-rs/0.31/kotlin/gradle.html), using the AGP variant API and proc-macro library metadata.

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

`assembleRelease` uses the Rust release profile; release signing is not configured. The device validation described below uses the debug APK.

## Install and read

```powershell
android run --apks=android/app/build/outputs/apk/debug/app-debug.apk --device=adb-51081JEBF12866-ekLo7L._adb-tls-connect._tcp
```

1. Enable NFC and Bluetooth on the reader and allow **Nearby devices** permission.
2. Tap **Start reading** and wait for **Hold the presenting device nearby**.
3. Start presenting a compatible mobile ID in the other device's wallet and hold the NFC areas together.
4. Keep the devices together during NFC handover. Approve the requested information in the wallet when prompted.
5. Review the authenticated attributes and portrait, then tap **Clear results**.

This is an mdoc wallet reader, not a direct reader for a physical My Number Card. The supported flow uses negotiated NFC handover with the presenting wallet acting as the BLE central. QR and static handover are not implemented.

## Request and data handling

The repository includes `android/app/src/main/assets/request.example.json`, copied from the upstream [request example](https://github.com/zhuolabs/mdoc-reader/blob/main/request.example.json). It provides a safe default for builds and requests the example mDL attributes with every `intentToRetain` flag false.

To use a private request, create `android/app/src/main/assets/request.json`. This file is ignored by Git. When both files are packaged, the app loads `request.json`; when it is absent, the app loads `request.example.json`. Rebuild after adding or changing either asset. Never commit a request containing private configuration.

Issuer certificate, issuer signature, device authentication and revocation processing are delegated to upstream Rust without enabling skip flags. The response must also have success status and contain documents. Revocation coverage depends on what the document supplies; upstream does not expose a detailed verification report through this API.

Personal attributes are not persisted or logged. Results are cleared on leaving the screen. Screenshots and Android backup are disabled for the app. UI and documentation are English; document values are shown as received.

## Kotlin API

```kotlin
// Each dependency implements one generated callback interface and can be replaced independently.
val session = ReaderSession(nfcHardware, bleHardware, eventSink)
try {
    val resultJson = session.read(requestJson) // Generated suspend fun.
} finally {
    session.cancel()
    session.close()
}
```

Run it from a lifecycle-owned coroutine. Cancelling that coroutine drops the Rust Future and shuts down hardware waits. Android callbacks run on a dedicated Rust worker; UI updates are dispatched to Main. Create a new session and hardware adapter for each read.

## Development checks

```powershell
cargo fmt --all -- --check
cargo test --workspace --exclude mdoc-uniffi-bindgen --locked
./android/gradlew.bat -p android :app:assembleDebug :app:lintDebug
$env:ANDROID_SERIAL = 'adb-51081JEBF12866-ekLo7L._adb-tls-connect._tcp'
./android/gradlew.bat -p android :app:connectedDebugAndroidTest
```

The instrumentation suite needs NFC/Bluetooth enabled and internet connectivity. It uses the actual arm64 Rust library and the device's BLE advertiser but does not read a personal document. Stop any active Android CLI layout instrumentation before running it; concurrent UI automation services conflict. Gradle's connected-test runner may uninstall the APK afterward, so reinstall for manual testing.

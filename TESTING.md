# Device testing

## Test environment

- Date: 2026-09-06.
- Reader: Pixel 9a, Android 17, arm64-v8a.
- Authorized ADB serial: `adb-51081JEBF12866-ekLo7L._adb-tls-connect._tcp`.
- Android build: minSdk 31, compile/targetSdk 36, AGP 9.0.1, Gradle 9.1.0, NDK 27.0.12077973.
- UniFFI: 0.32.0; native code and Kotlin bindings built by Gradle.
- Request SHA-256: `1700d8ccf759114010d51798351d03de3ee28105af1569a848cfc49a932ff976`.

## Automated validation

| Check | Result | Scope |
| --- | --- | --- |
| Rust formatting | Passed | `cargo fmt --all -- --check` |
| Rust tests | 6 passed | Request preservation/rejection; BLE framing across MTU and message boundaries; malformed frames and size limits; ISO 23220 date formatting |
| ISO 23220 date formatting | Passed | Plain text, CBOR full-date and the structured `birth_date` map |
| Gradle debug APK | Passed | Rust cross-compilation, Kotlin generation, Compose compilation and native packaging |
| Android lint | Passed with 17 warnings | No lint errors; dependency/target updates and platform coverage advisories remain |
| UniFFI async error test | Passed on Pixel 9a | Real arm64 library; invalid request propagates a typed exception and closes callbacks |
| Coroutine cancellation | Passed on Pixel 9a | Real certificate download; NFC callback runs off Main; Future cancellation releases the independent NFC and BLE callbacks |
| BLE peripheral startup | Passed on Pixel 9a | Actual GATT service registration and advertising callback; cancellation closes the server |
| Compose start/cancel/restart | Passed on Pixel 9a | Real NFC reader mode, certificate download, waiting screen, cancellation and a second read |

The instrumentation suite is `ReaderIntegrationTest`. Its results are generated under `android/app/build/reports/androidTests/connected/debug/` and `android/app/build/outputs/androidTest-results/connected/debug/`. Test data does not include a personal document.

An initial instrumentation run encountered a competing Android CLI UI automation service. Stopping that service allowed all four device tests to pass. Before running connected tests after `android layout`, use:

```powershell
adb -s adb-51081JEBF12866-ekLo7L._adb-tls-connect._tcp shell am force-stop com.android.cli.interact.instrumentation
```

The app uses FLAG_SECURE, so external screenshot captures of the activity are black. Compose assertions verify screen content directly.

## Manual wallet acceptance test — pending

The automated checks do **not** prove interoperability with a real presenting wallet. NFC APDU handover, the peer's GATT subscription and full encrypted document transfer require the user's presenting device.

1. On the reader, enable NFC and Bluetooth and ensure internet connectivity.
2. Open **Mdoc Reader**, tap **Start reading**, and allow **Nearby devices** if requested.
3. Wait for **Hold the presenting device nearby**. This confirms the Rust library loaded and the configured issuer certificate was downloaded.
4. Start the mobile ID presentation flow on the presenting wallet. Align the NFC areas of the two devices and hold them together.
5. Expect **Setting up NFC handover**, followed by **Waiting for Bluetooth connection** and **Approve sharing on the presenting device**. Some brief intermediate states may be too fast to see.
6. Approve sharing in the wallet. Expect **Verifying the received document**, then **Document received and authenticated**.
7. Check the returned name, address, individual number, portrait, birth date, sex, age and local government code. The request has 11 elements across two namespaces; a wallet can return partial data or element errors.
8. Tap **Clear results** and confirm the attributes disappear. Repeat a read, then leave the app and confirm results are cleared on return.

Do not provide personal attribute values when reporting failures. Report the last visible stage, the exact error text, the presenting device/wallet version, and whether wallet consent appeared. There is no need to change or disable verification to start this test.

### Follow-up failure cases

- Move the presenting device away before NFC handover completes: show an NFC error and allow retry.
- Decline the wallet request: show an error or a timeout; never claim successful authentication of an empty response.
- Tap **Cancel** while waiting for NFC, BLE or consent: stop the session and allow a new read.
- Disable Bluetooth during a session: report failure and release the GATT server.
- Remove internet access: report certificate/revocation download failure; do not silently enable skip flags.

## Current limitations

- Real-wallet end-to-end presentation remains untested until the user presents an mdoc.
- The first APK targets arm64 devices only; no emulator/x86_64 support is configured.
- Release signing and distribution are not configured; physical validation uses the debug build.
- Only upstream's negotiated NFC handover with reader-peripheral BLE is supported.
- Upstream controls verification policy and can mark some revocation checks as unavailable. The adapter does not expose a detailed verification report.
- The reader does not implement a separate reader-authentication credential. Wallets requiring one may reject the request.

## Handoff state

The final debug APK was reinstalled on the authorized Pixel 9a after testing, with runtime permissions granted. MainActivity was confirmed as the resumed activity. The app is ready for the user to tap **Start reading** and present an mdoc. No real personal document has been read during implementation.


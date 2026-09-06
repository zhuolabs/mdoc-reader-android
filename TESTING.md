# Device testing

## Test environment

- Date: 2026-09-06.
- Reader: Pixel 9a, Android 17, arm64-v8a.
- Authorized ADB serial: `adb-51081JEBF12866-ekLo7L._adb-tls-connect._tcp`.
- Android build: minSdk 31, compile/targetSdk 36, AGP 9.0.1, Gradle 9.1.0, NDK 27.0.12077973.
- UniFFI: 0.32.0; native code and Kotlin bindings built by Gradle.
- Request SHA-256: `1700d8ccf759114010d51798351d03de3ee28105af1569a848cfc49a932ff976`.

## USB BTstack validation (2026-09-06)

### Advertising API migration

btstack-gatt-rs revision `18081f4da678ca8bba87084787f4365dd0a233dc` adds typed legacy advertising configuration and `.advertise_service_uuid(...)`. The reader now uses that API with the negotiated UUID; its HCI command rewriting shim and direct `btstack-core` dependency have been removed. Advertising encoding/limit tests now live upstream.

After migration: btstack-gatt-rs workspace tests **15 passed** (one desktop USB hardware test ignored), strict workspace Clippy passed; reader workspace tests **7 passed**, targeted backend Clippy and formatting passed. Both reader debug flavors built, and BTstack lint passed. On the Pixel 9a + 0411:0374, `ReaderIntegrationTest` passed all **4 tests**, and `UsbGattWireTest` plus the PC central passed **two rounds** of UUID discovery, Ident, State and 200 frames in each direction, with server restart between rounds. Records: `.artifacts/advertising-api-integration.txt`, `advertising-api-wire.txt`, and `advertising-api-central.txt`.

The user reported the preceding implementation working. This migration was validated with synthetic BLE traffic; no additional personal wallet presentation was performed by the agent.

### Initial USB implementation

Validated on the Pixel 9a with USB dongle **0411:0374** (Bluetooth Radio). ADB `192.168.1.9:46131` and the mDNS entry refer to the same physical device; use one serial to avoid duplicate test runs.

- Both `platformDebug` and `btstackDebug` APKs built successfully, including generated UniFFI bindings. Both flavor lint tasks: **0 errors, 17 warnings**.
- Rust workspace: **8 tests passed**, including negotiated UUID advertising byte order and preserving queued response frames when State=2 arrives.
- Formatting and `cargo clippy -p mdoc-transport-btstack --all-targets --locked -- -D warnings`: passed. Workspace-wide strict Clippy encounters a pre-existing `collapsible_if` warning in `mdoc-ui-android/src/lib.rs`; this change leaves that unrelated code untouched.
- `ReaderIntegrationTest`: **4 tests passed per flavor**. USB startup exercises the actual dongle and controller-confirmed advertising; Compose exercises the direct Rust USB constructor, NFC waiting, cancel and restart.
- `UsbGattWireTest` plus `scripts/verify_usb_mdoc.py`: **passed two rounds in one app process**, with server shutdown, USB release and restart between rounds. A Windows PC BLE central found the test UUID in advertising, discovered GATT, read the 16-byte Ident, subscribed, wrote State=1, received 200 ordered notification frames, sent 200 ordered response frames, and wrote State=2. Android validated every received frame and orderly termination. Observed ATT MTU: **527**; shared framing caps characteristic values at 512 bytes.
- USB permission dialog and transition to NFC waiting were checked on the device. The USB flavor does not request Nearby devices permissions.

The first wire run exposed a race between queued response frames and immediate State=2; the backend now drains accepted frames before reporting orderly termination. Lifecycle testing exposed a closed UniFFI handle during suspended USB cleanup; cleanup is now non-cancellable, clears the UI handle first, and gates restart until complete.

### Reproduce USB tests

Build and install the app and test APK. Open the USB app once, tap Start reading, allow USB access, and cancel. Retain USB permission by reinstalling rather than uninstalling.

```powershell
./android/gradlew.bat -p android :app:assembleBtstackDebug :app:assembleBtstackDebugAndroidTest
android install --apks=android/app/build/outputs/apk/btstack/debug/app-btstack-debug.apk --device=192.168.1.9:46131
android install --apks=android/app/build/outputs/apk/androidTest/btstack/debug/app-btstack-debug-androidTest.apk --device=192.168.1.9:46131
adb -s 192.168.1.9:46131 shell am force-stop com.android.cli.interact.instrumentation
adb -s 192.168.1.9:46131 shell am instrument -w -e class com.example.mdocreader.ReaderIntegrationTest com.example.mdocreader.btstack.test/androidx.test.runner.AndroidJUnitRunner
```

For the opt-in wire test, run `uv run scripts/verify_usb_mdoc.py` on a PC with its own Bluetooth adapter, then run this in another terminal within its 120-second discovery timeout:

```powershell
adb -s 192.168.1.9:46131 shell am instrument -w -e usbWireTest true -e class com.example.mdocreader.UsbGattWireTest com.example.mdocreader.btstack.test/androidx.test.runner.AndroidJUnitRunner
```

The PC script connects only to the dedicated test service UUID. Packets contain synthetic bytes and no personal document. The wire test is skipped unless explicitly enabled. Local terminal records are under ignored `.artifacts/btstack-integration.txt`, `platform-integration.txt`, `btstack-wire.txt`, and `btstack-central.txt`.

Real-wallet NFC handover, consent and authenticated document retrieval remain **unverified**. The synthetic BLE test does not establish wallet interoperability. Physical unplug/replug during an active transfer was not exercised. Release variants are configured but were not used for device validation.

## Original platform validation

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


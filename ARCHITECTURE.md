# Android mdoc Reader architecture

## Scope

A Jetpack Compose Android application for reading an mdoc through NFC negotiated handover followed by BLE retrieval. Protocol and verification logic comes from https://github.com/zhuolabs/mdoc-reader, pinned to revision `bc973b1d1b58da55f3c99779b72f7e951e7911ef` through Cargo git dependencies.

The Android reader is the BLE peripheral / GATT server. The presenting wallet is the BLE central / GATT client. NFC uses Android reader mode and IsoDep. This reads mobile documents presented by a compatible wallet, not a physical My Number Card. QR engagement, static handover and reader-central BLE mode are outside the initial scope. All documentation and application UI are in English; received attribute values remain unchanged.

## Components

```text
Kotlin UI / MainActivity
    |  upstream UniFFI ReaderSession
    v
mdoc-reader-ffi -> native read_mdoc
    |                    |
    |               GenericNfcReader -> Kotlin AndroidNfcReader
    |
BleMdocTransport -> BleBackend -> Kotlin AndroidBleBackend / Rust USB bridge
```

- Upstream `nfc-reader` retains its native Send-only, exclusively accessed API with an owned `Tag` associated type. Its optional UniFFI `NfcBackend` and single `GenericNfcReader` adapter preserve native ergonomics without multiple Android forwarding layers.
- Upstream `mdoc-transport-ble` owns framing, assembly, validation, size limits, operation deadlines and receive ordering. Kotlin implements that crate's async `BleBackend` directly. `BleConnectionInfo` reports usable characteristic bytes, not ATT MTU.
- Upstream `mdoc-reader-ffi` owns request validation, certificate loading, ephemeral keys, trusted-issuer policy, single-use sessions, cancellation and presentation JSON. The structured `read_mdoc_foreign` entry also returns validated DeviceResponse CBOR. Native `read_mdoc` remains unchanged.
- `mdoc-android` only packages the shared library and optional USB descriptor bridge. `nfc-reader-android`, `mdoc-transport-ble-android` and `mdoc-ui-android` were removed after migration. Their applicable tests moved upstream.
- `mdoc-android-platform` retains only the synchronous `BlePlatform` and `EventSink` contract used by the existing USB driver. There is no platform trait layer between Android Kotlin and upstream NFC/BLE.
- `AndroidNfcReader` owns reader mode and IsoDep; `AndroidBleBackend` owns advertising and GATT. `UiEventSink`, `MainActivity` and `ReaderScreen` retain their existing composition, event codes and UI behavior.

Generated interfaces use `uniffi.nfc_reader`, `uniffi.mdoc_transport_ble` and `uniffi.mdoc_reader_ffi`. Only optional `UsbBleHardware` remains in the Android library's own `com.example.mdocreader.rust` namespace.

## USB BTstack flavor

`platform` selects the original Android GATT backend. `btstack` enables the Cargo `btstack` feature and packages `mdoc-transport-btstack`, with btstack-gatt-rs pinned to `18081f4da678ca8bba87084787f4365dd0a233dc`. `BleBackend` is supplied by flavor source sets. Cargo target directories are isolated per variant to prevent feature/output races.

`UsbManager` enumerates Bluetooth HCI interfaces, requires exactly one candidate, requests permission, and opens `UsbDeviceConnection`. UniFFI `UsbBleHardware` duplicates the borrowed FD synchronously and passes its `OwnedFd` to `NusbHciTransport::from_fd`. `UsbBleHardware.readerSession` wraps the existing synchronous `BtstackBle` in a thin Rust `BleBackend` bridge; document packets do not cross Kotlin callbacks. Upstream `BleMdocTransport` supplies framing and assembly for both flavors.

The backend calls `GattServer::builder(transport).advertise_service_uuid(uuid)` with the UUID negotiated by mdoc. btstack-gatt-rs owns AD structure encoding, Bluetooth UUID byte order and legacy payload validation, and passes the resulting payload to BTstack normally. GATT registration remains separate from advertising. The app does not inspect or rewrite HCI commands and no longer directly depends on `btstack-core`.

GATT callbacks only update bounded shared state. A single selected peer must subscribe to Server2Client and write State=1 before connect returns. Notifications use upstream's bounded enqueue API with backpressure and transmission-error events. State=2 delivers already accepted frames before reporting orderly termination; malformed frames, queue overflow and transport errors fail the session. Notifications remain enqueue acknowledgements, not delivery acknowledgements.

Cancellation sets an atomic flag without blocking Main. Kotlin's non-cancellable IO cleanup waits for server shutdown and USB release before closing the original FD. Rust callbacks poll cancellation at 20 ms intervals during normal waits; controller startup is bounded by upstream (up to 25 seconds plus shutdown). USB detach signals cancellation. The UI clears its native session reference before cleanup suspends, preventing lifecycle callbacks from touching a closed UniFFI handle.

## Async API and cancellation

UniFFI 0.32.0 proc macros generate `suspend fun read(requestJson: String): String`. Kotlin calls it directly from `lifecycleScope.launch`. UniFFI's Tokio integration supplies a shared runtime, and the Send upstream flow runs as a Tokio task. Kotlin BLE waits use bounded coroutine channels; IsoDep uses `runInterruptible(Dispatchers.IO)`. Only the optional synchronous USB driver uses a Rust blocking-worker bridge. Android callbacks enqueue data without blocking the UI. No per-read OS thread, runtime, or result channel is needed.

Each session is single-use. A Rust Future drop guard, session drop and `ReaderSession.cancel()` close the Android resources. An abort-on-drop task handle aborts the flow when its UniFFI future is dropped, and a cancellation token wakes async waits immediately. This releases outstanding NFC/BLE waits, including when Kotlin cancels the coroutine. Cancellation and the overall deadline remain responsive during network and blocking hardware waits. Kotlin's finally block releases the UniFFI handle and disables NFC reader mode. Leaving the activity cancels the session and clears results. Generation IDs reject stale progress callbacks.

Deadlines: NFC 120 seconds; BLE connection 120 seconds; BLE response 120 seconds; GATT service/advertising/notification operations 10 seconds; initial certificate download 30 seconds; overall asynchronous flow 420 seconds. Synchronous hardware calls have their own deadlines. Response buffering is bounded to 16 MiB / 100,000 packets on Rust and 2,048 queued frames on Android.

## BLE protocol

The service UUID is generated by upstream and advertised in the handover request. Characteristics use the upstream reader-peripheral UUIDs:

| Characteristic | UUID | Properties |
| --- | --- | --- |
| State | 00000005-a123-48ce-896b-4c76973373e6 | Write without response, notify |
| Client2Server | 00000006-a123-48ce-896b-4c76973373e6 | Write without response |
| Server2Client | 00000007-a123-48ce-896b-4c76973373e6 | Notify |
| Ident | 00000008-a123-48ce-896b-4c76973373e6 | Read |

Connection readiness requires a peer, Server2Client CCCD subscription and State START (0x01). The platform backend serializes notifications using `onNotificationSent`; the USB backend uses the BTstack queue. Upstream framing uses 0x01 for continuation and 0x00 for LAST. Android reports `min(ATT MTU - 3, 512)` as usable characteristic size; the common layer subtracts only the one-byte mdoc flag. Ordered completes at LAST. WinRT uses the same common transport with Relaxed and a fixed 30 ms grace period for late MORE frames. The defensive SessionData decode/decrypt reorder recovery is retained upstream. A single peer is accepted per session. Invalid writes, queue overflow and disconnects fail the session. Advertising and GATT resources are always released.

## Request and verification

`android/app/src/main/assets/request.example.json` is the tracked default request. An ignored `request.json` in the same directory overrides it at runtime, allowing private request configuration to remain local. The selected request must contain an HTTPS IACA URL. INTERNET is used for certificates and revocation information; Android 12+ CONNECT and ADVERTISE permissions are requested at runtime by the platform flavor; the USB flavor requests USB device access instead.

Cryptography, issuer authentication, device authentication, certificate validation and revocation processing remain in upstream Rust. The verification policy requires a trusted issuer and enables both CRL and MSO revocation checks. The UI adapter additionally rejects nonzero response status and absent/empty documents. Upstream may report MSO revocation as NotChecked when information is unavailable and exposes validation only as success/error, so the UI does not claim every revocation check was completed. Per-document/per-element errors are displayed separately.

Attributes and portraits remain in memory. No APDUs, decrypted documents or personal attributes are written to logs or disk. Android backup is disabled and FLAG_SECURE protects the result screen. The app does not register a Rust logger, since upstream debug/info logs can contain protocol information.

## Gradle integration

The [UniFFI Gradle integration guide](https://mozilla.github.io/uniffi-rs/0.31/kotlin/gradle.html) is implemented with AGP's current variant sources API and library metadata instead of UDL.

For each variant, `build<Variant>Rust` invokes cargo-ndk with a locked Cargo dependency graph and Android NDK 27. `generate<Variant>UniFFIBindings` then runs the workspace's version-matched bindgen against that library. Generated Kotlin and native libraries are registered with AGP, so normal `assembleDebug` / `assembleRelease` tasks build and package everything. Outputs live under `app/build/generated`; source files and manifests are Gradle task inputs. No generated Kotlin or native binaries are committed. The initial ABI is arm64-v8a for the authorized Pixel 9a; minSdk is 27 and compile/targetSdk are 36.

## Refactoring checkpoints

Both repositories contain separate commits for NFC ownership, common BLE framing/ordering, WinRT backend migration, Kotlin BLE/NFC adoption, the upstream FFI entry and removal of obsolete Android adapters. Cargo's temporary local checkout references are excluded from commits; committed manifests pin matching upstream Git revisions. Push those upstream revisions before distributing the dependent Android commits.

## Acceptance criteria

Automated tests cover BLE frame boundaries, malformed input, buffer bounds and request parsing. Android validation covers compilation, lint, Compose state and the actual UniFFI async bridge. The authorized Pixel 9a must install and launch, request permissions, load the issuer certificate, wait for NFC, cancel and restart successfully. BLE server registration/advertising must be exercised on that hardware before handoff.

A real wallet presentation remains a separate user-assisted acceptance test: NFC handover -> BLE connection -> wallet consent -> authenticated attributes and portrait. Startup or synthetic tests do not prove wallet interoperability; verification records must distinguish them.




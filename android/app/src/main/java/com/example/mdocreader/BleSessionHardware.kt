package com.example.mdocreader

import uniffi.mdoc_transport_ble.BleBackend as RustBleBackend
import uniffi.nfc_reader.NfcBackend
import uniffi.mdoc_reader_ffi.ReaderEventSink
import uniffi.mdoc_reader_ffi.ReaderSession

interface BleSessionHardware : RustBleBackend {
    fun readerSession(nfc: NfcBackend, events: ReaderEventSink): ReaderSession = ReaderSession(nfc, this, events)
    suspend fun finish() { shutdown() }
}

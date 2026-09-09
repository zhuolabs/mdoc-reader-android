package com.example.mdocreader

import uniffi.mdoc_transport_ble.BleBackend as RustBleBackend
import uniffi.nfc_reader.NfcBackend
import com.example.mdocreader.rust.ReaderEventSink
import com.example.mdocreader.rust.ReaderSession

interface BleSessionHardware : RustBleBackend {
    fun readerSession(nfc: NfcBackend, events: ReaderEventSink): ReaderSession = ReaderSession(nfc, this, events)
    suspend fun finish() { shutdown() }
}

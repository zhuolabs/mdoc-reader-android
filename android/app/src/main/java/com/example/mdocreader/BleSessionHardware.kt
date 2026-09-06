package com.example.mdocreader

import com.example.mdocreader.rust.BleHardware
import com.example.mdocreader.rust.NfcHardware
import com.example.mdocreader.rust.ReaderEventSink
import com.example.mdocreader.rust.ReaderSession

interface BleSessionHardware : BleHardware {
    fun readerSession(nfc: NfcHardware, events: ReaderEventSink): ReaderSession = ReaderSession(nfc, this, events)
    suspend fun finish() { shutdown() }
}

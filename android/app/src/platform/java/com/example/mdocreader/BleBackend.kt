package com.example.mdocreader

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.mdocreader.rust.ReaderEventSink

object BleBackend {
    const val description = "Connect with NFC. Receive securely over Bluetooth."
    const val settingsLabel = "NFC & Bluetooth settings"
    val permissions = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    @android.annotation.SuppressLint("MissingPermission")
    fun checkAvailable(context: Context) {
        check(context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) {
            "Turn on Bluetooth to continue."
        }
    }
    suspend fun open(context: Context, events: ReaderEventSink): BleSessionHardware = AndroidBleHardware(context, events)
}

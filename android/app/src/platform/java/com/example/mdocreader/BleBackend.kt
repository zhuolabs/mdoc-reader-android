package com.example.mdocreader

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.example.mdocreader.rust.ReaderEventSink

object BleBackend {
    const val description = "Connect with NFC. Receive securely over Bluetooth."
    const val settingsLabel = "NFC & Bluetooth settings"
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        emptyArray()
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun checkAvailable(context: Context) {
        check(context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) {
            "Turn on Bluetooth to continue."
        }
    }
    suspend fun open(context: Context, events: ReaderEventSink): BleSessionHardware = AndroidBleHardware(context, events)
}

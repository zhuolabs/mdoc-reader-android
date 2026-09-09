package com.example.mdocreader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.example.mdocreader.rust.ReaderEventSink
import com.example.mdocreader.rust.UsbBleHardware
import com.example.mdocreader.rust.ReaderSession
import com.example.mdocreader.rust.NfcHardware
import kotlinx.coroutines.*
import uniffi.mdoc_transport_ble.*
import java.util.concurrent.atomic.AtomicBoolean

object BleBackend {
    const val description = "Connect with NFC. Receive securely through the USB BLE dongle."
    const val settingsLabel = "NFC settings"
    val permissions = emptyArray<String>()
    private fun device(context: Context): UsbDevice {
        val devices = context.getSystemService(UsbManager::class.java).deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { index ->
                val iface = device.getInterface(index)
                iface.interfaceClass == 0xe0 && iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1
            }
        }
        check(devices.size == 1) { "Connect exactly one compatible USB BLE dongle (found ${devices.size})." }
        return devices.single()
    }
    fun checkAvailable(context: Context) { device(context) }

    suspend fun open(context: Context, events: ReaderEventSink): BleSessionHardware {
        val app = context.applicationContext
        val usb = app.getSystemService(UsbManager::class.java)
        val device = device(app)
        if (!usb.hasPermission(device)) {
            events.onEvent("usb_permission")
            val action = "${app.packageName}.USB_PERMISSION.${java.util.UUID.randomUUID()}"
            val result = CompletableDeferred<Unit>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != action) return
                    if (usb.hasPermission(device)) result.complete(Unit)
                    else result.completeExceptionally(IllegalStateException("USB permission denied or dongle removed. Try again."))
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pending = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
            try {
                usb.requestPermission(device, pending)
                withTimeout(120_000) { result.await() }
            } finally { pending.cancel(); app.unregisterReceiver(receiver) }
        }
        // Keep resource acquisition inside the coroutine: cancellation during a
        // dispatcher switch must not drop a newly returned native USB owner.
        var connection: UsbDeviceConnection? = null
        var native: UsbBleHardware? = null
        try {
            withContext(Dispatchers.IO) {
                connection = checkNotNull(usb.openDevice(device)) { "Cannot open USB dongle" }
                native = UsbBleHardware(connection.fileDescriptor, events)
            }
            return UsbSession(app, device, connection!!, native!!)
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { native?.closeTransport(); native?.close(); connection?.close() }
            throw error
        }
    }
}

private class UsbSession(
    private val context: Context,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val native: UsbBleHardware,
) : BleSessionHardware {
    private val closed = AtomicBoolean(false)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val removed = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (removed?.deviceName == device.deviceName) shutdown()
        }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override suspend fun connect(params: BleBackendParams): BleConnectionInfo = withContext(Dispatchers.IO) {
        val mtu = native.bleConnect(params.serviceUuid, params.ident, 120_000u)
        BleConnectionInfo(minOf(mtu.toUInt() - 3u, 512u), BleReceiveOrdering.Ordered)
    }
    override fun readerSession(nfc: NfcHardware, events: ReaderEventSink) = ReaderSession.withUsb(nfc, native, events)
    override suspend fun send(value: ByteArray) = withContext(Dispatchers.IO) { native.bleSend(value) }
    override suspend fun receive() = withContext(Dispatchers.IO) { native.bleReceive(120_000u) }
    override fun shutdown() { native.shutdown() }
    override suspend fun finish() {
        if (!closed.compareAndSet(false, true)) return
        shutdown()
        withContext(NonCancellable + Dispatchers.IO) {
            try { native.closeTransport() }
            finally { connection.close(); context.unregisterReceiver(receiver) }
        }
        // ReaderSession's worker may still hold callbacks briefly; its Arc and
        // the generated object's cleaner release the handle after those calls.
    }
}

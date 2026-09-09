package com.example.mdocreader

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import uniffi.mdoc_transport_ble.BleBackendParams
import uniffi.mdoc_transport_ble.BleConnectionInfo
import uniffi.mdoc_transport_ble.BleReceiveOrdering
import uniffi.mdoc_transport_ble.BleBackendException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import uniffi.mdoc_reader_ffi.ReaderEventSink
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android GATT-server implementation of the UniFFI BLE callback interface. */
@SuppressLint("MissingPermission") // MainActivity checks CONNECT/ADVERTISE before construction.
class AndroidBleBackend(
    context: Context,
    private val events: ReaderEventSink,
) : BleSessionHardware {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private val lock = Any()
    private val serviceAdded = Channel<Int>(1)
    private val advertising = Channel<Int>(1)
    private val notifications = Channel<Int>(1)
    private val packets = Channel<ByteArray>(2048)
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var peer: BluetoothDevice? = null
    @Volatile private var mtu = 23
    @Volatile private var subscribed = false
    @Volatile private var stateStarted = false
    private var ident = byteArrayOf()
    private lateinit var s2c: BluetoothGattCharacteristic
    private val subscriptions = mutableMapOf<UUID, ByteArray>()

    override suspend fun connect(params: BleBackendParams): BleConnectionInfo = translateErrors {
        val uuid = params.serviceUuid
        val ident = params.ident
        val timeoutMs = 120_000L
        val adapter = manager.adapter ?: throw BleBackendException.Failure("Bluetooth is not supported")
        if (!adapter.isEnabled) throw BleBackendException.Failure("Turn on Bluetooth")
        val leAdvertiser = adapter.bluetoothLeAdvertiser
            ?: throw BleBackendException.Failure("BLE peripheral advertising is unavailable")
        val service = BluetoothGattService(UUID.fromString(uuid), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fun characteristic(id: UUID, properties: Int, permissions: Int, notify: Boolean = false): BluetoothGattCharacteristic {
            val characteristic = BluetoothGattCharacteristic(id, properties, permissions)
            if (notify) characteristic.addDescriptor(
                BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
            )
            check(service.addCharacteristic(characteristic))
            return characteristic
        }
        characteristic(
            STATE,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
            true,
        )
        characteristic(C2S, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        s2c = characteristic(S2C, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0, true)
        characteristic(IDENT, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        synchronized(lock) {
            checkOpen()
            this.ident = ident.copyOf()
            server = manager.openGattServer(appContext, callback)
                ?: throw BleBackendException.Failure("Cannot open GATT server")
            advertiser = leAdvertiser
            if (server?.addService(service) != true) throw BleBackendException.Failure("Cannot add GATT service")
        }
        if (await(serviceAdded, 10_000, "GATT service registration") != BluetoothGatt.GATT_SUCCESS) {
            throw BleBackendException.Failure("GATT service registration failed")
        }
        synchronized(lock) {
            checkOpen()
            leAdvertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(UUID.fromString(uuid)))
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build(),
                advertiseCallback,
            )
        }
        val code = await(advertising, 10_000, "BLE advertising")
        if (code != 0) throw BleBackendException.Failure("Cannot start BLE advertising (code=$code)")
        events.onEvent("ble_advertising")
        while (!subscribed || !stateStarted || peer == null) {
            checkOpen()
            if (SystemClock.elapsedRealtime() >= deadline) throw BleBackendException.Failure("BLE connection timed out")
            delay(20)
        }
        BleConnectionInfo(minOf(mtu - 3, 512).toUInt(), BleReceiveOrdering.Ordered)
    }

    override suspend fun send(value: ByteArray) = translateErrors {
        if (!subscribed) throw BleBackendException.Failure("BLE notifications are not subscribed")
        val chunk = value
        while (notifications.tryReceive().isSuccess) { /* drain stale callbacks */ }
        synchronized(lock) {
            checkOpen()
            val device = peer ?: throw BleBackendException.Failure("BLE is not connected")
            val gatt = server ?: throw BleBackendException.Failure("GATT server is closed")
            val sent = if (Build.VERSION.SDK_INT >= 33) {
                gatt.notifyCharacteristicChanged(device, s2c, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                s2c.value = chunk
                @Suppress("DEPRECATION")
                gatt.notifyCharacteristicChanged(device, s2c, false)
            }
            if (!sent) throw BleBackendException.Failure("Cannot send BLE notification")
        }
        if (await(notifications, 10_000, "BLE notification") != BluetoothGatt.GATT_SUCCESS) {
            throw BleBackendException.Failure("BLE notification failed")
        }
    }

    override suspend fun receive(): ByteArray = translateErrors { packets.receive() }

    override fun shutdown() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            advertiser = null
            runCatching { peer?.let { server?.cancelConnection(it) } }
            runCatching { server?.close() }
            server = null
            peer = null
            packets.close(BleBackendException.Disconnected())
            notifications.close(BleBackendException.Disconnected())
            serviceAdded.close(BleBackendException.Disconnected())
            advertising.close(BleBackendException.Disconnected())
            ident.fill(0)
        }
    }

    private fun fail(details: String) {
        failure.compareAndSet(null, details)
        val error = BleBackendException.Failure(details)
        packets.close(error)
        notifications.close(error)
        serviceAdded.close(error)
        advertising.close(error)
    }

    private fun checkOpen() {
        if (closed.get()) throw BleBackendException.Failure("Reading cancelled")
        failure.get()?.let { throw BleBackendException.Failure(it) }
    }

    private suspend inline fun <T> translateErrors(block: () -> T): T = try {
        checkOpen()
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: BleBackendException) {
        throw error
    } catch (error: Exception) {
        throw BleBackendException.Failure(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun <T> await(queue: Channel<T>, timeoutMs: Long, label: String): T {
        return withTimeoutOrNull(timeoutMs) { queue.receive() }
            ?: throw BleBackendException.Failure("$label timed out")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising.trySend(0) }
        override fun onStartFailure(errorCode: Int) { advertising.trySend(errorCode) }
    }

    private fun accepts(device: BluetoothDevice): Boolean = !closed.get() && peer == device
    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int = 0, data: ByteArray? = null) {
        runCatching { server?.sendResponse(device, requestId, status, offset, data) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { serviceAdded.trySend(status) }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(lock) {
                if (closed.get()) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (peer == null) peer = device else if (peer != device) server?.cancelConnection(device)
                } else if (device == peer) {
                    fail("BLE disconnected (status=$status)")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (accepts(device)) this@AndroidBleBackend.mtu = mtu.coerceIn(23, 517)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (accepts(device)) notifications.trySend(status)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!accepts(device)) { respond(device, requestId, BluetoothGatt.GATT_FAILURE); return }
            if (characteristic.uuid != IDENT) { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED); return }
            if (offset !in 0..ident.size) { respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET); return }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ident.copyOfRange(offset, ident.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (!accepts(device) || preparedWrite || offset != 0) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else when (characteristic.uuid) {
                STATE -> when {
                    value.contentEquals(byteArrayOf(1)) -> stateStarted = true
                    value.contentEquals(byteArrayOf(2)) -> fail("Presenting device ended the BLE session")
                    else -> status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                }
                C2S -> if (value.isEmpty() || value.size > 512) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    fail("Invalid BLE frame")
                } else if (!packets.trySend(value.copyOf()).isSuccess) {
                    fail("BLE receive queue overflow")
                }
                else -> status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            }
            if (responseNeeded) respond(device, requestId, status)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            var status = BluetoothGatt.GATT_SUCCESS
            if (!accepts(device) || preparedWrite || offset != 0 || descriptor.uuid != CCCD) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else if (!value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) &&
                !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            ) {
                status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            } else synchronized(lock) {
                subscriptions[descriptor.characteristic.uuid] = value.copyOf()
                if (descriptor.characteristic.uuid == S2C) {
                    subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
            if (responseNeeded) respond(device, requestId, status)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (!accepts(device) || descriptor.uuid != CCCD) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }
            val value = synchronized(lock) {
                subscriptions[descriptor.characteristic.uuid] ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            if (offset !in 0..value.size) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
            else respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
        }
    }

    companion object {
        val STATE: UUID = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
        val C2S: UUID = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
        val S2C: UUID = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
        val IDENT: UUID = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

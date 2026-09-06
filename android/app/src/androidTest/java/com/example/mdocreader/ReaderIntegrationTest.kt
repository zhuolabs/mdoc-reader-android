package com.example.mdocreader

import android.Manifest
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mdocreader.rust.BleHardware
import com.example.mdocreader.rust.NfcHardware
import com.example.mdocreader.rust.ReaderEventSink
import com.example.mdocreader.rust.ReaderException
import com.example.mdocreader.rust.ReaderSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the real arm64 library and authorized device; no personal data fixtures. */
class ReaderIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun grantHardwarePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
    }

    @Test fun invalidRequestCrossesAsyncUniffiBoundary() = runBlocking {
        val nfc = StubNfc()
        val ble = StubBle()
        val reader = ReaderSession(nfc, ble, StubEvents())
        try {
            try { withTimeout(5_000) { reader.read("{}") }; fail("Invalid request must fail") }
            catch (error: ReaderException.Failure) { assertTrue(error.details.contains("iacaCert")) }
            withTimeout(5_000) { while (!nfc.closed.get() || !ble.closed.get()) delay(10) }
        } finally { reader.cancel(); reader.close() }
    }

    @Test fun coroutineCancellationClosesIndependentHardwareCallbacks() = runBlocking {
        val reachedNfc = CountDownLatch(1)
        val released = CountDownLatch(1)
        val offMain = AtomicBoolean(false)
        val nfc = object : StubNfc() {
            override fun nfcConnect(timeoutMs: ULong): Boolean {
                offMain.set(Looper.myLooper() != Looper.getMainLooper())
                reachedNfc.countDown()
                if (!released.await(15, TimeUnit.SECONDS)) throw ReaderException.Failure("Test cancellation timeout")
                throw ReaderException.Failure("Cancelled")
            }
            override fun shutdown() { super.shutdown(); released.countDown() }
        }
        val ble = StubBle()
        val reader = ReaderSession(nfc, ble, StubEvents())
        val request = try {
            compose.activity.assets.open("request.json")
        } catch (_: FileNotFoundException) {
            compose.activity.assets.open("request.example.json")
        }.bufferedReader().use { it.readText() }
        val job = launch(Dispatchers.Default) { reader.read(request) }
        try {
            assertTrue("Certificate download and NFC callback", withContext(Dispatchers.IO) { reachedNfc.await(40, TimeUnit.SECONDS) })
            assertTrue("Callback must not block Main", offMain.get())
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue("Future cancellation must shut down NFC", released.await(5, TimeUnit.SECONDS))
            assertTrue("Future cancellation must shut down BLE", ble.closed.get())
        } finally { reader.cancel(); job.cancelAndJoin(); reader.close() }
    }

    @Test fun realGattServiceAdvertisesAndCanBeCancelled() = runBlocking {
        val advertised = CountDownLatch(1)
        val events = StubEvents { if (it == "ble_advertising") advertised.countDown() }
        val hardware: BleHardware = AndroidBleHardware(compose.activity, events)
        val job = async(Dispatchers.IO) {
            try { hardware.bleConnect(UUID.randomUUID().toString(), ByteArray(16), 120_000u); false }
            catch (_: ReaderException.Failure) { true }
        }
        try {
            assertTrue("GATT service registered and BLE advertising started", withContext(Dispatchers.IO) { advertised.await(25, TimeUnit.SECONDS) })
            hardware.shutdown()
            assertTrue(withTimeout(5_000) { job.await() })
        } finally { hardware.shutdown(); job.cancelAndJoin() }
    }

    @Test fun startCancelAndRestartThroughCompose() {
        compose.onNodeWithText("Start reading").performClick()
        awaitNfcScreen()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading cancelled").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start reading").performClick()
        awaitNfcScreen()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Start reading").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitNfcScreen() {
        compose.waitUntil(40_000) { compose.onAllNodesWithText("Hold the presenting device nearby").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").assertIsEnabled()
    }
}

private open class StubNfc : NfcHardware {
    val closed = AtomicBoolean(false)
    override fun nfcConnect(timeoutMs: ULong): Boolean = error("Unexpected NFC call")
    override fun nfcTransceive(command: ByteArray): ByteArray = error("Unexpected NFC transceive")
    override fun shutdown() { closed.set(true) }
}

private open class StubBle : BleHardware {
    val closed = AtomicBoolean(false)
    override fun bleConnect(uuid: String, ident: ByteArray, timeoutMs: ULong): UShort = error("Unexpected BLE connect")
    override fun bleSend(chunk: ByteArray) = error("Unexpected BLE send")
    override fun bleReceive(timeoutMs: ULong): ByteArray = error("Unexpected BLE receive")
    override fun shutdown() { closed.set(true) }
}

private class StubEvents(private val listener: (String) -> Unit = {}) : ReaderEventSink {
    override fun onEvent(event: String) = listener(event)
}

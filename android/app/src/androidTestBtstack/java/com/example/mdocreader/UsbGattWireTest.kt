package com.example.mdocreader

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mdocreader.rust.ReaderEventSink
import com.example.mdocreader.rust.ReaderException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in test paired with scripts/verify_usb_mdoc.py on a separate BLE central. */
class UsbGattWireTest {
    @Test fun exchangeFramesAndRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("usbWireTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(2) { round ->
            val events = object : ReaderEventSink {
                override fun onEvent(event: String) { Log.i("UsbMdocTest", "round=$round $event") }
            }
            val hardware = BleBackend.open(context, events)
            try {
                withContext(Dispatchers.IO) {
                    val mtu = hardware.bleConnect(SERVICE, ByteArray(16) { it.toByte() }, 120_000u)
                    assertTrue(mtu >= 23u)
                    // Multiple notifications exercise the runtime's bounded queue.
                    repeat(200) { index -> hardware.bleSend(byteArrayOf(if (index == 199) 0 else 1, index.toByte())) }
                    repeat(200) { index ->
                        assertArrayEquals(byteArrayOf(if (index == 199) 0 else 1, index.toByte()), hardware.bleReceive(30_000u))
                    }
                    // Central sends State=0x02 after confirming all frames.
                    try { hardware.bleReceive(30_000u); fail("Termination must fail receive") }
                    catch (error: ReaderException.Failure) { assertTrue(error.details.contains("ended the BLE session")) }
                    Log.i("UsbMdocTest", "round=$round exchange passed mtu=$mtu")
                }
            } finally { hardware.finish() }
            delay(1000)
        }
    }
    companion object { const val SERVICE = "c6f0a001-481e-4fd8-9efd-53736e198a60" }
}

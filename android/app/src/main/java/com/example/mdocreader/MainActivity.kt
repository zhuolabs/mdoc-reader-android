package com.example.mdocreader

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import java.io.FileNotFoundException
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mdocreader.rust.ReaderSession
import com.example.mdocreader.rust.BleHardware
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.mdocreader.theme.MdocReaderTheme

class MainActivity : ComponentActivity() {
    private val state = MutableStateFlow(ReaderState())
    private var nfcHardware: NfcSessionHardware? = null
    private var bleHardware: BleSessionHardware? = null
    private var session: ReaderSession? = null
    private var reading: Job? = null
    private var generation = 0
    private val nfc by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val permissions = BleBackend.permissions
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (permissions.all { result[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startRead()
        else state.value = ReaderState(error = "Nearby devices permission is required. Allow it in app settings and try again.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val current by state.collectAsStateWithLifecycle()
            MdocReaderTheme {
                ReaderScreen(current, ::requestRead, ::cancelRead,
                    { state.value = ReaderState() },
                    ::openConnectionPreferences)
            }
        }
    }
    private fun openConnectionPreferences() {
        val advancedSettings = Intent("com.android.settings.ADVANCED_CONNECTED_DEVICE_SETTINGS")
        startActivity(advancedSettings.takeIf { it.resolveActivity(packageManager) != null }
            ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    private fun requestRead() {
        if (reading?.isCompleted == false) return
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions.launch(permissions); return
        }
        startRead()
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun startRead() {
        if (reading?.isCompleted == false) return
        val adapter = nfc
        if (adapter == null || !adapter.isEnabled) {
            state.value = ReaderState(error = "Turn on NFC. An NFC-capable device is required."); return
        }
        try { BleBackend.checkAvailable(this) } catch (error: Exception) {
            state.value = ReaderState(error = error.message); return
        }
        state.value = ReaderState(busy = true, stage = "certificate_loading")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val thisGeneration = ++generation
        reading = lifecycleScope.launch {
            val eventSink = UiEventSink { event ->
                runOnUiThread { if (generation == thisGeneration && state.value.busy) state.value = state.value.copy(stage = event) }
            }
            val nfcSession = AndroidNfcHardware(this@MainActivity, adapter)
            var bleSession: BleSessionHardware? = null
            nfcHardware = nfcSession
            var native: ReaderSession? = null
            var cancelled = false
            try {
                bleSession = BleBackend.open(this@MainActivity, eventSink)
                bleHardware = bleSession
                nfcSession.start()
                val request = withContext(Dispatchers.IO) { loadRequest() }
                native = bleSession.readerSession(nfcSession, eventSink)
                session = native
                // UniFFI suspend API; Rust schedules the flow and hardware work on Tokio.
                val result = native.read(request)
                val documents = withContext(Dispatchers.Default) { parseDocuments(result) }
                state.value = ReaderState(stage = "complete", documents = documents)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                state.value = ReaderState(error = e.message ?: "Reading failed")
            } catch (e: LinkageError) {
                state.value = ReaderState(error = "Cannot load the Rust library: ${e.message}")
            } finally { withContext(NonCancellable) {
                // Clear the UI's handle before USB cleanup suspends; onStop can
                // otherwise call cancel() on an already closed UniFFI object.
                session = null
                native?.cancel(); native?.close()
                nfcSession.shutdown(); bleSession?.finish()
                nfcHardware = null; bleHardware = null; session = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (cancelled && generation == thisGeneration) state.value = ReaderState(stage = "cancelled")
            } }
        }
    }
    private fun loadRequest(): String {
        for (name in listOf("request.json", "request.example.json")) {
            try {
                return assets.open(name).bufferedReader().use { it.readText() }
            } catch (_: FileNotFoundException) {
                // A local request is optional; the tracked example is the fallback.
            }
        }
        error("No request JSON asset is available")
    }
    private fun cancelRead() {
        session?.cancel()
        nfcHardware?.shutdown()
        bleHardware?.shutdown()
        reading?.cancel()
    }
    override fun onStop() {
        ++generation
        cancelRead()
        state.value = ReaderState()
        super.onStop()
    }
}

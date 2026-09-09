package com.example.mdocreader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import uniffi.nfc_reader.NfcBackend
import uniffi.nfc_reader.NfcBackendException
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** NFC session contract used by MainActivity. Replace this to provide another NFC implementation. */
interface NfcSessionHardware : NfcBackend {
    fun start()
}

/** Android reader-mode and IsoDep implementation of the UniFFI NFC callback interface. */
class AndroidNfcReader(
    private val activity: ComponentActivity,
    private val adapter: NfcAdapter,
) : NfcSessionHardware {
    private val closed = AtomicBoolean(false)
    private val tags = ArrayBlockingQueue<Tag>(1)
    @Volatile private var isoDep: IsoDep? = null

    override fun start() {
        checkOpen()
        adapter.enableReaderMode(
            activity,
            { tag -> if (!closed.get() && isoDep == null) tags.offer(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) },
        )
    }

    override suspend fun connect(timeoutMs: ULong): Boolean = runInterruptible(Dispatchers.IO) { translateErrors {
        val tag = awaitTag(timeoutMs.toLong()) ?: return@translateErrors false
        val connection = IsoDep.get(tag)
            ?: throw NfcBackendException.Failure("This NFC tag does not support IsoDep")
        checkOpen()
        isoDep = connection
        connection.connect()
        connection.timeout = 10_000
        checkOpen()
        true
    } }

    override suspend fun transceive(command: ByteArray): ByteArray = runInterruptible(Dispatchers.IO) { translateErrors {
        (isoDep ?: throw NfcBackendException.Failure("NFC is not connected")).transceive(command)
    } }

    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { isoDep?.close() }
        isoDep = null
        tags.clear()
        runCatching { adapter.disableReaderMode(activity) }
    }

    private fun awaitTag(timeoutMs: Long): Tag? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            checkOpen()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            tags.poll(minOf(remaining, 100), TimeUnit.MILLISECONDS)?.let { return it }
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw NfcBackendException.Failure("Reading cancelled")
    }

    private inline fun <T> translateErrors(block: () -> T): T = try {
        checkOpen()
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: NfcBackendException) {
        throw error
    } catch (error: Exception) {
        throw NfcBackendException.Failure(error.message ?: error.javaClass.simpleName)
    }
}

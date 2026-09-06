package com.example.mdocreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class ReaderState(val busy: Boolean = false, val stage: String = "idle", val error: String? = null,
    val documents: List<ReaderDocument> = emptyList())
data class ReaderDocument(val docType: String, val fields: List<ReaderField>, val errors: String?)
data class ReaderField(val namespace: String, val name: String, val value: String, val image: Bitmap?)

fun parseDocuments(raw: String): List<ReaderDocument> {
    val root = JSONObject(raw)
    val docs = root.getJSONArray("documents")
    return (0 until docs.length()).map { index ->
        val doc = docs.getJSONObject(index)
        val fields = doc.getJSONArray("fields")
        ReaderDocument(doc.getString("docType"), (0 until fields.length()).map { fieldIndex ->
            val field = fields.getJSONObject(fieldIndex)
            val image = field.optString("image").takeIf { it.isNotEmpty() }?.let {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            ReaderField(field.getString("namespace"), field.getString("name"), field.get("value").toString(), image)
        }, listOfNotNull(doc.opt("errors")?.takeUnless { it == JSONObject.NULL }?.toString(),
            root.opt("documentErrors")?.takeUnless { it == JSONObject.NULL }?.toString()).joinToString("\n").ifEmpty { null })
    }
}

private val labels = mapOf("full_name_unicode" to "Full name", "resident_address_unicode" to "Address",
    "local_gov_code_unicode" to "Local government code", "individual_number_unicode" to "Individual number",
    "portrait" to "Portrait", "sex_unicode" to "Sex", "birth_date_unicode" to "Date of birth",
    "sex" to "Sex code", "age_over_20" to "Age over 20", "age_in_years" to "Age", "birth_date" to "Date of birth")

@Composable
fun ReaderScreen(state: ReaderState, onStart: () -> Unit, onCancel: () -> Unit,
    onClear: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
            item {
                Text("MDOC READER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Read a mobile ID", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(BleBackend.description, style = MaterialTheme.typography.bodyLarge)
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (state.error != null) "Unable to complete reading" else when (state.stage) {
                            "usb_permission" -> "Allow access to the USB BLE dongle"
                            "certificate_loading" -> "Loading issuer certificate"
                            "nfc_waiting" -> "Hold the presenting device nearby"
                            "nfc_connected" -> "Setting up NFC handover"
                            "ble_connecting", "ble_advertising" -> "Waiting for Bluetooth connection"
                            "ble_connected" -> "Sending the request"
                            "approval_waiting" -> "Approve sharing on the presenting device"
                            "validating" -> "Verifying the received document"
                            "complete" -> "Document received and authenticated"
                            "cancelled" -> "Reading cancelled"
                            else -> "Ready to read"
                        }, style = MaterialTheme.typography.titleMedium)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                        if (state.stage == "nfc_waiting" || state.stage == "nfc_connected")
                            Text("Start presenting your ID in the wallet. Hold the NFC areas of both devices together until Bluetooth connects.")
                        if (state.busy) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                        else Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(if (state.documents.isEmpty()) "Start reading" else "Read another ID") }
                    }
                }
            }
            if (state.documents.isEmpty() && !state.busy) item {
                Text("Requested information", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Full name, address, individual number, portrait, date of birth, sex, age and local government code. The wallet asks you to approve sharing.")
                Spacer(Modifier.height(12.dp))
                Text("Information stays in memory and is cleared when you leave this screen.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSettings) { Text(BleBackend.settingsLabel) }
            }
            state.documents.forEach { doc ->
                item { Text(doc.docType, style = MaterialTheme.typography.titleSmall) }
                if (doc.errors != null) item { Text("Some items were not returned: ${doc.errors}", color = MaterialTheme.colorScheme.error) }
                items(doc.fields) { field ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(labels[field.name] ?: field.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (field.image != null) Image(field.image.asImageBitmap(), "Portrait", Modifier.fillMaxWidth().heightIn(max = 300.dp))
                            else Text(field.value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (state.documents.isNotEmpty()) item {
                Text("Issuer certificate, signature and device authentication verified. Revocation coverage depends on the information provided by the document.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear results") }
            }
        }
    }
}

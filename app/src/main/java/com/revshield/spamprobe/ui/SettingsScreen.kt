package com.revshield.spamprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val OK = Color(0xFF2E7D32)
private val BAD = Color(0xFFD32F2F)

@Composable
fun SettingsScreen(vm: ProbeViewModel, modifier: Modifier) {
    val url by vm.webhookUrl.collectAsState()
    val savedHeaderName by vm.headerName.collectAsState()
    val savedHeaderValue by vm.headerValue.collectAsState()
    val conn by vm.connection.collectAsState()

    var draft by remember(url) { mutableStateOf(url) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var showHeader by remember { mutableStateOf(savedHeaderName.isNotBlank()) }
    var hName by remember(savedHeaderName) { mutableStateOf(savedHeaderName) }
    var hValue by remember(savedHeaderValue) { mutableStateOf(savedHeaderValue) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Webhook", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Every recorded call is POSTed here as JSON — one call per request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null; saved = null },
                    label = { Text("Webhook URL") },
                    placeholder = { Text("https://webhook.site/<your-id>") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "Must start with https:// — leave empty to hold records on the device.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                saved?.let { Text(it, color = OK, style = MaterialTheme.typography.bodySmall) }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val err = vm.saveUrl(draft.trim())
                        error = err
                        saved = if (err == null) {
                            if (draft.isBlank()) "Cleared. Records will be held as Pending."
                            else "Saved — sending any pending records now."
                        } else null
                    }) { Text("Save") }
                    OutlinedButton(onClick = { vm.testConnection() }) { Text("Test connection") }
                }
                conn?.let {
                    val bad = it.startsWith("OFFLINE") || it.startsWith("REACHED but non-2xx") || it.startsWith("No webhook")
                    Text(it, color = if (bad) BAD else OK, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Authentication (optional)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (savedHeaderName.isBlank()) "No custom header — requests are sent unauthenticated."
                    else "Sending header: $savedHeaderName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showHeader = !showHeader }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (showHeader) "Hide" else "Add a custom header")
                }
                if (showHeader) {
                    OutlinedTextField(
                        value = hName,
                        onValueChange = { hName = it },
                        label = { Text("Header name") },
                        placeholder = { Text("Authorization") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = hValue,
                        onValueChange = { hValue = it },
                        label = { Text("Header value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        vm.saveHeader(hName.trim(), hValue.trim())
                        saved = if (hName.isBlank()) "Custom header cleared." else "Custom header saved."
                    }) { Text("Save header") }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How delivery works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Bullet("A record is marked Synced only after your endpoint returns HTTP 2xx — never on send.")
                Bullet("Anything else is marked Failed with the reason, and retried automatically.")
                Bullet("Records are kept on the device after upload; nothing is deleted.")
                Bullet("With no URL set, calls are still recorded and held as Pending.")
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

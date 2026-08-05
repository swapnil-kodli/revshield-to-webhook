package com.revshield.spamprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
                saved = null
            },
            label = { Text("Webhook URL") },
            placeholder = { Text("https://webhook.site/<your-id>") },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        saved?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val err = vm.saveUrl(draft.trim())
                error = err
                saved = if (err == null) {
                    if (draft.isBlank()) "Cleared — records will be held as Pending until a URL is set."
                    else "Saved. Flushing any pending records to this URL…"
                } else null
            }) { Text("Save") }
            Button(onClick = { vm.testConnection() }) { Text("Test connection") }
        }

        conn?.let {
            val bad = it.startsWith("OFFLINE") || it.startsWith("REACHED but non-2xx") || it.startsWith("No webhook")
            Text(
                it,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TextButton(onClick = { showHeader = !showHeader }) {
            Text(if (showHeader) "Hide optional custom header" else "Add optional custom header (for auth)")
        }
        if (showHeader) {
            OutlinedTextField(
                value = hName,
                onValueChange = { hName = it },
                label = { Text("Header name (optional)") },
                placeholder = { Text("Authorization") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hValue,
                onValueChange = { hValue = it },
                label = { Text("Header value (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                vm.saveHeader(hName.trim(), hValue.trim())
                saved = if (hName.isBlank()) "Custom header cleared." else "Custom header saved."
            }) { Text("Save header") }
        }

        Text(
            "Each captured call is POSTed as JSON to the URL above (one record per request, body unchanged). " +
                "A record only shows Synced after the webhook confirms with HTTP 2xx — never on enqueue. " +
                "With no URL set, records are still captured and held as Pending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

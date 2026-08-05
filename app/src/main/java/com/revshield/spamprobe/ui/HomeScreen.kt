package com.revshield.spamprobe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.SyncState
import com.revshield.spamprobe.presentation.util.AccessibilityUtils

@Composable
fun HomeScreen(vm: ProbeViewModel, modifier: Modifier, onExport: (pendingOnly: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val a11y by vm.accessibilityOn.collectAsState()
    val total by vm.total.collectAsState()
    val pending by vm.pending.collectAsState()
    val synced by vm.synced.collectAsState()
    val failed by vm.failed.collectAsState()
    val records by vm.records.collectAsState()
    val conn by vm.connection.collectAsState()
    val configured by vm.webhookConfigured.collectAsState()
    val url by vm.webhookUrl.collectAsState()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RevShield Probe", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (a11y) "Capture service: ON" else "Capture service: OFF",
                    color = if (a11y) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!a11y) {
                    Text("Enable RevShield Probe in Accessibility to read incoming-call screens.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { AccessibilityUtils.openAccessibilitySettings(ctx) }) { Text("Open Accessibility settings") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Total", total)
            Stat("Pending", pending)
            Stat("Synced", synced)
            Stat("Failed", failed)
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Webhook", style = MaterialTheme.typography.titleMedium)
                if (!configured) {
                    Text(
                        "No webhook URL configured — records are captured and held as Pending. Set one in Settings.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.testConnection() }) { Text("Test connection") }
                }
                conn?.let {
                    val bad = it.startsWith("OFFLINE") || it.startsWith("REACHED but non-2xx") || it.startsWith("No webhook")
                    Text(it, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        records.firstOrNull()?.let { LastCall(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.syncNow() }) { Text("Sync now") }
            Button(onClick = { onExport(true) }) { Text("Export pending") }
            Button(onClick = { onExport(false) }) { Text("Export all") }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("$value", style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LastCall(r: ObservationRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Last call", style = MaterialTheme.typography.titleMedium)
            Text("${r.spamStatus}   ${r.callerNumber ?: ""}")
            Text(r.exactLabelText ?: "no label", style = MaterialTheme.typography.bodySmall)
            val color = when (r.syncState) {
                SyncState.SYNCED -> MaterialTheme.colorScheme.primary
                SyncState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(syncLine(r), color = color, style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun syncLine(r: ObservationRecord): String = when (r.syncState) {
    SyncState.SYNCED -> "Synced ✓"
    SyncState.FAILED -> "Failed: ${r.failReason ?: "unknown"}"
    else -> "Pending…"
}

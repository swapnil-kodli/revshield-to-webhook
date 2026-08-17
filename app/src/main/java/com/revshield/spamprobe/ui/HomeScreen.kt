package com.revshield.spamprobe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.presentation.util.AccessibilityUtils

private val OK = Color(0xFF2E7D32)
private val WARN = Color(0xFFF57C00)
private val BAD = Color(0xFFD32F2F)

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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("RevShield Probe", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ── 1. Is capture actually running? The single most important fact. ──
        StatusBanner(
            ok = a11y,
            title = if (a11y) "Capture service ON" else "Capture service OFF",
            detail = if (a11y) "Reading incoming-call screens." else "Calls are NOT being recorded.",
        )
        if (!a11y) {
            Button(onClick = { AccessibilityUtils.openAccessibilitySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Accessibility settings")
            }
            Text(
                "Enable RevShield Probe there. Note: force-stopping this app switches it back off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 2. Where records go ──
        StatusBanner(
            ok = configured,
            title = if (configured) "Webhook configured" else "No webhook URL set",
            detail = if (configured) url else "Records are captured and held as Pending until you set one in Settings.",
            mono = configured,
            warnNotError = true,
        )
        if (configured) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testConnection() }) { Text("Test connection") }
                Button(onClick = { vm.syncNow() }) { Text("Sync now") }
            }
            conn?.let {
                val bad = it.startsWith("OFFLINE") || it.startsWith("REACHED but non-2xx") || it.startsWith("No webhook")
                Text(it, color = if (bad) BAD else OK, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 3. Counts ──
        Text("Records", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Total", total, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Stat("Pending", pending, if (pending > 0) WARN else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            Stat("Synced", synced, OK, Modifier.weight(1f))
            Stat("Failed", failed, if (failed > 0) BAD else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
        }

        records.firstOrNull()?.let { LastCall(it, configured) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onExport(true) }, modifier = Modifier.weight(1f)) { Text("Export pending") }
            OutlinedButton(onClick = { onExport(false) }, modifier = Modifier.weight(1f)) { Text("Export all") }
        }
    }
}

/** A colour-coded banner with a status dot — readable at arm's length on a shelf. */
@Composable
private fun StatusBanner(
    ok: Boolean,
    title: String,
    detail: String,
    mono: Boolean = false,
    warnNotError: Boolean = false,
) {
    val tint = if (ok) OK else if (warnNotError) WARN else BAD
    Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(12.dp).background(tint, CircleShape))
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = tint, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LastCall(r: ObservationRecord, configured: Boolean) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last call", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(r.spamStatus)
                Text(r.callerNumber ?: "number hidden", style = MaterialTheme.typography.titleMedium)
            }
            r.exactLabelText?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            Text(localDateTime(r.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(syncLine(r, configured), color = syncColor(r), style = MaterialTheme.typography.bodySmall)
        }
    }
}

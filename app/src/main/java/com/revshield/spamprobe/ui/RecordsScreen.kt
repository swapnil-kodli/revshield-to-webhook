package com.revshield.spamprobe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.SyncState
import org.json.JSONObject

@Composable
fun RecordsScreen(vm: ProbeViewModel, modifier: Modifier) {
    val records by vm.records.collectAsState()
    var selected by remember { mutableStateOf<ObservationRecord?>(null) }

    val sel = selected
    if (sel != null) {
        RecordDetail(sel) { selected = null }
        return
    }

    LazyColumn(modifier.padding(8.dp)) {
        if (records.isEmpty()) {
            item { Text("No records yet. Enable capture on Home, then receive a call.", Modifier.padding(16.dp)) }
        }
        items(records) { r ->
            Card(Modifier.fillMaxWidth().padding(4.dp).clickable { selected = r }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${r.spamStatus}   ${r.callerNumber ?: ""}", style = MaterialTheme.typography.titleSmall)
                    Text(r.exactLabelText ?: "no label", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${r.carrier ?: "—"} · ${syncLine(r)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (r.syncState) {
                            SyncState.SYNCED -> MaterialTheme.colorScheme.primary
                            SyncState.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordDetail(r: ObservationRecord, onClose: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Record", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onClose) { Text("Close") }
        }
        Field("id", r.id)
        Field("status", r.spamStatus)
        Field("exact label", r.exactLabelText)
        Field("confidence", r.detectionConfidence)
        Field("caller", r.callerNumber)
        Field("carrier", r.carrier)
        Field("dialer", r.dialerPackage)
        Field("device", "${r.manufacturer} ${r.model} (Android ${r.androidVersion})")
        Field("timestamp", r.timestamp)
        Field("sync", syncLine(r))
        Text("raw accessibility tree", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Card {
            Text(
                pretty(r.rawTree),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall)
    }
}

private fun pretty(json: String): String = try {
    JSONObject(json).toString(2)
} catch (_: Exception) {
    json
}

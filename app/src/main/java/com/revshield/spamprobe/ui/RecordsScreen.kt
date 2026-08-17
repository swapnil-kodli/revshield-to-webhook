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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.revshield.spamprobe.data.ObservationRecord
import org.json.JSONObject

@Composable
fun RecordsScreen(vm: ProbeViewModel, modifier: Modifier) {
    val records by vm.records.collectAsState()
    val configured by vm.webhookConfigured.collectAsState()
    var selected by remember { mutableStateOf<ObservationRecord?>(null) }

    val sel = selected
    if (sel != null) {
        RecordDetail(sel, configured) { selected = null }
        return
    }

    Column(modifier) {
        Text(
            "${records.size} record${if (records.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            if (records.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No calls recorded yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Make sure capture is ON (Home), then receive a call. Each call becomes one record here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            items(records) { r ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = r },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(r.spamStatus)
                            Text(
                                r.callerNumber ?: "number hidden",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        r.exactLabelText?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                localDateTime(r.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(syncLine(r, configured), style = MaterialTheme.typography.bodySmall, color = syncColor(r), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordDetail(r: ObservationRecord, configured: Boolean, onClose: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Record detail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip(r.spamStatus)
                    Text(r.callerNumber ?: "number hidden", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                r.exactLabelText?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
                Text(syncLine(r, configured), color = syncColor(r), style = MaterialTheme.typography.bodyMedium)
            }
        }

        DetailSection("When") {
            Field("captured", localDateTime(r.timestamp))
            Field("captured (UTC)", r.timestamp)
        }
        DetailSection("Detection") {
            Field("verdict", r.spamStatus)
            Field("exact label", r.exactLabelText)
            Field("confidence", r.detectionConfidence)
            Field("dialer", r.dialerPackage)
            Field("carrier", r.carrier)
        }
        DetailSection("Device") {
            Field("model", "${r.manufacturer} ${r.model}")
            Field("android", r.androidVersion)
            Field("app version", r.appVersion)
            Field("record id", r.id)
        }

        Text("Raw accessibility tree", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "The full call screen as captured — kept so verdicts can be re-derived later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(shape = RoundedCornerShape(12.dp)) {
            Text(
                pretty(r.rawTree),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.38f),
        )
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.62f))
    }
}

private fun pretty(json: String): String = try {
    JSONObject(json).toString(2)
} catch (_: Exception) {
    json
}

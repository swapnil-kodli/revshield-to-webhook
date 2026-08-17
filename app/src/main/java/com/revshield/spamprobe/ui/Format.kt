package com.revshield.spamprobe.ui

import androidx.compose.ui.graphics.Color
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.SyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Display helpers. Timestamps are STORED as UTC ISO-8601 and rendered in the device's local zone
 * only here, at display time — never stored local.
 */
private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss")

/** "17 Aug 2026, 14:44:02" in local time; falls back to the raw value if unparseable. */
fun localDateTime(iso: String?): String = render(iso, DATE_TIME)

/** "14:44:02" in local time — for dense list rows. */
fun localTime(iso: String?): String = render(iso, TIME_ONLY)

private fun render(iso: String?, fmt: DateTimeFormatter): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        Instant.parse(iso).atZone(LOCAL_ZONE).format(fmt)
    } catch (_: Exception) {
        iso
    }
}

/** Colour for a spam verdict — severity at a glance. */
fun statusColor(status: String?): Color = when (status?.uppercase()) {
    "SPAM" -> Color(0xFFD32F2F)
    "FRAUD_RISK" -> Color(0xFFB71C1C)
    "SUSPECTED_SPAM" -> Color(0xFFF57C00)
    "UNKNOWN" -> Color(0xFF757575)
    "NONE" -> Color(0xFF2E7D32)
    else -> Color(0xFF757575)
}

/** Short human label for a verdict. */
fun statusLabel(status: String?): String = when (status?.uppercase()) {
    "SUSPECTED_SPAM" -> "SUSPECTED"
    "FRAUD_RISK" -> "FRAUD"
    null, "" -> "—"
    else -> status.uppercase()
}

/** One-line delivery state for a record, honest about what has actually happened. */
fun syncLine(r: ObservationRecord, webhookConfigured: Boolean = true): String = when (r.syncState) {
    SyncState.SYNCED -> "Synced ✓"
    SyncState.FAILED -> "Failed: ${r.failReason?.take(90) ?: "unknown"}"
    else -> if (webhookConfigured) "Pending…" else "Pending — no webhook URL set"
}

fun syncColor(r: ObservationRecord): Color = when (r.syncState) {
    SyncState.SYNCED -> Color(0xFF2E7D32)
    SyncState.FAILED -> Color(0xFFD32F2F)
    else -> Color(0xFF757575)
}

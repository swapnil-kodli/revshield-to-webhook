package com.revshield.spamprobe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Sync-ledger state. SYNCED is reached ONLY on a verified HTTP 2xx from the configured webhook. */
object SyncState {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
}

/**
 * One captured call — the durable outbox row. Append-only, keyed by the per-call session id (dedupe).
 * Records are NEVER deleted on upload; the uploader flips [syncState] to SYNCED (on 2xx) or FAILED
 * (with a visible [failReason]), and keeps retrying non-synced rows until [attempts] hits the cap.
 */
@Entity(tableName = "observations")
data class ObservationRecord(
    @PrimaryKey val id: String,
    val timestamp: String,
    /** Strongest verdict seen from ANY source - used for the in-app display. */
    val spamStatus: String,
    /** Verdict read from the NATIVE dialer UI (the carrier / built-in caller-ID label). */
    val airtelStatus: String = "NONE",
    /** Verdict read from Truecaller's own UI. */
    val truecallerStatus: String = "NONE",
    /** Exactly what the native dialer displayed, e.g. "Airtel Warning: SPAM" or "Sapna Kodliwadmath". */
    val airtelDisplay: String? = null,
    /** Exactly what Truecaller's banner displayed, e.g. "Likely Spam" or "Mana Projects". */
    val truecallerDisplay: String? = null,
    val exactLabelText: String?,
    val detectionConfidence: String?,
    val rawTree: String, // JSON text of the full raw accessibility tree
    val callerNumber: String?,
    val dialerPackage: String?,
    val carrier: String?,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val appVersion: String,
    val createdAt: Long,
    val syncState: String = SyncState.PENDING,
    val failReason: String? = null,
    val attempts: Int = 0,
)

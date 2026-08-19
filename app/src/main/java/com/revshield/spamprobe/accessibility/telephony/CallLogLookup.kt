package com.revshield.spamprobe.accessibility.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log

/**
 * Recovers the caller number for calls where the carrier MASKED it on screen.
 *
 * Airtel replaces the caller-name/number field with its warning ("Airtel Warning: SPAM"), so the
 * incoming-call UI contains no number at all and the probe legitimately captures `phone_number: null`
 * — on exactly the spam calls that matter most. The number IS written to the system call log when the
 * call ends, so after the auto-reject we look it up there and backfill the record.
 *
 * Read-only. Nothing new is uploaded: it only fills the phone_number field the webhook already has.
 */
object CallLogLookup {
    private const val TAG = "RevShield"

    /** Incoming-ish call types — the probe never places outgoing calls. */
    private val INCOMING_TYPES = setOf(
        CallLog.Calls.INCOMING_TYPE,   // 1
        CallLog.Calls.MISSED_TYPE,     // 3
        CallLog.Calls.REJECTED_TYPE,   // 5 — what our own auto-reject produces
        CallLog.Calls.BLOCKED_TYPE,    // 6
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /**
     * The number of the most recent incoming call logged at/after [sinceMs] (minus a small grace
     * window for clock jitter). Returns null when the permission is missing, the log has no matching
     * entry yet, or the number is withheld.
     */
    fun findIncomingNumberSince(context: Context, sinceMs: Long, graceMs: Long = 15_000L): String? {
        if (!hasPermission(context)) {
            Log.w(TAG, "call-log backfill skipped: READ_CALL_LOG not granted")
            return null
        }
        val from = sinceMs - graceMs
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(from.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                val numberCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                while (c.moveToNext()) {
                    val type = if (typeCol >= 0) c.getInt(typeCol) else -1
                    if (type !in INCOMING_TYPES) continue
                    val number = if (numberCol >= 0) c.getString(numberCol) else null
                    if (!number.isNullOrBlank()) return@use number.trim()
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "call-log backfill failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}

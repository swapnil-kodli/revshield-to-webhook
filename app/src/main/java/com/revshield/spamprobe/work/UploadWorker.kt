package com.revshield.spamprobe.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ProbeDatabase
import com.revshield.spamprobe.net.Net
import com.revshield.spamprobe.settings.ProbeSettings

/**
 * Drains not-yet-SYNCED records to the configured WEBHOOK. THE non-negotiable contract:
 *   - no webhook URL configured  -> success (no-op); records stay PENDING, never FAILED, no error spam
 *   - nothing to send            -> success (no-op)
 *   - HTTP 2xx                   -> markSynced (the ONLY path to SYNCED — never on enqueue)
 *   - any non-2xx / exception    -> markFailed(<visible reason>) + Result.retry() (backoff)
 * A record is never dropped or reported as sent when it wasn't; the reason is in the row (shown in the
 * UI) and in logcat under RevShieldNet. WorkManager survives app death; failed rows are retried until a
 * 2xx or the attempts cap.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = ProbeSettings(applicationContext)
        val url = settings.webhookUrl
        if (url.isBlank()) {
            // Unconfigured is a legitimate state — hold the backlog as PENDING and exit cleanly.
            Log.i(Net.TAG, "no webhook URL configured — holding records as PENDING (nothing sent)")
            return Result.success()
        }
        val headerName = settings.headerName
        val headerValue = settings.headerValue
        val dao = ProbeDatabase.get(applicationContext).observations()
        val batch = dao.uploadable(BATCH, MAX_ATTEMPTS)
        if (batch.isEmpty()) return Result.success() // no-op success

        var anyFailure = false
        for (rec in batch) {
            try {
                val res = Net.postObservation(url, ObservationJson.toWire(rec), headerName, headerValue)
                if (res.code in 200..299) {
                    dao.markSynced(rec.id)
                    Log.i(Net.TAG, "SYNCED ${rec.id} (HTTP ${res.code})")
                } else {
                    val reason = "HTTP ${res.code}${if (res.body.isNotBlank()) ": ${res.body}" else ""}"
                    Log.e(Net.TAG, "NOT synced ${rec.id} — $reason")
                    dao.markFailed(rec.id, reason)
                    anyFailure = true
                }
            } catch (e: Exception) {
                val reason = "network: ${e.message ?: e.javaClass.simpleName}"
                Log.w(Net.TAG, "NOT synced ${rec.id} — $reason")
                dao.markFailed(rec.id, reason)
                anyFailure = true
            }
        }
        return if (anyFailure) Result.retry() else Result.success()
    }

    private companion object {
        const val BATCH = 50
        const val MAX_ATTEMPTS = 200
    }
}

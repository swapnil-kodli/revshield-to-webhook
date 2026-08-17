package com.revshield.spamprobe.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.revshield.spamprobe.BuildConfig
import com.revshield.spamprobe.accessibility.CallCaptureService
import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ProbeDatabase
import com.revshield.spamprobe.data.SyncState
import com.revshield.spamprobe.net.Net
import com.revshield.spamprobe.presentation.util.AccessibilityUtils
import com.revshield.spamprobe.settings.ProbeSettings
import org.json.JSONObject

/**
 * Drains not-yet-SYNCED records to the configured WEBHOOK. THE non-negotiable contract:
 *   - no webhook URL configured  -> success (no-op); records stay PENDING, never FAILED, no error spam
 *   - nothing to send            -> success (no-op)
 *   - HTTP 2xx                   -> markSynced (the ONLY path to SYNCED — never on enqueue)
 *   - any non-2xx / exception    -> markFailed(<visible reason>) + Result.retry() (backoff)
 *
 * Draining LOOPS over batches until the outbox is empty (or the time budget runs out), so a backlog
 * built up during an outage clears in one run instead of 50 records per 15-minute tick.
 *
 * It also emits a periodic HEARTBEAT carrying `capture_enabled`, so a probe whose accessibility
 * service was switched off (Android de-registers it on force-stop) is visible at the receiver
 * instead of looking identical to "no spam calls today".
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

        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var anyFailure = false
        var sent = 0
        var rounds = 0

        drain@ while (rounds < MAX_ROUNDS) {
            if (System.currentTimeMillis() > deadline) {
                Log.i(Net.TAG, "time budget reached after $sent uploads — rescheduling to finish the backlog")
                UploadScheduler.syncNow(applicationContext)
                break
            }
            val batch = dao.uploadable(BATCH, MAX_ATTEMPTS)
            if (batch.isEmpty()) break
            rounds++

            for (rec in batch) {
                try {
                    val res = Net.postObservation(url, ObservationJson.toWire(rec), headerName, headerValue)
                    if (res.code in 200..299) {
                        dao.markSynced(rec.id)
                        sent++
                        Log.i(Net.TAG, "SYNCED ${rec.id} (HTTP ${res.code})")
                    } else {
                        val reason = "HTTP ${res.code}${if (res.body.isNotBlank()) ": ${res.body.take(120)}" else ""}"
                        Log.e(Net.TAG, "NOT synced ${rec.id} — $reason")
                        dao.markFailed(rec.id, reason)
                        anyFailure = true
                        break@drain // endpoint is unhealthy; back off rather than hammer it
                    }
                } catch (e: Exception) {
                    val reason = "network: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(Net.TAG, "NOT synced ${rec.id} — $reason")
                    dao.markFailed(rec.id, reason)
                    anyFailure = true
                    break@drain
                }
            }
        }
        if (sent > 0) Log.i(Net.TAG, "drain complete: $sent record(s) delivered in $rounds batch(es)")

        maybeHeartbeat(settings, url, headerName, headerValue, dao)

        return if (anyFailure) Result.retry() else Result.success()
    }

    /**
     * Periodic liveness ping. Carries whether capture is actually ARMED, so the receiver can alert on
     * a probe that is running but no longer recording — and on a probe that has gone silent entirely.
     */
    private suspend fun maybeHeartbeat(
        settings: ProbeSettings,
        url: String,
        headerName: String,
        headerValue: String,
        dao: com.revshield.spamprobe.data.ObservationDao,
    ) {
        val now = System.currentTimeMillis()
        if (now - settings.lastHeartbeatMillis < HEARTBEAT_INTERVAL_MS) return

        val captureEnabled = AccessibilityUtils.isServiceEnabled(applicationContext, CallCaptureService::class.java)
        val body = JSONObject()
            .put("type", "heartbeat")
            .put("sent_at", java.time.Instant.ofEpochMilli(now).toString())
            .put("capture_enabled", captureEnabled)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("manufacturer", Build.MANUFACTURER ?: "unknown")
            .put("model", Build.MODEL ?: "unknown")
            .put("android_version", Build.VERSION.RELEASE ?: "unknown")
            .put("records_total", dao.countAll())
            .put("records_pending", dao.countByState(SyncState.PENDING))
            .put("records_failed", dao.countByState(SyncState.FAILED))
            .toString()
        try {
            val res = Net.postObservation(url, body, headerName, headerValue)
            if (res.code in 200..299) {
                settings.lastHeartbeatMillis = now
                Log.i(Net.TAG, "heartbeat sent (capture_enabled=$captureEnabled)")
            } else {
                Log.w(Net.TAG, "heartbeat not accepted — HTTP ${res.code}")
            }
        } catch (e: Exception) {
            Log.w(Net.TAG, "heartbeat failed — ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val BATCH = 50
        const val MAX_ATTEMPTS = 200
        const val MAX_ROUNDS = 200          // 10k records per run — a backstop, not a real limit
        const val MAX_RUN_MS = 8 * 60 * 1000L // WorkManager allows ~10 min; leave headroom
        const val HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L // hourly
    }
}

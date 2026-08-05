package com.revshield.spamprobe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.revshield.spamprobe.BuildConfig
import com.revshield.spamprobe.accessibility.classifier.SpamLabelClassifier
import com.revshield.spamprobe.accessibility.config.DialerPackages
import com.revshield.spamprobe.accessibility.scraper.AccessibilityNodeScraper
import com.revshield.spamprobe.accessibility.scraper.PhoneNumberExtractor
import com.revshield.spamprobe.accessibility.session.CallSessionTracker
import com.revshield.spamprobe.accessibility.telephony.CarrierResolver
import com.revshield.spamprobe.accessibility.telephony.TelephonyCarrierResolver
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.ProbeDatabase
import com.revshield.spamprobe.observation.RawTreeSerializer
import com.revshield.spamprobe.work.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The probe core. Reads the native incoming-call UI (DialerPackages scoping), collapses the event
 * burst into ONE record per call (CallSessionTracker), captures the FULL raw tree, persists the
 * immutable record, schedules upload, and only THEN hangs up (capture fully, then reject — never
 * before). Reuses the proven pure capture logic unchanged; everything wire/store-related is new.
 */
class CallCaptureService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scraper = AccessibilityNodeScraper()
    private val classifier = SpamLabelClassifier()
    private val sessionTracker = CallSessionTracker()
    private lateinit var carrier: CarrierResolver

    private var finalizeJob: Job? = null
    private var lastRejectedSession: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        carrier = TelephonyCarrierResolver(this)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName
        if (!DialerPackages.isMonitored(pkg, includeThirdParty = false)) return

        val root = rootInActiveWindow ?: event.source ?: return
        val scraped = scraper.collect(root)
        if (scraped.isEmpty()) return

        val result = classifier.classify(scraped)
        val rawTexts = scraped.map { it.text }
        val callerNumber = PhoneNumberExtractor.extract(rawTexts)
        val now = System.currentTimeMillis()

        val obs = sessionTracker.onObservation(
            screenTexts = rawTexts,
            callerNumber = callerNumber,
            label = result.label,
            confidence = result.confidence,
            matchedText = result.matchedText,
            nowMillis = now,
        ) ?: return

        val record = ObservationRecord(
            id = obs.sessionId,
            timestamp = Instant.ofEpochMilli(obs.sessionStartMillis).toString(),
            spamStatus = obs.label.name,
            exactLabelText = obs.matchedText,
            detectionConfidence = obs.confidence.name,
            rawTree = RawTreeSerializer.serialize(root).toString(),
            callerNumber = obs.callerNumber,
            dialerPackage = pkg?.toString(),
            carrier = carrier.currentCarrier(),
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = now,
        )
        // Finalise ~SETTLE_MS after the LAST meaningful change, so a label rendering a beat after the
        // number is captured before we hang up. Each new change reschedules; timer ticks are already
        // filtered upstream (they return null), so they don't keep resetting the timer.
        scheduleFinalize(record)
    }

    private fun scheduleFinalize(record: ObservationRecord) {
        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            delay(SETTLE_MS)
            ProbeDatabase.get(applicationContext).observations().insert(record) // append-only, dedupe by session id
            Log.i(TAG, "captured ${record.id} label=${record.spamStatus} number=${record.callerNumber} pkg=${record.dialerPackage}")
            UploadScheduler.syncNow(applicationContext) // upload on capture
            if (record.id != lastRejectedSession) {
                lastRejectedSession = record.id
                withContext(Dispatchers.Main) { CallRejecter.reject(this@CallCaptureService) }
            }
        }
    }

    override fun onInterrupt() { /* passive reader — nothing to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        sessionTracker.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "capture service destroyed")
    }

    private companion object {
        const val TAG = "RevShield"
        const val SETTLE_MS = 1_800L
    }
}

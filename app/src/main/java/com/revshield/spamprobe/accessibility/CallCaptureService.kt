package com.revshield.spamprobe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.revshield.spamprobe.BuildConfig
import com.revshield.spamprobe.accessibility.classifier.SpamLabelClassifier
import com.revshield.spamprobe.accessibility.config.DialerPackages
import com.revshield.spamprobe.accessibility.scraper.AccessibilityNodeScraper
import com.revshield.spamprobe.accessibility.scraper.CallerLabelExtractor
import com.revshield.spamprobe.accessibility.scraper.PhoneNumberExtractor
import com.revshield.spamprobe.accessibility.session.CallSessionTracker
import com.revshield.spamprobe.accessibility.telephony.CallLogLookup
import com.revshield.spamprobe.accessibility.telephony.CarrierResolver
import com.revshield.spamprobe.accessibility.telephony.TelephonyCarrierResolver
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.ProbeDatabase
import com.revshield.spamprobe.domain.model.SpamLabel
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
 * The probe core. Reads the incoming-call UI, collapses the event burst into ONE record per call,
 * captures the FULL raw tree, persists the immutable record, schedules upload, and only THEN hangs
 * up (capture fully, then reject — never before).
 *
 * TWO sources are read and reported SEPARATELY:
 *   - the NATIVE dialer  -> the carrier / built-in caller-ID verdict  (airtelStatus)
 *   - Truecaller         -> its own crowdsourced verdict              (truecallerStatus)
 * They render in different windows, so each event is attributed to whichever package fired it and
 * the two verdicts accumulate independently for the life of the call.
 */
class CallCaptureService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scraper = AccessibilityNodeScraper()
    private val classifier = SpamLabelClassifier()
    private val sessionTracker = CallSessionTracker()
    // NOT lateinit: an event arriving before onServiceConnected would throw and Android
    // permanently unbinds a crashed accessibility service. Resolved lazily, safely.
    private var carrier: CarrierResolver? = null

    private var finalizeJob: Job? = null
    private var lastRejectedSession: String? = null

    // Per-call, per-source verdicts. Reset whenever a new session id appears.
    private var currentSession: String? = null
    // Hard deadline for THIS call: capture is debounced, but never past this point, so the
    // auto-reject always fires even while events keep streaming in from two packages.
    private var sessionDeadline = 0L
    private var airtelLabel = SpamLabel.NONE
    private var airtelText: String? = null
    private var truecallerLabel = SpamLabel.NONE
    private var truecallerText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        carrier = runCatching { TelephonyCarrierResolver(this) }.getOrNull()
        Log.i(TAG, "capture service connected - watching native dialers AND Truecaller")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A throw here would make Android mark the service CRASHED and never rebind it —
        // the probe would look installed but capture nothing, forever. Never let that happen.
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "capture error (swallowed to keep the service alive): ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        ) {
            return
        }

        val pkg = event.packageName
        val thirdParty = DialerPackages.isThirdParty(pkg)
        // A heads-up call BANNER is drawn by SystemUI, not the dialer. Treat it as a carrier surface:
        // the banner carries the same caller name / carrier warning the full screen would show.
        val banner = DialerPackages.isCallBanner(pkg)
        val native = DialerPackages.isNative(pkg) || banner
        if (!native && !thirdParty) return

        // A dialer/caller-ID package being on screen does NOT mean a call is happening: Truecaller's
        // after-call popup and profile screens also contain phone numbers, and would otherwise be
        // recorded as phantom "calls". Only capture while the phone is actually ringing or connected.
        if (!callInProgress()) {
            Log.d(TAG, "ignoring $pkg - no call in progress (audio mode=${audioMode()})")
            return
        }

        // CRITICAL: only ever read the window that BELONGS to the event's package. rootInActiveWindow
        // returns whatever is in the foreground, which may be a completely unrelated app (including
        // RevShield's own Records screen) — scraping that invents callers and verdicts out of other
        // apps' text. Verified on-device: our own UI was once captured as a FRAUD_RISK "call".
        val root = windowRootFor(pkg, event)
        if (root == null) {
            Log.d(TAG, "event from $pkg but no window belonging to it (foreground is another app)")
            return
        }
        val scraped = scraper.collect(root)
        if (scraped.isEmpty()) {
            Log.d(TAG, "event from $pkg but scraper found NO text nodes")
            return
        }

        val result = classifier.classify(scraped)
        val rawTexts = scraped.map { it.text }
        val displayed = CallerLabelExtractor.extract(scraped, result.matchedText)
        val callerNumber = PhoneNumberExtractor.extract(rawTexts)
        val now = System.currentTimeMillis()

        Log.d(
            TAG,
            "event pkg=$pkg mode=${audioMode()} nodes=${scraped.size} number=$callerNumber label=${result.label} " +
                "texts=${rawTexts.filter { it.isNotBlank() }.take(8)}",
        )

        // Feed the tracker so burst-collapse / session detection stays exactly as proven.
        val obs = sessionTracker.onObservation(
            screenTexts = rawTexts,
            callerNumber = callerNumber,
            label = result.label,
            confidence = result.confidence,
            matchedText = result.matchedText,
            nowMillis = now,
        ) ?: run {
            Log.d(TAG, "tracker emitted nothing (no number AND no label yet, or unchanged since last snapshot)")
            return
        }

        // New call ⇒ forget the previous call's per-source verdicts.
        if (obs.sessionId != currentSession) {
            currentSession = obs.sessionId
            sessionDeadline = now + MAX_CAPTURE_MS
            airtelLabel = SpamLabel.NONE
            airtelText = null
            truecallerLabel = SpamLabel.NONE
            truecallerText = null
        }

        // Record what each source DISPLAYED, spam or not — the webhook reports it verbatim after the
        // verdict ("NOT SPAM | Mana Projects"). First non-chrome text wins; later blanks never erase it.
        if (!displayed.isNullOrBlank()) {
            if (native && airtelText == null) airtelText = displayed
            if (thirdParty && truecallerText == null) truecallerText = displayed
        }

        // Attribute this event's verdict to the package that produced it, keeping the strongest.
        if (result.label != SpamLabel.NONE) {
            if (native && severity(result.label) > severity(airtelLabel)) {
                airtelLabel = result.label
                if (!result.matchedText.isNullOrBlank()) airtelText = result.matchedText
                Log.i(TAG, "carrier verdict from $pkg${if (banner) " (banner)" else ""}: ${result.label} [${result.matchedText}]")
            } else if (thirdParty && severity(result.label) > severity(truecallerLabel)) {
                truecallerLabel = result.label
                if (!result.matchedText.isNullOrBlank()) truecallerText = result.matchedText
                Log.i(TAG, "truecaller verdict from $pkg: ${result.label} [${result.matchedText}]")
            }
        }

        val record = ObservationRecord(
            id = obs.sessionId,
            timestamp = Instant.ofEpochMilli(obs.sessionStartMillis).toString(),
            spamStatus = obs.label.name,
            airtelStatus = airtelLabel.name,
            truecallerStatus = truecallerLabel.name,
            airtelDisplay = airtelText,
            truecallerDisplay = truecallerText,
            exactLabelText = airtelText ?: truecallerText ?: obs.matchedText,
            detectionConfidence = obs.confidence.name,
            rawTree = RawTreeSerializer.serialize(root).toString(),
            callerNumber = obs.callerNumber,
            dialerPackage = pkg?.toString(),
            carrier = runCatching { carrierResolver()?.currentCarrier() }.getOrNull(),
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = now,
        )
        // Finalise ~SETTLE_MS after the LAST meaningful change, so a label rendering a beat after the
        // number - or Truecaller's overlay arriving late - is captured before we hang up. BUT never
        // later than the per-call deadline: with the dialer AND Truecaller both emitting events, a
        // pure debounce can be reset forever and the call would never be auto-rejected.
        val remaining = (sessionDeadline - now).coerceAtLeast(0L)
        scheduleFinalize(record, minOf(SETTLE_MS, remaining))
    }

    /**
     * The root node of a window owned by [pkg]. Checks the event's own source first, then every
     * window the service can see, and finally rootInActiveWindow ONLY if it really belongs to [pkg].
     * Never returns this app's own window.
     */
    /**
     * When the carrier masked the number on screen, recover it from the call log. Polled briefly
     * because the entry is written asynchronously as the call tears down.
     */
    private suspend fun backfillMaskedNumber(record: ObservationRecord) {
        if (record.callerNumber != null) return
        if (!CallLogLookup.hasPermission(applicationContext)) return
        repeat(BACKFILL_TRIES) { attempt ->
            delay(BACKFILL_INTERVAL_MS)
            val number = CallLogLookup.findIncomingNumberSince(applicationContext, record.createdAt)
            if (!number.isNullOrBlank()) {
                runCatching {
                    ProbeDatabase.get(applicationContext).observations().backfillCallerNumber(record.id, number)
                }.onSuccess {
                    Log.i(TAG, "backfilled masked number for ${record.id}: $number (attempt ${attempt + 1})")
                }.onFailure { Log.w(TAG, "backfill write failed: ${it.message}") }
                return
            }
        }
        Log.w(TAG, "call-log backfill found no number for ${record.id} - phone_number stays null")
    }

    private fun windowRootFor(pkg: CharSequence?, event: AccessibilityEvent): AccessibilityNodeInfo? {
        val want = pkg?.toString() ?: return null
        if (want == packageName) return null // never scrape ourselves

        event.source?.let { src ->
            if (src.packageName?.toString() == want) return src
        }
        runCatching {
            windows?.forEach { w ->
                val r = w.root
                if (r != null && r.packageName?.toString() == want) return r
            }
        }
        rootInActiveWindow?.let { r ->
            if (r.packageName?.toString() == want) return r
        }
        return null
    }

    private fun carrierResolver(): CarrierResolver? {
        carrier?.let { return it }
        val c = runCatching { TelephonyCarrierResolver(this) }.getOrNull()
        carrier = c
        return c
    }

    private fun audioMode(): Int =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode ?: -1

    /** True while the phone is ringing or on a call. Permission-free, unlike the telephony APIs. */
    private fun callInProgress(): Boolean = when (audioMode()) {
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        -1 -> true // AudioManager unavailable: fail OPEN so a real call is never silently missed
        else -> false
    }

    private fun severity(label: SpamLabel): Int = when (label) {
        SpamLabel.FRAUD_RISK -> 4
        SpamLabel.SPAM -> 3
        SpamLabel.SUSPECTED_SPAM -> 2
        SpamLabel.UNKNOWN -> 1
        SpamLabel.NONE -> 0
    }

    private fun scheduleFinalize(record: ObservationRecord, settleMs: Long) {
        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            try {
            delay(settleMs)
            runCatching {
                ProbeDatabase.get(applicationContext).observations().insert(record) // append-only, dedupe by session id
            }.onFailure { Log.e(TAG, "insert failed (still rejecting the call): ${it.message}") }
            Log.i(
                TAG,
                "captured ${record.id} airtel=${record.airtelStatus} truecaller=${record.truecallerStatus} " +
                    "number=${record.callerNumber} pkg=${record.dialerPackage}",
            )
            // Reject FIRST: the system writes the call-log entry when the call ends, so the number
            // the carrier masked on screen only becomes available after we hang up.
            if (record.id != lastRejectedSession) {
                lastRejectedSession = record.id
                withContext(Dispatchers.Main) { CallRejecter.reject(this@CallCaptureService) }
            }
            backfillMaskedNumber(record)
            UploadScheduler.syncNow(applicationContext) // upload once the record is complete
            } catch (t: Throwable) {
                Log.e(TAG, "finalize failed: ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    override fun onInterrupt() { /* passive reader — nothing to interrupt */ }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        sessionTracker.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        scope.cancel()
        Log.i(TAG, "capture service destroyed")
    }

    companion object {
        /**
         * True only while Android actually has this service BOUND. The settings entry is not proof:
         * after MIUI kills the process, `enabled_accessibility_services` still lists us while Android
         * has flagged the service crashed and refuses to rebind. The watchdog checks this flag.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        const val TAG = "RevShield"
        const val SETTLE_MS = 1_800L
        // Upper bound from the first event of a call to finalise+reject. Ring lasts ~25s, so this is
        // comfortably early while still allowing a late carrier/Truecaller label to land.
        const val MAX_CAPTURE_MS = 3_500L
        const val BACKFILL_TRIES = 6
        const val BACKFILL_INTERVAL_MS = 700L
    }
}

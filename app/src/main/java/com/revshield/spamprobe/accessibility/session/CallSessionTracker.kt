package com.revshield.spamprobe.accessibility.session

import com.revshield.spamprobe.domain.model.DetectionConfidence
import com.revshield.spamprobe.domain.model.SpamLabel
import java.util.UUID

/** Accumulated, de-duplicated snapshot of one incoming-call session, ready to persist. */
data class CallObservation(
    val sessionId: String,
    val callerNumber: String?,
    val label: SpamLabel,
    val confidence: DetectionConfidence,
    val matchedText: String?,
    val sessionStartMillis: Long,
    /** Raw text scraped from the call screen at the moment of this snapshot (diagnostic). */
    val screenText: String?,
)

/**
 * Collapses the many accessibility events fired during a single call into one enriched record.
 *
 * A call screen emits a burst of events (window opened, timer ticks, label loading). Instead of
 * writing a row per event, this tracker keeps per-session state and re-emits a [CallObservation]
 * only when the persisted snapshot would actually change: a caller number appears, a stronger spam
 * label appears, or the set of on-screen texts changes (ignoring the call-duration timer, so ticks
 * don't cause churn). That last trigger matters for diagnostics — if a label renders a beat after
 * the number, the screen-text change re-persists the row so the label is captured even when our
 * classifier doesn't (yet) recognise it.
 *
 * A gap longer than [sessionGapMillis] between events starts a new session (a new call). An
 * observation is emitted only once there is a caller number OR a real spam label, so merely opening
 * the dialer records nothing. Thread-safe, since events may arrive on the main thread.
 */
class CallSessionTracker(
    private val sessionGapMillis: Long = 8_000L,
    private val maxScreenTextChars: Int = 2_000,
) {
    private var sessionId: String? = null
    private var sessionStartMillis = 0L
    private var lastEventMillis = 0L
    private var bestNumber: String? = null
    private var strongestLabel = SpamLabel.NONE
    private var strongestConfidence = DetectionConfidence.LOW
    private var strongestMatched: String? = null
    private var persistedNumber: String? = null
    private var persistedLabel: SpamLabel? = null
    private var persistedSignature: Set<String> = emptySet()

    @Synchronized
    fun onObservation(
        screenTexts: List<String>,
        callerNumber: String?,
        label: SpamLabel,
        confidence: DetectionConfidence,
        matchedText: String?,
        nowMillis: Long,
    ): CallObservation? {
        val isNewSession = sessionId == null || (nowMillis - lastEventMillis) > sessionGapMillis
        if (isNewSession) startSession(nowMillis)
        lastEventMillis = nowMillis

        if (callerNumber != null && bestNumber == null) bestNumber = callerNumber
        if (severity(label) > severity(strongestLabel)) {
            strongestLabel = label
            strongestConfidence = confidence
            strongestMatched = matchedText
        }

        // Nothing worth storing yet (e.g. a dialer window with no number and no spam label).
        if (bestNumber == null && strongestLabel == SpamLabel.NONE) return null

        val signature = screenTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isTimerLike(it) }
            .toSortedSet()

        val changed = bestNumber != persistedNumber ||
            strongestLabel != persistedLabel ||
            signature != persistedSignature
        if (!changed) return null

        persistedNumber = bestNumber
        persistedLabel = strongestLabel
        persistedSignature = signature

        return CallObservation(
            sessionId = requireNotNull(sessionId),
            callerNumber = bestNumber,
            label = strongestLabel,
            confidence = strongestConfidence,
            matchedText = strongestMatched,
            sessionStartMillis = sessionStartMillis,
            screenText = screenTexts.joinToString(separator = " | ").take(maxScreenTextChars),
        )
    }

    @Synchronized
    fun reset() {
        sessionId = null
        sessionStartMillis = 0L
        lastEventMillis = 0L
        bestNumber = null
        strongestLabel = SpamLabel.NONE
        strongestConfidence = DetectionConfidence.LOW
        strongestMatched = null
        persistedNumber = null
        persistedLabel = null
        persistedSignature = emptySet()
    }

    private fun startSession(nowMillis: Long) {
        sessionId = UUID.randomUUID().toString()
        sessionStartMillis = nowMillis
        bestNumber = null
        strongestLabel = SpamLabel.NONE
        strongestConfidence = DetectionConfidence.LOW
        strongestMatched = null
        persistedNumber = null
        persistedLabel = null
        persistedSignature = emptySet()
    }

    private fun severity(label: SpamLabel): Int = when (label) {
        SpamLabel.FRAUD_RISK -> 4
        SpamLabel.SPAM -> 3
        SpamLabel.SUSPECTED_SPAM -> 2
        SpamLabel.UNKNOWN -> 1
        SpamLabel.NONE -> 0
    }

    private fun isTimerLike(text: String): Boolean = TIMER_REGEX.matches(text)

    private companion object {
        // Call-duration readouts like "0:07", "00:07", "1:02:33".
        val TIMER_REGEX = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    }
}

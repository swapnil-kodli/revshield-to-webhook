package com.revshield.spamprobe

import com.revshield.spamprobe.accessibility.classifier.SpamLabelClassifier
import com.revshield.spamprobe.accessibility.scraper.PhoneNumberExtractor
import com.revshield.spamprobe.accessibility.scraper.ScrapedText
import com.revshield.spamprobe.accessibility.session.CallSessionTracker
import com.revshield.spamprobe.domain.model.DetectionConfidence
import com.revshield.spamprobe.domain.model.SpamLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates real incoming-call screens against the pure capture logic, so detection can be verified
 * without a live SIM. Each screen mirrors the node shapes seen on real dialers: a caller-name view,
 * a number view, and the spam ACTION BUTTONS that must never be mistaken for a carrier verdict.
 */
class CaptureSimulationTest {

    private val classifier = SpamLabelClassifier()

    private fun label(text: String) = ScrapedText(text, isClickable = false, className = "android.widget.TextView")
    private fun button(text: String) = ScrapedText(text, isClickable = true, className = "android.widget.Button")

    // ── carrier verdicts ────────────────────────────────────────────────────────

    @Test
    fun `airtel injects the verdict into the caller name`() {
        // The real-world Airtel shape: the label rides inside the caller-name heading.
        val screen = listOf(label("Airtel Warning: SPAM"), label("+919812345678"), button("Decline"), button("Answer"))
        val r = classifier.classify(screen)
        assertEquals(SpamLabel.SPAM, r.label)
        assertEquals(DetectionConfidence.MEDIUM, r.confidence) // keyword inside a short heading
        assertEquals("Airtel Warning: SPAM", r.matchedText)
    }

    @Test
    fun `a dedicated label view scores HIGH`() {
        val screen = listOf(label("Spam"), label("+919812345678"), button("Decline"))
        val r = classifier.classify(screen)
        assertEquals(SpamLabel.SPAM, r.label)
        assertEquals(DetectionConfidence.HIGH, r.confidence) // the whole text IS the label
    }

    @Test
    fun `specific phrases beat the generic ones`() {
        assertEquals(SpamLabel.SUSPECTED_SPAM, classifier.classify(listOf(label("Suspected spam"))).label)
        assertEquals(SpamLabel.SUSPECTED_SPAM, classifier.classify(listOf(label("Likely spam"))).label)
        assertEquals(SpamLabel.FRAUD_RISK, classifier.classify(listOf(label("Fraud risk"))).label)
        assertEquals(SpamLabel.UNKNOWN, classifier.classify(listOf(label("Unknown caller"))).label)
    }

    @Test
    fun `edge punctuation does not defeat the exact match`() {
        val r = classifier.classify(listOf(label("Spam!")))
        assertEquals(SpamLabel.SPAM, r.label)
        assertEquals(DetectionConfidence.HIGH, r.confidence)
    }

    // ── false-positive guards (the expensive kind of bug) ───────────────────────

    @Test
    fun `spam ACTION BUTTONS never produce a verdict`() {
        val screen = listOf(
            label("Priya Sharma"), label("+919812345678"),
            button("Report spam"), button("Block & report spam"), button("Not spam"),
        )
        assertEquals(SpamLabel.NONE, classifier.classify(screen).label)
    }

    @Test
    fun `a question is not a verdict`() {
        assertEquals(SpamLabel.NONE, classifier.classify(listOf(label("Is this spam?"))).label)
    }

    @Test
    fun `a company name containing spam as a substring is not a verdict`() {
        // Word-boundary matching: "Spammy" must not match "spam".
        assertEquals(SpamLabel.NONE, classifier.classify(listOf(label("Spammy Corp Ltd"))).label)
    }

    @Test
    fun `a long notice mentioning spam is not a verdict`() {
        val notice = "This call may be recorded for quality purposes and reported as spam by other users " +
            "if you choose to do so from the call log afterwards"
        assertTrue(notice.length > 80) // beyond the heading length cap
        assertEquals(SpamLabel.NONE, classifier.classify(listOf(label(notice))).label)
    }

    @Test
    fun `a clean call yields NONE`() {
        val screen = listOf(label("Priya Sharma"), label("+919812345678"), button("Decline"), button("Answer"))
        assertEquals(SpamLabel.NONE, classifier.classify(screen).label)
    }

    // ── caller number extraction ────────────────────────────────────────────────

    @Test
    fun `number is recovered from assorted on-screen formats`() {
        assertEquals("+919812345678", PhoneNumberExtractor.extract(listOf("+91 98123 45678")))
        assertEquals("08012345678", PhoneNumberExtractor.extract(listOf("080-1234-5678")))
        assertNull(PhoneNumberExtractor.extract(listOf("Priya Sharma")))
        assertNull(PhoneNumberExtractor.extract(listOf("12:34"))) // call timer, not a number
    }

    @Test
    fun `carrier-masked call has a label but no number`() {
        // Airtel replaces the number with the warning — a valid record with caller_number absent.
        val screen = listOf("Airtel Warning: SPAM", "Incoming call")
        assertNull(PhoneNumberExtractor.extract(screen))
        assertEquals(SpamLabel.SPAM, classifier.classify(screen.map { label(it) }).label)
    }

    // ── session collapse: one record per call, not one per event ────────────────

    @Test
    fun `an event burst collapses into a single enriched record`() {
        val t = CallSessionTracker()
        var emitted = 0
        var last: com.revshield.spamprobe.accessibility.session.CallObservation? = null

        // A realistic burst: window opens, number appears, label renders late, timer ticks.
        val frames = listOf(
            Triple(listOf("Incoming call"), null as String?, SpamLabel.NONE),
            Triple(listOf("Incoming call", "+919812345678"), "+919812345678", SpamLabel.NONE),
            Triple(listOf("Airtel Warning: SPAM", "+919812345678"), "+919812345678", SpamLabel.SPAM),
            Triple(listOf("Airtel Warning: SPAM", "+919812345678", "0:03"), "+919812345678", SpamLabel.SPAM),
            Triple(listOf("Airtel Warning: SPAM", "+919812345678", "0:04"), "+919812345678", SpamLabel.SPAM),
        )
        var now = 1_000L
        for ((texts, number, lbl) in frames) {
            val o = t.onObservation(texts, number, lbl, DetectionConfidence.MEDIUM, if (lbl == SpamLabel.SPAM) "Airtel Warning: SPAM" else null, now)
            if (o != null) { emitted++; last = o }
            now += 500
        }
        assertNotNull(last)
        // Timer-only ticks must not churn out new rows.
        assertTrue("expected few emissions for one call, got $emitted", emitted <= 3)
        assertEquals(SpamLabel.SPAM, last!!.label)
        assertEquals("+919812345678", last.callerNumber)
    }

    @Test
    fun `all frames of one call share a session id, and a gap starts a new call`() {
        val t = CallSessionTracker()
        val a = t.onObservation(listOf("+919812345678"), "+919812345678", SpamLabel.NONE, DetectionConfidence.LOW, null, 1_000L)
        val b = t.onObservation(listOf("Spam", "+919812345678"), "+919812345678", SpamLabel.SPAM, DetectionConfidence.HIGH, "Spam", 2_000L)
        assertEquals("same call ⇒ same id", a!!.sessionId, b!!.sessionId)

        // >8s later is a different call.
        val c = t.onObservation(listOf("+919899999999"), "+919899999999", SpamLabel.NONE, DetectionConfidence.LOW, null, 20_000L)
        assertTrue("new call ⇒ new id", c!!.sessionId != a.sessionId)
    }

    @Test
    fun `an idle dialer with no number and no label records nothing`() {
        val t = CallSessionTracker()
        assertNull(t.onObservation(listOf("Keypad", "Recents"), null, SpamLabel.NONE, DetectionConfidence.LOW, null, 1_000L))
    }

    @Test
    fun `the strongest label of the call wins`() {
        val t = CallSessionTracker()
        t.onObservation(listOf("Unknown caller", "+919812345678"), "+919812345678", SpamLabel.UNKNOWN, DetectionConfidence.MEDIUM, "Unknown caller", 1_000L)
        val out = t.onObservation(listOf("Fraud risk", "+919812345678"), "+919812345678", SpamLabel.FRAUD_RISK, DetectionConfidence.HIGH, "Fraud risk", 1_500L)
        assertEquals(SpamLabel.FRAUD_RISK, out!!.label)
    }
}

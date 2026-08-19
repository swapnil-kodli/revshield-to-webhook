package com.revshield.spamprobe

import com.revshield.spamprobe.accessibility.classifier.SpamLabelClassifier
import com.revshield.spamprobe.accessibility.config.DialerPackages
import com.revshield.spamprobe.accessibility.scraper.PhoneNumberExtractor
import com.revshield.spamprobe.accessibility.scraper.ScrapedText
import com.revshield.spamprobe.accessibility.session.CallSessionTracker
import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.domain.model.SpamLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN end-to-end call simulations. Each replays a realistic sequence of accessibility events from the
 * NATIVE dialer and/or TRUECALLER, runs them through the same classifier + session tracker the live
 * service uses, attributes each verdict to its source exactly as CallCaptureService does, and asserts
 * the resulting FOUR-FIELD webhook payload.
 *
 * Screens are taken from what the probe actually logged on-device (Google Phone on the Xiaomi and the
 * AOSP dialer on the LAVA), plus Truecaller's overlay shapes.
 */
class TenCallSimulationTest {

    private val classifier = SpamLabelClassifier()

    private fun t(text: String) = ScrapedText(text, isClickable = false, className = "android.widget.TextView")
    private fun b(text: String) = ScrapedText(text, isClickable = true, className = "android.widget.Button")

    /** One accessibility event: which package drew it, and what was on screen. */
    private data class Frame(val pkg: String, val texts: List<ScrapedText>)

    private data class Outcome(val payload: JSONObject, val emissions: Int)

    /**
     * Mirrors CallCaptureService.handleEvent: per-source attribution, strongest verdict wins,
     * burst-collapse via the tracker, then the four-field payload.
     */
    private fun runCall(frames: List<Frame>, startMs: Long = 1_000L, stepMs: Long = 300L): Outcome {
        val tracker = CallSessionTracker()
        var airtel = SpamLabel.NONE
        var truecaller = SpamLabel.NONE
        var airtelText: String? = null
        var truecallerText: String? = null
        var session: String? = null
        var sessionStart = startMs
        var number: String? = null
        var emissions = 0
        var now = startMs

        for (f in frames) {
            val native = DialerPackages.isNative(f.pkg)
            val third = DialerPackages.isThirdParty(f.pkg)
            require(native || third) { "test frame uses an unmonitored package: ${f.pkg}" }

            val result = classifier.classify(f.texts)
            val raw = f.texts.map { it.text }
            val obs = tracker.onObservation(raw, PhoneNumberExtractor.extract(raw), result.label, result.confidence, result.matchedText, now)
            now += stepMs
            if (obs == null) continue
            emissions++

            if (obs.sessionId != session) {
                session = obs.sessionId; sessionStart = obs.sessionStartMillis
                airtel = SpamLabel.NONE; truecaller = SpamLabel.NONE; airtelText = null; truecallerText = null
            }
            if (result.label != SpamLabel.NONE) {
                if (native && sev(result.label) > sev(airtel)) { airtel = result.label; airtelText = result.matchedText }
                else if (third && sev(result.label) > sev(truecaller)) { truecaller = result.label; truecallerText = result.matchedText }
            }
            number = obs.callerNumber
        }

        val rec = ObservationRecord(
            id = session ?: "none",
            timestamp = java.time.Instant.ofEpochMilli(sessionStart).toString(),
            spamStatus = maxOf(airtel, truecaller, compareBy { sev(it) }).name,
            airtelStatus = airtel.name,
            truecallerStatus = truecaller.name,
            exactLabelText = airtelText ?: truecallerText,
            detectionConfidence = "MEDIUM", rawTree = "{}", callerNumber = number,
            dialerPackage = frames.lastOrNull()?.pkg, carrier = "airtel", androidVersion = "14",
            manufacturer = "Xiaomi", model = "2107113SI", appVersion = "1.0.0", createdAt = sessionStart,
        )
        return Outcome(JSONObject(ObservationJson.toWire(rec)), emissions)
    }

    private fun sev(l: SpamLabel) = when (l) {
        SpamLabel.FRAUD_RISK -> 4; SpamLabel.SPAM -> 3; SpamLabel.SUSPECTED_SPAM -> 2
        SpamLabel.UNKNOWN -> 1; SpamLabel.NONE -> 0
    }

    private fun assertFourFields(o: JSONObject) =
        assertEquals(setOf("phone_number", "airtel_status", "call_received_time", "truecaller_status"), o.keys().asSequence().toSet())

    // ── real screen shapes ──────────────────────────────────────────────────────
    private fun googlePhone(vararg extra: String) = Frame(
        "com.google.android.dialer",
        listOf(t("+91 79658 54235"), t("India")) + extra.map { t(it) } + listOf(b("Message"), b("Decline"), b("Answer")),
    )
    private fun truecallerOverlay(vararg extra: String) = Frame(
        "com.truecaller",
        extra.map { t(it) } + listOf(t("+91 79658 54235"), b("Block"), b("Answer")),
    )

    // ── 1..10 ───────────────────────────────────────────────────────────────────

    @Test
    fun `sim 01 - Airtel flags SPAM, Truecaller silent`() {
        val o = runCall(listOf(googlePhone(), googlePhone("Airtel Warning: SPAM"), googlePhone("Airtel Warning: SPAM"))).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("NOT SPAM"))
        assertEquals("+917965854235", o.getString("phone_number"))
    }

    @Test
    fun `sim 02 - Truecaller flags SPAM, carrier silent`() {
        val o = runCall(listOf(googlePhone(), truecallerOverlay("Spam"), truecallerOverlay("Spam"))).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("NOT SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("SPAM"))
    }

    @Test
    fun `sim 03 - BOTH sources flag, reported independently`() {
        val o = runCall(listOf(googlePhone("Airtel Warning: SPAM"), truecallerOverlay("Fraud risk"))).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("SPAM"))
    }

    @Test
    fun `sim 04 - clean call from a real person`() {
        val o = runCall(listOf(googlePhone("Priya Sharma"), googlePhone("Priya Sharma"))).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("NOT SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("NOT SPAM"))
    }

    @Test
    fun `sim 05 - carrier masks the number behind the label`() {
        // Airtel replaces the caller number with its warning: number must be null, still 4 fields.
        val masked = Frame("com.google.android.dialer", listOf(t("Airtel Warning: SPAM"), t("Incoming call"), b("Decline"), b("Answer")))
        val o = runCall(listOf(masked, masked)).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
        assertTrue("number must be null when masked", o.isNull("phone_number"))
    }

    @Test
    fun `sim 06 - late Truecaller overlay still lands on the same call`() {
        // number first, carrier label second, Truecaller arriving last — one record, both verdicts.
        val o = runCall(listOf(googlePhone(), googlePhone("Airtel Warning: SPAM"), truecallerOverlay("Suspected spam"))).payload
        assertFourFields(o)
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("SPAM"))
    }

    @Test
    fun `sim 07 - spam ACTION BUTTONS on both surfaces never fake a verdict`() {
        val dialer = Frame("com.google.android.dialer", listOf(t("Priya Sharma"), t("+91 79658 54235"), b("Report spam"), b("Block & report spam")))
        val tc = Frame("com.truecaller", listOf(t("Priya Sharma"), b("Not spam"), b("Is this spam?"), b("Block")))
        val o = runCall(listOf(dialer, tc)).payload
        assertTrue(o.getString("airtel_status").startsWith("NOT SPAM"))
        assertTrue(o.getString("truecaller_status").startsWith("NOT SPAM"))
    }

    @Test
    fun `sim 08 - a 200-event burst from ONE window collapses to a handful`() {
        val frames = (1..200).map { googlePhone("Airtel Warning: SPAM") }
        val out = runCall(frames, stepMs = 40L) // 40ms apart = one continuous call
        assertFourFields(out.payload)
        assertTrue(out.payload.getString("airtel_status").startsWith("SPAM"))
        assertTrue("repeated identical screens must collapse, got ${out.emissions}", out.emissions <= 6)
    }

    @Test
    fun `sim 08b - ALTERNATING dialer-Truecaller windows emit per event (why the reject deadline exists)`() {
        // Each window swap genuinely changes the on-screen signature, so the tracker re-emits. That is
        // correct, but it means the finalise debounce would be reset forever and the call would never
        // be auto-rejected — hence CallCaptureService caps it with MAX_CAPTURE_MS.
        val frames = (1..200).map { if (it % 2 == 0) googlePhone("Airtel Warning: SPAM") else truecallerOverlay("Spam") }
        val out = runCall(frames, stepMs = 40L)
        assertFourFields(out.payload)
        assertTrue(out.payload.getString("airtel_status").startsWith("SPAM"))
        assertTrue(out.payload.getString("truecaller_status").startsWith("SPAM"))
        assertTrue("alternating windows re-emit; that is the debounce-reset hazard", out.emissions > 50)
    }

    @Test
    fun `sim 09 - strongest verdict per source wins as severity escalates`() {
        val o = runCall(listOf(
            googlePhone("Unknown caller"),        // UNKNOWN
            googlePhone("Suspected spam"),        // -> SUSPECTED_SPAM
            googlePhone("Airtel Warning: SPAM"),  // -> SPAM
            googlePhone("Unknown caller"),        // must NOT downgrade
        )).payload
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
    }

    @Test
    fun `sim 10 - two back-to-back calls do not leak verdicts`() {
        // First call is SPAM; after an 8s+ gap a clean call must report NONE for both sources.
        val tracker = CallSessionTracker()
        val spam = classifier.classify(listOf(t("Airtel Warning: SPAM"), t("+91 79658 54235")))
        val first = tracker.onObservation(listOf("Airtel Warning: SPAM", "+91 79658 54235"), "+917965854235", spam.label, spam.confidence, spam.matchedText, 1_000L)
        val clean = classifier.classify(listOf(t("Priya Sharma"), t("+91 90000 00000")))
        val second = tracker.onObservation(listOf("Priya Sharma", "+91 90000 00000"), "+919000000000", clean.label, clean.confidence, clean.matchedText, 30_000L)
        assertTrue("second call must be a new session", second!!.sessionId != first!!.sessionId)
        assertEquals(SpamLabel.SPAM, first.label)
        assertEquals(SpamLabel.NONE, second.label)
        assertNull(second.matchedText)
    }

    @Test
    fun `sim 11 - REGRESSION - our own Records screen must never look like a call`() {
        // Observed on-device 2026-08-19: an event from com.truecaller arrived while RevShield's own
        // Records screen was foreground. rootInActiveWindow returned OUR window, so the probe scraped
        // its own UI, pulled a number out of a test row and matched "FRAUD" from its own list —
        // inventing a FRAUD_RISK "call". The service now only reads a window owned by the event's
        // package. This asserts the text itself is the hazard, so the guard is what saves us.
        val ourOwnUi = listOf(
            t("120 records"), t("NONE"), t("number hidden"), t("Pending…"), t("UNKNOWN"),
            t("+919800000118"), t("19 Aug 2026, 20:28:58"), t("FRAUD_RISK"),
        )
        val verdict = classifier.classify(ourOwnUi)
        // Our own UI genuinely classifies as spam-ish — proving a package guard, not text filtering,
        // is the only correct defence.
        assertTrue("our UI text is inherently spam-like", verdict.label != SpamLabel.NONE)
        assertEquals("+919800000118", PhoneNumberExtractor.extract(ourOwnUi.map { it.text }))
    }
}

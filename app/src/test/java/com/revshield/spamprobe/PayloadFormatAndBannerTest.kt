package com.revshield.spamprobe

import com.revshield.spamprobe.accessibility.config.DialerPackages
import com.revshield.spamprobe.accessibility.scraper.CallerLabelExtractor
import com.revshield.spamprobe.accessibility.scraper.ScrapedText
import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ObservationRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reporting format: "STATUS | what the source displayed", plus heads-up BANNER coverage. */
class PayloadFormatAndBannerTest {

    private fun t(s: String) = ScrapedText(s, isClickable = false, className = "android.widget.TextView")
    private fun b(s: String) = ScrapedText(s, isClickable = true, className = "android.widget.Button")

    private fun rec(
        number: String? = "+917965854235",
        airtel: String = "NONE", airtelDisp: String? = null,
        tc: String = "NONE", tcDisp: String? = null,
        ts: String = "2026-08-19T13:53:05.286Z",
    ) = ObservationRecord(
        id = "x", timestamp = ts, spamStatus = airtel, airtelStatus = airtel, truecallerStatus = tc,
        airtelDisplay = airtelDisp, truecallerDisplay = tcDisp, exactLabelText = airtelDisp,
        detectionConfidence = "MEDIUM", rawTree = "{}", callerNumber = number,
        dialerPackage = "com.google.android.dialer", carrier = "airtel", androidVersion = "14",
        manufacturer = "Xiaomi", model = "2107113SI", appVersion = "1.0.0", createdAt = 1L,
    )

    // ── the requested output shape ──────────────────────────────────────────────

    @Test
    fun `spam carries the verdict AND the carrier wording`() {
        val o = JSONObject(ObservationJson.toWire(rec(airtel = "SPAM", airtelDisp = "Airtel Warning: SPAM")))
        assertEquals("SPAM | Airtel Warning: SPAM", o.getString("airtel_status"))
    }

    @Test
    fun `truecaller spam and truecaller business name both come through`() {
        val spam = JSONObject(ObservationJson.toWire(rec(tc = "SUSPECTED_SPAM", tcDisp = "Likely Spam")))
        assertEquals("SPAM | Likely Spam", spam.getString("truecaller_status"))

        val named = JSONObject(ObservationJson.toWire(rec(tc = "NONE", tcDisp = "Mana Projects")))
        assertEquals("NOT SPAM | Mana Projects", named.getString("truecaller_status"))
    }

    @Test
    fun `every spam severity collapses to SPAM, everything else to NOT SPAM`() {
        for (v in listOf("SPAM", "SUSPECTED_SPAM", "FRAUD_RISK")) {
            assertTrue(JSONObject(ObservationJson.toWire(rec(airtel = v))).getString("airtel_status").startsWith("SPAM"))
        }
        for (v in listOf("UNKNOWN", "NONE")) {
            assertTrue(JSONObject(ObservationJson.toWire(rec(airtel = v))).getString("airtel_status").startsWith("NOT SPAM"))
        }
    }

    @Test
    fun `a source that displayed nothing reports the bare verdict`() {
        val o = JSONObject(ObservationJson.toWire(rec(tc = "NONE", tcDisp = null)))
        assertEquals("NOT SPAM", o.getString("truecaller_status"))
    }

    @Test
    fun `time is a local 12-hour clock, not UTC ISO`() {
        val t = JSONObject(ObservationJson.toWire(rec())).getString("call_received_time")
        assertTrue("expected hh:mm am/pm, got '$t'", t.matches(Regex("""\d{2}:\d{2} (am|pm)""")))
        assertTrue("must not be ISO", !t.contains("T") && !t.endsWith("Z"))
    }

    @Test
    fun `still exactly four keys`() {
        val o = JSONObject(ObservationJson.toWire(rec(airtel = "SPAM", airtelDisp = "Airtel Warning: SPAM", tc = "NONE", tcDisp = "Mana Projects")))
        assertEquals(setOf("phone_number", "airtel_status", "call_received_time", "truecaller_status"), o.keys().asSequence().toSet())
    }

    // ── what each surface displays ──────────────────────────────────────────────

    @Test
    fun `extractor picks the carrier warning off a real Airtel full-screen call`() {
        val screen = listOf(
            t("Swipe up to answer"), t("Swipe up with two fingers to answer"), t("Swipe down to decline"),
            t("Call from"), t("Airtel Warning: SPAM"), t("Spam call"), t("Suspected junk caller"), b("Message"),
        )
        assertEquals("Airtel Warning: SPAM", CallerLabelExtractor.extract(screen, "Airtel Warning: SPAM"))
        assertEquals("Airtel Warning: SPAM", CallerLabelExtractor.extract(screen, null))
    }

    @Test
    fun `extractor picks the contact name off a normal call`() {
        val screen = listOf(
            t("Swipe up to answer"), t("Call from"), t("Sapna Kodliwadmath"), t("+91 96325 45909"), b("Message"),
        )
        assertEquals("Sapna Kodliwadmath", CallerLabelExtractor.extract(screen, null))
    }

    @Test
    fun `extractor picks Truecaller's banner text, skipping its chrome`() {
        val screen = listOf(
            t("Missed call 17 mins ago"), t("Likely Spam"), t("Ahemdabad Local India"),
            t("TATA Indicom · 079 6585 4235"), t("Drag to move Caller ID"),
        )
        assertEquals("Likely Spam", CallerLabelExtractor.extract(screen, null))
    }

    @Test
    fun `extractor ignores buttons, timers and bare numbers`() {
        val screen = listOf(b("Answer"), b("Decline"), t("0:07"), t("+91 96325 45909"), t("Mana Projects"))
        assertEquals("Mana Projects", CallerLabelExtractor.extract(screen, null))
    }

    // ── heads-up banner coverage ────────────────────────────────────────────────

    @Test
    fun `SystemUI is monitored so banner-style calls are captured too`() {
        // When the phone is unlocked and in use, the call arrives as a heads-up notification drawn
        // by SystemUI rather than a full-screen dialer window. Without this it is missed entirely.
        assertTrue("systemui must count as a call surface", DialerPackages.isCallBanner("com.android.systemui"))
        assertTrue(DialerPackages.isCallBanner("com.miui.systemui.plugin"))
        assertTrue("unrelated apps must not", !DialerPackages.isCallBanner("com.whatsapp"))
        assertTrue("and must not be treated as a dialer", !DialerPackages.isNative("com.android.systemui"))
    }

    @Test
    fun `a banner-shaped screen still yields the carrier warning`() {
        // A heads-up call notification: far fewer nodes than the full screen.
        val banner = listOf(t("Airtel Warning: SPAM"), t("Incoming call"), b("Answer"), b("Decline"))
        assertEquals("Airtel Warning: SPAM", CallerLabelExtractor.extract(banner, null))
    }
}

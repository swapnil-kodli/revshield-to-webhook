package com.revshield.spamprobe

import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.settings.ProbeSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire contract a receiver depends on: URL validation, and the EXACT four-field payload. */
class WebhookContractTest {

    // ── URL validation (what Settings accepts on Save) ──────────────────────────

    @Test
    fun `blank is allowed — it is the unconfigured state`() {
        assertNull(ProbeSettings.validationError(""))
        assertNull(ProbeSettings.validationError("   "))
    }

    @Test
    fun `https urls are accepted`() {
        assertNull(ProbeSettings.validationError("https://webhook.site/abc-123"))
        assertNull(ProbeSettings.validationError("https://api.example.com/v1/ingest?src=probe"))
    }

    @Test
    fun `http and malformed urls are rejected with a reason`() {
        assertNotNull(ProbeSettings.validationError("http://example.com"))
        assertNotNull(ProbeSettings.validationError("example.com"))
        assertNotNull(ProbeSettings.validationError("https://"))
        assertNotNull(ProbeSettings.validationError("not a url at all"))
        assertTrue(ProbeSettings.validationError("http://example.com")!!.contains("https"))
    }

    // ── the emitted payload: EXACTLY four fields ────────────────────────────────

    private fun record(
        number: String? = "+919812345678",
        airtel: String = "SPAM",
        truecaller: String = "SPAM",
        time: String = "2026-08-17T09:14:22.317Z",
    ) = ObservationRecord(
        id = "6f9b2c1e-6f2a-4b7d-9d3e-3f0f1c2a7b55",
        timestamp = time,
        spamStatus = airtel,
        airtelStatus = airtel,
        truecallerStatus = truecaller,
        exactLabelText = "Airtel Warning: SPAM",
        airtelDisplay = "Airtel Warning: SPAM",
        truecallerDisplay = "Likely Spam",
        detectionConfidence = "MEDIUM",
        rawTree = """{"className":"android.widget.FrameLayout","clickable":false,"children":[]}""",
        callerNumber = number,
        dialerPackage = "com.android.dialer",
        carrier = "airtel",
        androidVersion = "15",
        manufacturer = "LAVA",
        model = "LAVA LZG412",
        appVersion = "1.0.0",
        createdAt = 1_755_421_000_000L,
    )

    @Test
    fun `payload contains exactly the four required fields and nothing else`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        val keys = o.keys().asSequence().toSet()
        assertEquals(setOf("phone_number", "airtel_status", "call_received_time", "truecaller_status"), keys)
        assertEquals(4, keys.size)
    }

    @Test
    fun `the four fields carry the right values`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        assertEquals("+919812345678", o.getString("phone_number"))
        assertTrue(o.getString("airtel_status").startsWith("SPAM"))
        assertTrue("carrier wording must be preserved", o.getString("airtel_status").contains("Airtel Warning: SPAM"))
        assertTrue("time must be a 12-hour clock", o.getString("call_received_time").matches(Regex("""\d{2}:\d{2} (am|pm)""")))
        assertTrue(o.getString("truecaller_status").startsWith("SPAM"))
    }

    @Test
    fun `nothing from the old payload leaks through`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        for (gone in listOf(
            "id", "observed_at", "timestamp", "spam_status", "raw_accessibility_tree",
            "exact_label_text", "detection_confidence", "caller_number", "dialer_package",
            "carrier", "manufacturer", "model", "android_version", "app_version",
        )) {
            assertTrue("field '$gone' must no longer be sent", !o.has(gone))
        }
    }

    @Test
    fun `the two verdicts are reported INDEPENDENTLY`() {
        // Truecaller can flag a call the carrier did not, and vice versa — both must survive.
        val onlyTruecaller = JSONObject(ObservationJson.toWire(record(airtel = "NONE", truecaller = "SPAM")))
        assertTrue(onlyTruecaller.getString("airtel_status").startsWith("NOT SPAM"))
        assertTrue(onlyTruecaller.getString("truecaller_status").startsWith("SPAM"))

        val onlyAirtel = JSONObject(ObservationJson.toWire(record(airtel = "FRAUD_RISK", truecaller = "NONE")))
        assertTrue("fraud counts as spam", onlyAirtel.getString("airtel_status").startsWith("SPAM"))
        assertTrue(onlyAirtel.getString("truecaller_status").startsWith("NOT SPAM"))
    }

    @Test
    fun `a carrier-masked call sends phone_number as null, keeping four fields`() {
        val o = JSONObject(ObservationJson.toWire(record(number = null)))
        assertTrue("phone_number must still be present", o.has("phone_number"))
        assertTrue("and explicitly null", o.isNull("phone_number"))
        assertEquals(4, o.keys().asSequence().toSet().size)
    }

    @Test
    fun `call_received_time is a local 12-hour clock`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        val t = o.getString("call_received_time")
        assertTrue("expected hh:mm am/pm, got '$t'", t.matches(Regex("""\d{2}:\d{2} (am|pm)""")))
    }

    @Test
    fun `payload stays valid json for every verdict value`() {
        for (v in listOf("SPAM", "SUSPECTED_SPAM", "FRAUD_RISK", "UNKNOWN", "NONE")) {
            val expected = if (v in listOf("SPAM", "SUSPECTED_SPAM", "FRAUD_RISK")) "SPAM" else "NOT SPAM"
            val o = JSONObject(ObservationJson.toWire(record(airtel = v, truecaller = v)))
            assertTrue("$v -> $expected", o.getString("airtel_status").startsWith(expected))
            assertTrue("$v -> $expected", o.getString("truecaller_status").startsWith(expected))
        }
    }
}

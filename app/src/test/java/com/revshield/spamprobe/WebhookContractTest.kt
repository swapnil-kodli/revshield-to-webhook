package com.revshield.spamprobe

import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.settings.ProbeSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire contract a receiver depends on: URL validation, and the exact JSON emitted per record. */
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
        assertNotNull(ProbeSettings.validationError("http://example.com"))   // must be TLS
        assertNotNull(ProbeSettings.validationError("example.com"))          // no scheme
        assertNotNull(ProbeSettings.validationError("https://"))             // no host
        assertNotNull(ProbeSettings.validationError("not a url at all"))
        assertTrue(ProbeSettings.validationError("http://example.com")!!.contains("https"))
    }

    // ── the emitted payload ─────────────────────────────────────────────────────

    private fun record(
        status: String = "SPAM",
        label: String? = "Airtel Warning: SPAM",
        number: String? = "+919812345678",
        carrier: String? = "airtel",
    ) = ObservationRecord(
        id = "6f9b2c1e-6f2a-4b7d-9d3e-3f0f1c2a7b55",
        timestamp = "2026-08-17T09:14:22.317Z",
        spamStatus = status,
        exactLabelText = label,
        detectionConfidence = "MEDIUM",
        rawTree = """{"className":"android.widget.FrameLayout","clickable":false,"children":[]}""",
        callerNumber = number,
        dialerPackage = "com.android.dialer",
        carrier = carrier,
        androidVersion = "15",
        manufacturer = "LAVA",
        model = "LAVA LZG412",
        appVersion = "1.0.0",
        createdAt = 1_755_421_000_000L,
    )

    @Test
    fun `a labelled call emits every documented field`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        assertEquals("6f9b2c1e-6f2a-4b7d-9d3e-3f0f1c2a7b55", o.getString("id"))
        assertEquals("2026-08-17T09:14:22.317Z", o.getString("observed_at"))
        assertEquals("SPAM", o.getString("spam_status"))
        assertEquals("Airtel Warning: SPAM", o.getString("exact_label_text"))
        assertEquals("MEDIUM", o.getString("detection_confidence"))
        assertEquals("+919812345678", o.getString("caller_number"))
        assertEquals("com.android.dialer", o.getString("dialer_package"))
        assertEquals("airtel", o.getString("carrier"))
        assertEquals("15", o.getString("android_version"))
        assertEquals("LAVA", o.getString("manufacturer"))
        assertEquals("1.0.0", o.getString("app_version"))
        // the raw tree must arrive as a nested OBJECT, not a string
        assertTrue(o.get("raw_accessibility_tree") is JSONObject)
    }

    @Test
    fun `observed_at and timestamp carry the same value and are UTC`() {
        val o = JSONObject(ObservationJson.toWire(record()))
        assertEquals(o.getString("observed_at"), o.getString("timestamp"))
        assertTrue("must be UTC ISO-8601", o.getString("observed_at").endsWith("Z"))
    }

    @Test
    fun `absent fields are OMITTED, never null — the carrier-masked case`() {
        val o = JSONObject(ObservationJson.toWire(record(number = null, carrier = null)))
        assertFalse("caller_number must be absent, not null", o.has("caller_number"))
        assertFalse("carrier must be absent, not null", o.has("carrier"))
        assertTrue(o.has("id")) // required fields still present
    }

    @Test
    fun `a clean call omits the label`() {
        val o = JSONObject(ObservationJson.toWire(record(status = "NONE", label = null)))
        assertEquals("NONE", o.getString("spam_status"))
        assertFalse("exact_label_text must be absent when nothing matched", o.has("exact_label_text"))
    }

    @Test
    fun `payload is valid json even with quotes and unicode in the label`() {
        val nasty = """Airtel "Warning": SPAM — ₹ ／ 垃圾邮件"""
        val o = JSONObject(ObservationJson.toWire(record(label = nasty)))
        assertEquals(nasty, o.getString("exact_label_text"))
    }
}

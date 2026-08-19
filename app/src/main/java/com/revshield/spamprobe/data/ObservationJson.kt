package com.revshield.spamprobe.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The observation wire body POSTed to the webhook — exactly FOUR fields:
 *
 *   phone_number        the number that called the probe
 *   airtel_status       "SPAM | Airtel Warning: SPAM"  /  "NOT SPAM | Sapna Kodliwadmath"
 *   call_received_time  local 12-hour clock, e.g. "07:23 pm"
 *   truecaller_status   "SPAM | Likely Spam"           /  "NOT SPAM | Mana Projects"
 *
 * Each status is a plain SPAM / NOT SPAM verdict, followed by whatever that source actually put on
 * screen so the raw wording is preserved. Nothing else is sent.
 */
object ObservationJson {

    /** Any spam-ish verdict collapses to SPAM; a clean or merely unidentified caller is NOT SPAM. */
    private fun verdict(status: String?): String = when (status?.uppercase()) {
        "SPAM", "SUSPECTED_SPAM", "FRAUD_RISK" -> "SPAM"
        else -> "NOT SPAM"
    }

    /** "SPAM | Airtel Warning: SPAM", or just "NOT SPAM" when the source displayed nothing useful. */
    private fun field(status: String?, display: String?): String {
        val v = verdict(status)
        val d = display?.trim()
        return if (d.isNullOrEmpty()) v else "$v | $d"
    }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)

    /** UTC ISO -> local 12-hour clock, e.g. "07:23 pm". Falls back to the raw value if unparseable. */
    internal fun localClock(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).format(CLOCK).lowercase(Locale.US)
        } catch (_: Exception) {
            iso
        }
    }

    fun toWire(r: ObservationRecord): String =
        JSONObject()
            .put("phone_number", r.callerNumber ?: JSONObject.NULL)
            .put("airtel_status", field(r.airtelStatus, r.airtelDisplay))
            .put("call_received_time", localClock(r.timestamp))
            .put("truecaller_status", field(r.truecallerStatus, r.truecallerDisplay))
            .toString()
}

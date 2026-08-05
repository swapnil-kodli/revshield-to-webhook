package com.revshield.spamprobe.data

import org.json.JSONObject

/** The observation wire body POSTed to the webhook (snake_case; raw tree nested). Sent UNCHANGED. */
object ObservationJson {
    fun toWire(r: ObservationRecord): String {
        val o = JSONObject()
            .put("id", r.id)
            // observed_at = the phone's capture time, UTC ISO 8601 (Instant.toString() is always UTC 'Z').
            // `timestamp` is kept as a legacy alias so older Control Center builds still read the capture time.
            .put("observed_at", r.timestamp)
            .put("timestamp", r.timestamp)
            .put("spam_status", r.spamStatus)
            .put("raw_accessibility_tree", JSONObject(r.rawTree))
            .put("android_version", r.androidVersion)
            .put("manufacturer", r.manufacturer)
            .put("model", r.model)
            .put("app_version", r.appVersion)
        r.exactLabelText?.let { o.put("exact_label_text", it) }
        r.detectionConfidence?.let { o.put("detection_confidence", it) }
        r.callerNumber?.let { o.put("caller_number", it) }
        r.dialerPackage?.let { o.put("dialer_package", it) }
        r.carrier?.let { o.put("carrier", it) }
        return o.toString()
    }
}

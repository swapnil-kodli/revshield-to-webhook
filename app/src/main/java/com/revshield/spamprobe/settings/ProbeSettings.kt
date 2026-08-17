package com.revshield.spamprobe.settings

import android.content.Context

/**
 * The probe is STANDALONE — it needs only internet access. The one thing the operator configures is
 * the WEBHOOK URL each observation is POSTed to (e.g. a webhook.site URL for testing, or a permanent
 * production endpoint). Empty is the DEFAULT, valid, unconfigured state: the probe still captures and
 * stores records, holding them PENDING until a URL is saved.
 *
 * An optional single custom header (name + value) is available for future auth; default none.
 */
class ProbeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    var headerName: String
        get() = prefs.getString(KEY_HDR_NAME, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_HDR_NAME, value.trim()).apply()
        }

    var headerValue: String
        get() = prefs.getString(KEY_HDR_VALUE, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_HDR_VALUE, value.trim()).apply()
        }

    /** When the last liveness heartbeat was accepted (epoch millis; 0 = never). */
    var lastHeartbeatMillis: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_HEARTBEAT, value).apply()
        }

    /** True once a webhook URL is set. When false, records are held PENDING (never FAILED). */
    fun isConfigured(): Boolean = webhookUrl.isNotBlank()

    companion object {
        const val PREFS = "revshield-probe"
        private const val KEY_URL = "webhook_url"
        private const val KEY_HDR_NAME = "webhook_header_name"
        private const val KEY_HDR_VALUE = "webhook_header_value"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat_ms"

        /**
         * Validate a URL the operator is trying to SAVE.
         * @return null when acceptable (blank = allowed, the unconfigured state), else the reason to show.
         */
        fun validationError(raw: String): String? {
            val v = raw.trim()
            if (v.isEmpty()) return null // unconfigured is a legitimate state
            val parsed = try {
                java.net.URI(v)
            } catch (e: Exception) {
                return "Not a valid URL (${e.message ?: "malformed"})"
            }
            val scheme = parsed.scheme?.lowercase()
            if (scheme.isNullOrEmpty()) return "Missing scheme — the URL must start with https://"
            if (scheme != "https") return "Must be https:// (got \"$scheme://\")"
            if (parsed.host.isNullOrBlank()) return "Missing host — e.g. https://webhook.site/<your-id>"
            return null
        }
    }
}

package com.revshield.spamprobe.domain.model

/**
 * A single incoming-call observation.
 *
 * [id] is a per-call session id: all events for one call collapse into one record with this id,
 * so the log shows one row per call (enriched as the number / label become available), not one
 * row per accessibility event.
 *
 * Framework-free (no Android types) so it can be created, stored, and tested in isolation.
 */
data class CallDetection(
    val id: String,
    /** Epoch milliseconds at which the call was first observed. */
    val timestampMillis: Long,
    /** Caller number if the call UI exposed it; null for withheld/contact-name-only calls. */
    val callerNumber: String?,
    /** The network/SIM carrier on the observing device (whose labelling we are reading). */
    val carrier: String?,
    /** What the phone app appears to have labelled the call as ([SpamLabel.NONE] = no label). */
    val label: SpamLabel,
    /** How strongly the label was matched. */
    val confidence: DetectionConfidence,
    /** Which extraction path produced this observation. */
    val source: DetectionSource,
    /** Manufacturer + model of the observing device, for cross-device comparison. */
    val deviceModel: String,
    /** Package name of the phone/dialer app that rendered the call UI. */
    val packageName: String?,
    /** The exact on-screen text that triggered a spam match, for auditing/debugging. */
    val matchedText: String?,
    /** Diagnostic: the raw text scraped from the call screen, so we can see exactly what the
     *  dialer exposed (was a label present but unmatched, or absent entirely?). Local-only. */
    val screenText: String?,
)

/** The label a phone app may show for an incoming call. [NONE] means no spam indicator found. */
enum class SpamLabel { SPAM, SUSPECTED_SPAM, FRAUD_RISK, UNKNOWN, NONE }

/** Confidence in a match, derived from how the on-screen text matched a known label phrase. */
enum class DetectionConfidence { HIGH, MEDIUM, LOW }

/** The pipeline stage that produced a detection. [OCR] is reserved for a future fallback. */
enum class DetectionSource { ACCESSIBILITY_TREE, OCR }

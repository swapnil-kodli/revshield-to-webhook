package com.revshield.spamprobe.accessibility.classifier

import com.revshield.spamprobe.domain.model.SpamLabel

/**
 * A single rule mapping an on-screen phrase to a [SpamLabel].
 *
 * Rules are business configuration, not hardcoded logic: the phrases carriers/dialers display
 * vary by vendor, region, and language, and are expected to grow. Order matters — more specific
 * phrases must precede more general ones (e.g. "suspected spam" before "spam") so the label we
 * report matches what the screen actually says.
 */
data class SpamKeywordRule(
    val phrase: String,
    val label: SpamLabel,
) {
    companion object {
        /**
         * Default English-language ruleset, ordered from most specific to most general.
         * Extend this list (or supply a custom one to [SpamLabelClassifier]) to cover more
         * vendors or locales. Matching is case-insensitive.
         */
        val DEFAULT: List<SpamKeywordRule> = listOf(
            SpamKeywordRule("suspected spam", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("spam suspected", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("likely spam", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("spam likely", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("possible spam", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("spam risk", SpamLabel.SUSPECTED_SPAM),
            SpamKeywordRule("fraud risk", SpamLabel.FRAUD_RISK),
            SpamKeywordRule("fraud suspected", SpamLabel.FRAUD_RISK),
            SpamKeywordRule("suspected fraud", SpamLabel.FRAUD_RISK),
            SpamKeywordRule("fraud", SpamLabel.FRAUD_RISK),
            SpamKeywordRule("scam likely", SpamLabel.SPAM),
            SpamKeywordRule("scam", SpamLabel.SPAM),
            SpamKeywordRule("telemarketer", SpamLabel.SPAM),
            SpamKeywordRule("spam", SpamLabel.SPAM),
            SpamKeywordRule("unknown caller", SpamLabel.UNKNOWN),
            SpamKeywordRule("no caller id", SpamLabel.UNKNOWN),
            SpamKeywordRule("private number", SpamLabel.UNKNOWN),
            SpamKeywordRule("unknown", SpamLabel.UNKNOWN),
        )
    }
}

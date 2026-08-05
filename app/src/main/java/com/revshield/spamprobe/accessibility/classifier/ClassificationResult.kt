package com.revshield.spamprobe.accessibility.classifier

import com.revshield.spamprobe.domain.model.DetectionConfidence
import com.revshield.spamprobe.domain.model.SpamLabel

/** Output of [SpamLabelClassifier]: the matched label, how strong the match was, and the text. */
data class ClassificationResult(
    val label: SpamLabel,
    val confidence: DetectionConfidence,
    val matchedText: String?,
) {
    companion object {
        /** Convenience for "no label found in the given text". */
        val NONE = ClassificationResult(SpamLabel.NONE, DetectionConfidence.LOW, null)
    }
}

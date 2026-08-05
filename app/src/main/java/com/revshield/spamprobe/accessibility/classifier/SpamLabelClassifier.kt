package com.revshield.spamprobe.accessibility.classifier

import com.revshield.spamprobe.accessibility.scraper.ScrapedText
import com.revshield.spamprobe.domain.model.DetectionConfidence

/**
 * Classifies on-screen texts into a spam label.
 *
 * Real-world carriers surface the verdict in different shapes. Airtel, for example, injects it into
 * the caller *name* ("Airtel Warning: SPAM") rather than a dedicated badge. So matching must catch a
 * keyword embedded in a heading — but without re-introducing the false positives that spam *action
 * buttons* cause ("Report spam", "Block & report spam", "Not spam", "Is this spam?"). The strategy:
 *
 *  1. Drop clickable nodes and button-typed views (actions, not labels).
 *  2. Drop action/question text ("report", "block", "not spam", "is this…?", …).
 *  3. Match against what survives:
 *       - HIGH   — the node's whole text IS the label (a dedicated label view), e.g. "Spam".
 *       - MEDIUM — a label keyword appears as a *whole word* in a short caller-name/heading,
 *                  e.g. "Airtel Warning: SPAM". Word boundaries avoid partials like "Spammy Corp";
 *                  a length cap avoids matching long notices/paragraphs.
 *
 * Pure and framework-free, so it is fully unit-tested and reusable by a future OCR path.
 */
class SpamLabelClassifier(
    private val rules: List<SpamKeywordRule> = SpamKeywordRule.DEFAULT,
    private val maxLabelLength: Int = 80,
) {

    // Pre-compiled whole-word matchers, preserving the rules' specific-to-general order.
    private val wordMatchers: List<Pair<Regex, com.revshield.spamprobe.domain.model.SpamLabel>> =
        rules.map { Regex("\\b" + Regex.escape(it.phrase) + "\\b", RegexOption.IGNORE_CASE) to it.label }

    fun classify(texts: List<ScrapedText>): ClassificationResult {
        val candidates = texts.asSequence()
            .filterNot { it.isClickable }
            .filterNot { (it.className ?: "").contains("Button", ignoreCase = true) }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.isActionOrQuestion() }
            .toList()

        if (candidates.isEmpty()) return ClassificationResult.NONE

        // Pass 1: whole text equals the label (dedicated label view) -> HIGH.
        for (rule in rules) {
            val exact = candidates.firstOrNull { it.stripEdgePunctuation().equals(rule.phrase, ignoreCase = true) }
            if (exact != null) return ClassificationResult(rule.label, DetectionConfidence.HIGH, exact)
        }

        // Pass 2: keyword as a whole word inside a short caller-name/heading -> MEDIUM.
        val shortCandidates = candidates.filter { it.length <= maxLabelLength }
        for ((regex, label) in wordMatchers) {
            val hit = shortCandidates.firstOrNull { regex.containsMatchIn(it) }
            if (hit != null) return ClassificationResult(label, DetectionConfidence.MEDIUM, hit)
        }

        return ClassificationResult.NONE
    }

    private fun String.isActionOrQuestion(): Boolean {
        if (contains('?')) return true
        val lower = lowercase()
        return ACTION_TOKENS.any { lower.contains(it) }
    }

    private fun String.stripEdgePunctuation(): String = trim().trim(*EDGE_PUNCTUATION).trim()

    private companion object {
        // Words that indicate an action or question, i.e. UI chrome rather than a caller label.
        val ACTION_TOKENS = listOf(
            "report", "block", "unblock", "mark", "flag", "not spam", "is this", "add ", "save", "search",
        )
        val EDGE_PUNCTUATION = charArrayOf('.', '!', '·', '•', '-', '–', '—', ':', '|', ' ')
    }
}

package com.revshield.spamprobe.accessibility.scraper

/**
 * Pulls out what a call surface actually DISPLAYS for the caller — the carrier's warning, or the
 * caller-ID name.
 *
 * The webhook reports each source as `STATUS | <what it displayed>`, e.g.
 *   "SPAM | Airtel Warning: SPAM"      (native dialer, carrier label)
 *   "SPAM | Likely Spam"               (Truecaller banner)
 *   "NOT SPAM | Mana Projects"         (Truecaller identified a business)
 *
 * Strategy: drop UI chrome (swipe/answer/decline instructions, buttons), timers and bare numbers,
 * then take the first surviving line — on every real screen captured on-device the caller name or
 * carrier warning is the first non-chrome text.
 */
object CallerLabelExtractor {

    private const val MAX_LABEL_LENGTH = 80

    /** Instruction/control text that appears on call surfaces but never identifies the caller. */
    private val CHROME = listOf(
        "swipe", "answer", "decline", "reject", "hang up", "end call", "message", "call from",
        "incoming call", "drag to move", "tap to", "dismiss", "remind", "mute", "speaker",
        "hold", "add call", "video call", "keypad", "caller id", "missed call", "call ended",
        "with two fingers", "silence", "reply", "notification", "ongoing",
    )

    private val TIMER = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")
    private val MOSTLY_DIGITS = Regex("""^[+\d\s\-()]{5,}$""")

    /**
     * @param texts on-screen texts in render order
     * @param preferred a spam label already matched by the classifier — always wins when present
     */
    fun extract(texts: List<ScrapedText>, preferred: String? = null): String? {
        if (!preferred.isNullOrBlank()) return preferred.trim()
        return texts.asSequence()
            .filterNot { it.isClickable }
            .filterNot { (it.className ?: "").contains("Button", ignoreCase = true) }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_LABEL_LENGTH }
            .filterNot { TIMER.matches(it) }
            .filterNot { MOSTLY_DIGITS.matches(it) }
            .filterNot { t -> CHROME.any { t.contains(it, ignoreCase = true) } }
            .firstOrNull()
    }
}

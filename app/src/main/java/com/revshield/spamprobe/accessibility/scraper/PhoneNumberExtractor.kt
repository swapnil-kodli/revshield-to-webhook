package com.revshield.spamprobe.accessibility.scraper

/**
 * Best-effort extraction of a caller phone number from on-screen texts.
 *
 * The incoming-call UI shows either a number or a contact name; when it is a number we try to
 * recover it. Pure and testable — no Android dependencies.
 */
object PhoneNumberExtractor {

    // A loose phone-like pattern: optional +, then digits interspersed with spaces/dashes/parens.
    private val CANDIDATE = Regex("""\+?\d[\d\s\-()]{5,}\d""")

    fun extract(texts: List<String>): String? {
        for (text in texts) {
            val match = CANDIDATE.find(text)?.value ?: continue
            val digits = match.filter { it.isDigit() || it == '+' }
            // National + international numbers land roughly in this length band.
            if (digits.count { it.isDigit() } in 7..15) return digits
        }
        return null
    }
}

package com.revshield.spamprobe.accessibility.scraper

/**
 * A single piece of on-screen text plus the metadata needed to decide whether it is a genuine
 * caller label rather than a button or action control.
 *
 * The distinction matters: dialer/caller-ID screens (e.g. Truecaller) render spam-related *action
 * buttons* ("Block & report spam", "Not spam") on every call. Matching those produced false
 * positives. Carrying [isClickable]/[className] lets the classifier ignore them.
 */
data class ScrapedText(
    val text: String,
    val isClickable: Boolean,
    val className: String? = null,
)

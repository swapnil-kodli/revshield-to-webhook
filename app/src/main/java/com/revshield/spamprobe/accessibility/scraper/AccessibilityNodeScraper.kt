package com.revshield.spamprobe.accessibility.scraper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Traverses an [AccessibilityNodeInfo] tree breadth-first and collects visible text and content
 * descriptions, each tagged with whether its node is clickable and its class name. This is the
 * boundary between the Android accessibility API and the pure
 * [com.revshield.spamprobe.accessibility.classifier.SpamLabelClassifier].
 *
 * A node-count guard prevents pathological traversal of very large trees.
 */
class AccessibilityNodeScraper(
    private val maxNodes: Int = 400,
) {

    fun collect(root: AccessibilityNodeInfo?): List<ScrapedText> {
        if (root == null) return emptyList()

        val out = ArrayList<ScrapedText>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            val className = node.className?.toString()
            val clickable = node.isClickable

            node.text?.toString()?.takeIf { it.isNotBlank() }
                ?.let { out.add(ScrapedText(it, clickable, className)) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?.let { out.add(ScrapedText(it, clickable, className)) }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return out
    }
}

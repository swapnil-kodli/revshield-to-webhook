package com.revshield.spamprobe.observation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Serializes the FULL accessibility tree to the wire JSON shape (Package §9 raw-first / §16.6): every
 * observation captures the complete hierarchy — even on a confident detection — so labels can be
 * re-derived retroactively as carrier formats are learned. Matches `observation.schema.json`'s node.
 */
object RawTreeSerializer {
    private const val MAX_NODES = 400 // guardrail; mirrors the scraper's BFS bound

    fun serialize(root: AccessibilityNodeInfo?): JSONObject {
        if (root == null) {
            return JSONObject().put("clickable", false).put("children", JSONArray())
        }
        return node(root, intArrayOf(0))
    }

    private fun node(n: AccessibilityNodeInfo, counter: IntArray): JSONObject {
        val o = JSONObject()
        n.className?.let { o.put("className", it.toString()) }
        n.viewIdResourceName?.let { o.put("viewIdResourceName", it) }
        n.text?.let { o.put("text", it.toString()) }
        n.contentDescription?.let { o.put("contentDescription", it.toString()) }
        o.put("clickable", n.isClickable)
        val bounds = Rect().also { n.getBoundsInScreen(it) }
        o.put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")

        val children = JSONArray()
        for (i in 0 until n.childCount) {
            if (counter[0] >= MAX_NODES) break
            val child = n.getChild(i) ?: continue
            counter[0]++
            children.put(node(child, counter))
        }
        o.put("children", children)
        return o
    }

    /** Lowercase hex SHA-256 of the serialized tree — the device-computed `raw_tree_sha256` (Package §19). */
    fun sha256Hex(tree: JSONObject): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tree.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

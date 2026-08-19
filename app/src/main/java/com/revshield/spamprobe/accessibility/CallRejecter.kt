package com.revshield.spamprobe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hangs up the current call AFTER the observation is finalised (capture-then-reject).
 *
 * Strategy, in order:
 *  1. TelecomManager.endCall() — needs the ANSWER_PHONE_CALLS grant. Deprecated since API 29 and
 *     silently returns false on some OEM builds, so its result is logged explicitly rather than
 *     assumed.
 *  2. Click a Decline control found by view-id, content-description, or text — searched across ALL
 *     windows, not just the active one, because a caller-ID overlay (Truecaller) is frequently the
 *     active window while the Decline button lives in the dialer's window underneath.
 *
 * Every failure says WHY, so a probe that stops rejecting is diagnosable from logcat alone.
 */
object CallRejecter {
    private const val TAG = "RevShield"

    fun reject(service: AccessibilityService) {
        if (endCall(service)) return
        if (clickDecline(service)) return
        Log.e(TAG, "AUTO-REJECT FAILED: endCall() unavailable and no decline control found in any window")
    }

    private fun endCall(service: AccessibilityService): Boolean {
        return try {
            val tm = service.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (tm == null) {
                Log.w(TAG, "endCall(): TelecomManager unavailable")
                return false
            }
            @Suppress("MissingPermission", "DEPRECATION") // ANSWER_PHONE_CALLS granted at runtime
            val ended = tm.endCall()
            if (ended) Log.i(TAG, "call ended via TelecomManager.endCall()")
            else Log.w(TAG, "endCall() returned FALSE (OEM/API restriction) - falling back to decline click")
            ended
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall() denied - ANSWER_PHONE_CALLS not granted: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "endCall() threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Search every window for a decline affordance and click it. */
    private fun clickDecline(service: AccessibilityService): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.windows?.forEach { w -> w.root?.let { roots.add(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "could not enumerate windows: ${e.message}")
        }
        service.rootInActiveWindow?.let { if (roots.none { r -> r == it }) roots.add(it) }
        if (roots.isEmpty()) {
            Log.w(TAG, "decline click: no window roots available")
            return false
        }

        for (root in roots) {
            findDecline(root)?.let { node ->
                if (clickSelfOrAncestor(node)) return true
            }
        }
        // Nothing matched — dump what WAS on screen so the labels can be added to the matcher.
        Log.w(TAG, "decline click: no match. Visible clickables: ${describeClickables(roots)}")
        return false
    }

    /** Depth-first hunt for a node that looks like a decline/end-call control. */
    private fun findDecline(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        if (ID_HINTS.any { id.contains(it) } || LABELS.any { desc.contains(it) || text.contains(it) }) {
            // Never click "answer"/"accept" by accident.
            if (ACCEPT_HINTS.none { id.contains(it) || desc.contains(it) || text.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            findDecline(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickSelfOrAncestor(match: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = match
        var hops = 0
        while (node != null && hops < 6) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-rejected by clicking [id=${node.viewIdResourceName} desc=${node.contentDescription}]")
                return true
            }
            node = node.parent
            hops++
        }
        return false
    }

    /** Diagnostic: what clickable controls existed when we failed to find a decline button. */
    private fun describeClickables(roots: List<AccessibilityNodeInfo>): String {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > MAX_DEPTH || out.size >= 20) return
            if (n.isClickable) {
                val id = n.viewIdResourceName?.substringAfterLast('/') ?: "-"
                val label = n.contentDescription ?: n.text ?: "-"
                out.add("$id:$label")
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0) }
        return if (out.isEmpty()) "(none)" else out.joinToString(", ")
    }

    private const val MAX_DEPTH = 25
    private val LABELS = listOf("decline", "reject", "end call", "hang up", "dismiss", "decline call")
    private val ID_HINTS = listOf("decline", "reject", "end_call", "endcall", "hangup", "hang_up")
    private val ACCEPT_HINTS = listOf("answer", "accept", "pickup", "pick_up")
}

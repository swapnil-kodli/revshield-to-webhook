package com.revshield.spamprobe.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Hangs up the current call AFTER the observation is finalised (capture-then-reject). Primary path is
 * TelecomManager.endCall() (API 28+, requires the ANSWER_PHONE_CALLS runtime grant). If that isn't
 * available, fall back to clicking a Decline/Reject control in the call UI via accessibility.
 */
object CallRejecter {
    private const val TAG = "RevShield"

    fun reject(service: AccessibilityService) {
        val ctx: Context = service
        try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("MissingPermission") // ANSWER_PHONE_CALLS is requested at runtime by MainActivity
            val ended = tm?.endCall() == true
            if (ended) {
                Log.i(TAG, "call ended via TelecomManager.endCall()")
                return
            }
            Log.w(TAG, "endCall() returned false — trying accessibility decline fallback")
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall() denied (grant ANSWER_PHONE_CALLS): ${e.message} — trying fallback")
        } catch (e: Exception) {
            Log.w(TAG, "endCall() failed: ${e.message} — trying fallback")
        }
        if (!clickDecline(service)) {
            Log.w(TAG, "no decline control found; could not auto-reject the call")
        }
    }

    private fun clickDecline(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        for (label in DECLINE_LABELS) {
            val matches = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (match in matches) {
                var node: AccessibilityNodeInfo? = match
                while (node != null) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "auto-rejected via decline control '$label'")
                        return true
                    }
                    node = node.parent
                }
            }
        }
        return false
    }

    private val DECLINE_LABELS = listOf("decline", "reject", "end call", "hang up", "dismiss")
}

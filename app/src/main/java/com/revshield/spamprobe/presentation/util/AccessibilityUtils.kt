package com.revshield.spamprobe.presentation.util

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Helpers for checking accessibility-service enablement and opening the system settings screen. */
object AccessibilityUtils {

    /** True if [service] is currently enabled in the system's accessibility settings. */
    fun isServiceEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        val expected = ComponentName(context, service).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices.split(':').any { entry ->
            entry.equals(expected, ignoreCase = true)
        }
    }

    /** Opens the system Accessibility settings so the user can enable the service. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

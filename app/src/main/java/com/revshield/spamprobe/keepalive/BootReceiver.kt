package com.revshield.spamprobe.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-arms the probe after a reboot, so the phone comes back capturing without anyone touching it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i("RevShield", "boot/upgrade received ($action) - starting keep-alive")
            ProbeKeepAliveService.start(context)
        }
    }
}

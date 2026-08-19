package com.revshield.spamprobe.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.revshield.spamprobe.accessibility.CallCaptureService
import com.revshield.spamprobe.presentation.util.AccessibilityUtils
import com.revshield.spamprobe.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the probe alive and armed — the phone's single most important job.
 *
 * Two independent failure modes are handled, both observed on this hardware:
 *
 *  1. MIUI's memory cleaner kills the process outright:
 *       ProcessSceneCleaner: OneKeyClean: kill procName=com.revshield.spamprobe
 *     A FOREGROUND service with an ongoing notification keeps the process at a priority the cleaner
 *     will not reap, and START_STICKY makes Android restart it if it is killed anyway.
 *
 *  2. When the process dies while an AccessibilityService is bound, Android records it under
 *     "Crashed services" and NEVER rebinds it. The probe then looks installed and healthy while
 *     capturing nothing. The watchdog below detects that and re-arms the service itself.
 *
 * Re-arming writes Settings.Secure, which needs WRITE_SECURE_SETTINGS. That is a signature/privileged
 * permission, granted once over adb on this dedicated probe handset:
 *   adb shell pm grant com.revshield.spamprobe android.permission.WRITE_SECURE_SETTINGS
 * Without it the watchdog still runs and LOUDLY logs that capture is down, so the failure is visible
 * rather than silent.
 */
class ProbeKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    private var consecutiveFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("Capture armed"))
        watchdog = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.e(TAG, "watchdog tick failed: ${it.message}") }
                delay(CHECK_INTERVAL_MS)
            }
        }
        Log.i(TAG, "keep-alive service started (foreground + watchdog)")
    }

    /** START_STICKY: if the OS or a cleaner kills us, Android brings the service back. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** If the app is swiped from recents, come straight back — the probe must not stop. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "task removed from recents - restarting keep-alive")
        restartSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.w(TAG, "keep-alive service destroyed - scheduling restart")
        watchdog?.cancel()
        scope.cancel()
        restartSelf()
        super.onDestroy()
    }

    private fun restartSelf() {
        runCatching {
            val intent = Intent(applicationContext, ProbeKeepAliveService::class.java)
            val pi = PendingIntent.getForegroundService(
                applicationContext, 1, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2_000L, pi)
        }
    }

    /**
     * One watchdog pass. Capture is only truly armed when ALL THREE hold:
     *   - the master accessibility switch is on   (MIUI sets ACCESSIBILITY_ENABLED=0 after a kill)
     *   - our component is in the enabled list
     *   - the service is ACTUALLY bound           (the list still lists us when Android has flagged
     *                                              the service crashed and refuses to rebind)
     * Checking only the list — as this watchdog first did — reports "armed" while nothing captures.
     */
    private suspend fun tick() {
        val master = runCatching {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.getOrDefault(0) == 1
        val listed = AccessibilityUtils.isServiceEnabled(applicationContext, CallCaptureService::class.java)
        val bound = CallCaptureService.isConnected

        if (master && listed && bound) {
            consecutiveFailures = 0
            updateNotification("Capture armed")
            return
        }

        consecutiveFailures++
        Log.e(TAG, "CAPTURE IS DOWN (master=$master listed=$listed bound=$bound) - re-arming, attempt $consecutiveFailures")
        updateNotification("Capture DOWN - re-arming")
        if (!reArm()) {
            Log.e(TAG, "re-arm FAILED - grant WRITE_SECURE_SETTINGS, or enable RevShield Probe in Accessibility settings")
        }
    }

    /**
     * Force a rebind. Simply re-writing the setting is NOT enough once Android has flagged the
     * service crashed — it must be cleared first so the framework drops the crashed state, then set
     * again. Verified on-device: clear -> pause -> set produces "capture service connected".
     * Other apps' accessibility services are preserved.
     */
    private suspend fun reArm(): Boolean = runCatching {
        val component = "$packageName/${CallCaptureService::class.java.name}"
        val cr = contentResolver
        val others = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() && it != component }

        // 1. drop the crashed state
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        delay(REARM_PAUSE_MS)

        // 2. re-enable, ours last so it is definitely present
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + component).joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        delay(REARM_PAUSE_MS)

        val ok = CallCaptureService.isConnected
        Log.i(TAG, "re-arm sequence done - bound=$ok")
        ok
    }.getOrElse {
        Log.w(TAG, "re-arm failed: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "RevShield Probe", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps call capture running"
                    setShowBadge(false)
                },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RevShield Probe")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    private fun updateNotification(text: String) = runCatching {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "RevShield"
        private const val CHANNEL_ID = "revshield-keepalive"
        private const val NOTIFICATION_ID = 4711
        private const val CHECK_INTERVAL_MS = 10_000L
        private const val REARM_PAUSE_MS = 1_500L

        /** Safe to call repeatedly; starting an already-running service is a no-op. */
        fun start(context: Context) = runCatching {
            val intent = Intent(context, ProbeKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }.onFailure { Log.w(TAG, "could not start keep-alive: ${it.message}") }
    }
}

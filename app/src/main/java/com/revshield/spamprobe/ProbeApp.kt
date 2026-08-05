package com.revshield.spamprobe

import android.app.Application
import com.revshield.spamprobe.work.UploadScheduler

/** Application entry point. Schedules the safety-net periodic drain (no-op until a URL + records exist). */
class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UploadScheduler.ensurePeriodic(this)
    }
}

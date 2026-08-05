package com.revshield.spamprobe.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.revshield.spamprobe.accessibility.CallCaptureService
import com.revshield.spamprobe.data.ObservationJson
import com.revshield.spamprobe.data.ObservationRecord
import com.revshield.spamprobe.data.ProbeDatabase
import com.revshield.spamprobe.data.SyncState
import com.revshield.spamprobe.net.Net
import com.revshield.spamprobe.presentation.util.AccessibilityUtils
import com.revshield.spamprobe.settings.ProbeSettings
import com.revshield.spamprobe.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProbeViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ProbeDatabase.get(app).observations()
    private val settings = ProbeSettings(app)

    private fun <T> stateOf(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val records: StateFlow<List<ObservationRecord>> = stateOf(dao.observeRecent(500), emptyList())
    val total: StateFlow<Int> = stateOf(dao.observeTotal(), 0)
    val pending: StateFlow<Int> = stateOf(dao.observeCount(SyncState.PENDING), 0)
    val synced: StateFlow<Int> = stateOf(dao.observeCount(SyncState.SYNCED), 0)
    val failed: StateFlow<Int> = stateOf(dao.observeCount(SyncState.FAILED), 0)

    val accessibilityOn = MutableStateFlow(isAccessibilityOn())
    val connection = MutableStateFlow<String?>(null)
    val webhookUrl = MutableStateFlow(settings.webhookUrl)
    val headerName = MutableStateFlow(settings.headerName)
    val headerValue = MutableStateFlow(settings.headerValue)

    /** False ⇒ Home shows "No webhook URL configured" and records are held PENDING (not Failed). */
    val webhookConfigured = MutableStateFlow(settings.isConfigured())

    fun refreshAccessibility() {
        accessibilityOn.value = isAccessibilityOn()
    }

    private fun isAccessibilityOn(): Boolean =
        AccessibilityUtils.isServiceEnabled(getApplication(), CallCaptureService::class.java)

    fun syncNow() = UploadScheduler.syncNow(getApplication())

    /**
     * Validate + persist the webhook URL. Returns an error message to display, or null on success.
     * On a successful save of a NON-BLANK url the pending backlog is flushed to it immediately.
     */
    fun saveUrl(value: String): String? {
        val error = ProbeSettings.validationError(value)
        if (error != null) return error
        settings.webhookUrl = value
        webhookUrl.value = settings.webhookUrl
        webhookConfigured.value = settings.isConfigured()
        connection.value = null
        if (settings.isConfigured()) syncNow() // flush the backlog the instant a valid URL exists
        return null
    }

    fun saveHeader(name: String, value: String) {
        settings.headerName = name
        settings.headerValue = value
        headerName.value = settings.headerName
        headerValue.value = settings.headerValue
    }

    /** POST a small test payload to the configured webhook and show the REAL HTTP result. */
    fun testConnection() {
        val url = settings.webhookUrl
        if (url.isBlank()) {
            connection.value = "No webhook URL configured — set one above first."
            return
        }
        connection.value = "testing $url …"
        viewModelScope.launch(Dispatchers.IO) {
            connection.value = try {
                val r = Net.testWebhook(url, settings.headerName, settings.headerValue)
                val ok = r.code in 200..299
                "${if (ok) "reachable" else "REACHED but non-2xx"} — HTTP ${r.code} ${r.body}"
            } catch (e: Exception) {
                "OFFLINE — ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** NDJSON text for export (one wire record per line). */
    suspend fun exportText(pendingOnly: Boolean): String = withContext(Dispatchers.IO) {
        val rows = if (pendingOnly) dao.allPending() else dao.all()
        rows.joinToString("\n") { ObservationJson.toWire(it) }
    }
}

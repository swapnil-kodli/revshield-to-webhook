package com.revshield.spamprobe.net

import android.util.Log
import com.revshield.spamprobe.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP to the configured WEBHOOK. No auth, no envelope, no reshaping — the observation JSON is
 * POSTed exactly as produced, one record per request. An optional single custom header is supported
 * for future auth. Every request and response is logged under tag "RevShieldNet" (BODY level in
 * debug), so the network layer is never invisible. Filter with: adb logcat -s RevShieldNet:*
 */
object Net {
    const val TAG = "RevShieldNet"
    private val JSON = "application/json".toMediaType()

    data class Result(val code: Int, val body: String)

    private fun client(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor { message -> Log.d(TAG, message) }.apply {
                    level = HttpLoggingInterceptor.Level.BODY
                },
            )
        }
        return builder.build()
    }

    private fun post(url: String, jsonBody: String, headerName: String, headerValue: String): Result {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .apply {
                if (headerName.isNotBlank() && headerValue.isNotBlank()) addHeader(headerName, headerValue)
            }
            .build()
        client().newCall(req).execute().use { resp ->
            return Result(resp.code, resp.body?.string()?.take(300) ?: "")
        }
    }

    /**
     * POST ONE observation to the webhook, body unchanged. Returns the real HTTP status + (truncated)
     * body; throws IOException on network failure. SYNCED is decided by the caller on a 2xx ONLY.
     */
    fun postObservation(url: String, jsonBody: String, headerName: String = "", headerValue: String = ""): Result =
        post(url, jsonBody, headerName, headerValue)

    /** "Test connection": POST a small, clearly-labelled payload so the operator sees the real result. */
    fun testWebhook(url: String, headerName: String = "", headerValue: String = ""): Result {
        val payload = """{"revshield_test":true,"message":"RevShield Probe test — no call data"}"""
        return post(url, payload, headerName, headerValue)
    }
}

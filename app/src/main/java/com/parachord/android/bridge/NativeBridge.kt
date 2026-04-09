package com.parachord.android.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "NativeBridge"

/**
 * Native module bindings exposed to JavaScript via @JavascriptInterface.
 *
 * Provides fetch, storage, and logging to the JS runtime so .axe resolver
 * plugins and AI providers can call back into native code.
 *
 * These methods are called synchronously from the WebView thread —
 * they must return without suspending (hence [runBlocking] for storage reads).
 */
class NativeBridge(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val dataStore: DataStore<Preferences>,
) {

    // ── Fetch ────────────────────────────────────────────────────────

    /**
     * HTTP GET fetch for backward compatibility.
     */
    @JavascriptInterface
    fun fetch(url: String, headersJson: String): String {
        return fetchWithOptions(url, "GET", headersJson, "")
    }

    /**
     * Full HTTP fetch supporting GET, POST, PUT, DELETE with headers and body.
     * Returns a JSON envelope: {"status": 200, "ok": true, "body": "..."}
     */
    @JavascriptInterface
    fun fetchWithOptions(url: String, method: String, headersJson: String, body: String): String {
        return try {
            val requestBuilder = Request.Builder().url(url)

            // Parse and apply headers
            if (headersJson.isNotBlank() && headersJson != "{}") {
                try {
                    val cleaned = headersJson.trim().removePrefix("{").removeSuffix("}")
                    if (cleaned.isNotBlank()) {
                        cleaned.split(",").forEach { pair ->
                            val parts = pair.split(":", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().removeSurrounding("\"")
                                val value = parts[1].trim().removeSurrounding("\"")
                                if (key.isNotBlank()) requestBuilder.header(key, value)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse headers: $headersJson", e)
                }
            }

            // Set method and body
            val requestBody = when (method.uppercase()) {
                "POST", "PUT", "PATCH" -> body.toRequestBody("application/json".toMediaType())
                "DELETE" -> if (body.isNotBlank()) body.toRequestBody("application/json".toMediaType()) else null
                else -> null
            }
            requestBuilder.method(method.uppercase(), requestBody)

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            val isOk = response.isSuccessful

            """{"status":$statusCode,"ok":$isOk,"body":${escapeJsonString(responseBody)}}"""
        } catch (e: Exception) {
            Log.e(TAG, "fetch error ($method $url): ${e.message}")
            """{"status":0,"ok":false,"body":${escapeJsonString(e.message ?: "Network error")}}"""
        }
    }

    // ── Logging ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level) {
            "error" -> Log.e(TAG, message)
            "warn" -> Log.w(TAG, message)
            "info" -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    // ── Storage ──────────────────────────────────────────────────────

    /**
     * Read a value from DataStore. Synchronous (required by @JavascriptInterface).
     * Uses [runBlocking] — acceptable since DataStore reads from memory cache.
     */
    @JavascriptInterface
    fun storageGet(key: String): String? {
        return try {
            runBlocking {
                val prefs = dataStore.data.first()
                prefs[stringPreferencesKey(key)]
            }
        } catch (e: Exception) {
            Log.w(TAG, "storageGet($key) failed: ${e.message}")
            null
        }
    }

    /** Write a value to DataStore asynchronously. */
    @JavascriptInterface
    fun storageSet(key: String, value: String) {
        scope.launch {
            try {
                dataStore.edit { it[stringPreferencesKey(key)] = value }
            } catch (e: Exception) {
                Log.w(TAG, "storageSet($key) failed: ${e.message}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}

package com.parachord.android.bridge

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native module bindings exposed to JavaScript via @JavascriptInterface.
 *
 * Provides fetch(), storage, and logging capabilities to the JS runtime so
 * that resolver plugins and business logic can call back into native code.
 */
class NativeBridge(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {

    @JavascriptInterface
    fun fetch(url: String, headersJson: String): String {
        // Synchronous fetch for JS — runs on WebView thread
        val requestBuilder = Request.Builder().url(url)
        // headersJson is a JSON object of key-value pairs
        // TODO: parse and apply headers
        val response = httpClient.newCall(requestBuilder.build()).execute()
        return response.body?.string() ?: ""
    }

    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level) {
            "error" -> android.util.Log.e("JsBridge", message)
            "warn" -> android.util.Log.w("JsBridge", message)
            "info" -> android.util.Log.i("JsBridge", message)
            else -> android.util.Log.d("JsBridge", message)
        }
    }

    @JavascriptInterface
    fun storageGet(key: String): String? {
        // TODO: Wire up to DataStore for persistent key-value storage
        return null
    }

    @JavascriptInterface
    fun storageSet(key: String, value: String) {
        // TODO: Wire up to DataStore for persistent key-value storage
    }
}

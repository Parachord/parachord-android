package com.parachord.android.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between Kotlin and JavaScript runtime.
 *
 * Uses a headless WebView to execute .axe resolver plugins and core business
 * logic (resolver-loader, scrobble-manager, sync-engine) unchanged from the
 * desktop Electron app.
 *
 * The architecture doc specifies Hermes, but we start with WebView as a
 * readily available JS runtime. Hermes integration can be swapped in later
 * without changing the bridge API surface.
 */
@Singleton
class JsBridge @Inject constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var webView: WebView? = null

    /** Initialize the JS runtime and load core modules from assets/js/. */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(NativeBridge(httpClient, scope), "NativeBridge")
        }
        webView = wv

        // Load the bootstrap HTML that sets up the JS environment
        wv.loadUrl("file:///android_asset/js/bootstrap.html")
        _ready.value = true
    }

    /**
     * Evaluate a JS expression and return the result.
     * Must be called after [initialize].
     */
    suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: error("JsBridge not initialized")
        val deferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(script) { result -> deferred.complete(result) }
        deferred.await()
    }

    fun teardown() {
        webView?.destroy()
        webView = null
        _ready.value = false
    }
}

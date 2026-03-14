package com.parachord.android.playback.handlers

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between Kotlin and Apple's MusicKit JS running inside a hidden WebView.
 *
 * This mirrors the desktop Parachord approach of using MusicKit JS as a
 * cross-platform fallback for Apple Music playback and catalog search.
 * The WebView hosts musickit-bridge.html which loads MusicKit v3 from Apple's CDN.
 *
 * Communication:
 * - Kotlin -> JS: via [evaluate] wrapping calls in async IIFEs
 * - JS -> Kotlin: via [MusicKitJsInterface] @JavascriptInterface callbacks
 */
@Singleton
class MusicKitWebBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    companion object {
        private const val TAG = "MusicKitWebBridge"
        private const val BRIDGE_URL = "file:///android_asset/js/musickit-bridge.html"
        private const val APP_NAME = "Parachord"
    }

    private var webView: WebView? = null
    private var pageLoaded = CompletableDeferred<Unit>()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** Callback invoked when the current track finishes playing (for auto-advance). */
    var onTrackEnded: (() -> Unit)? = null

    // ── Lifecycle ─────────────────────────────────────────────────

    /** Create the hidden WebView and load the MusicKit bridge HTML. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext

        pageLoaded = CompletableDeferred()

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(MusicKitJsInterface(), "MusicKitBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Bridge page loaded: $url")
                    pageLoaded.complete(Unit)
                }
            }
        }
        webView = wv
        wv.loadUrl(BRIDGE_URL)
        _ready.value = true
    }

    /** Destroy the WebView and release resources. */
    fun teardown() {
        webView?.destroy()
        webView = null
        _ready.value = false
        _configured.value = false
        _authorized.value = false
        _isPlaying.value = false
        _position.value = 0L
        _duration.value = 0L
    }

    // ── Configuration & Auth ──────────────────────────────────────

    /**
     * Configure MusicKit with the developer token from settings.
     * Returns true if configuration succeeded.
     */
    suspend fun configure(): Boolean {
        pageLoaded.await()
        val token = settingsStore.getAppleMusicDeveloperToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Apple Music developer token configured")
            return false
        }
        val escaped = token.replace("'", "\\'")
        val result = evaluate("configure('$escaped', '$APP_NAME')") ?: return false
        return try {
            val parsed = json.decodeFromString<GenericResponse>(cleanJsString(result))
            val success = parsed.success
            if (success) _configured.value = true
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse configure response: $result", e)
            false
        }
    }

    /**
     * Prompt Apple ID sign-in via MusicKit's authorize flow.
     * Returns true if the user authorized successfully.
     */
    suspend fun authorize(): Boolean {
        pageLoaded.await()
        val result = evaluate("authorize()") ?: return false
        return try {
            val parsed = json.decodeFromString<AuthorizeResponse>(cleanJsString(result))
            val authorized = parsed.authorized
            if (authorized) _authorized.value = true
            authorized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse authorize response: $result", e)
            false
        }
    }

    // ── Search ────────────────────────────────────────────────────

    /**
     * Search the Apple Music catalog for songs.
     * Returns a list of search results, or empty on failure.
     */
    suspend fun search(query: String, limit: Int = 10): List<AppleMusicSearchResult> {
        pageLoaded.await()
        val storefront = settingsStore.getAppleMusicStorefront() ?: "us"
        val escaped = query.replace("'", "\\'")
        val result = evaluate("search('$escaped', $limit, '$storefront')") ?: return emptyList()
        return try {
            val parsed = json.decodeFromString<MusicKitSearchResponse>(cleanJsString(result))
            if (!parsed.success) {
                Log.w(TAG, "Search failed: ${parsed.error}")
                emptyList()
            } else {
                parsed.results
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse search response: $result", e)
            emptyList()
        }
    }

    // ── Playback Control ──────────────────────────────────────────

    /** Play a song by Apple Music catalog ID. Returns true on success. */
    suspend fun play(songId: String): Boolean {
        pageLoaded.await()
        val escaped = songId.replace("'", "\\'")
        val result = evaluate("play('$escaped')") ?: return false
        return parseSuccessResponse(result)
    }

    /** Pause playback. */
    suspend fun pause() {
        pageLoaded.await()
        evaluate("pause()")
    }

    /** Resume playback. */
    suspend fun resume() {
        pageLoaded.await()
        evaluate("resume()")
    }

    /** Stop playback. */
    suspend fun stop() {
        pageLoaded.await()
        evaluate("stop()")
    }

    /** Seek to position in milliseconds. */
    suspend fun seekTo(positionMs: Long) {
        pageLoaded.await()
        evaluate("seekTo($positionMs)")
    }

    // ── State Accessors ───────────────────────────────────────────

    fun getPosition(): Long = _position.value
    fun getDuration(): Long = _duration.value
    fun getIsPlaying(): Boolean = _isPlaying.value

    // ── JS Interface (JS -> Kotlin callbacks) ─────────────────────

    inner class MusicKitJsInterface {

        @JavascriptInterface
        fun onPlaybackStateChange(jsonStr: String) {
            Log.d(TAG, "Playback state change: $jsonStr")
            try {
                val state = json.decodeFromString<MusicKitPlaybackState>(jsonStr)
                _isPlaying.value = state.isPlaying
                // JS bridge already sends values in milliseconds
                _position.value = state.position.toLong()
                _duration.value = state.duration.toLong()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse playback state: $jsonStr", e)
            }
        }

        @JavascriptInterface
        fun onAuthChange(jsonStr: String) {
            Log.d(TAG, "Auth change: $jsonStr")
            try {
                val state = json.decodeFromString<MusicKitAuthState>(jsonStr)
                _configured.value = state.configured
                _authorized.value = state.authorized
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse auth state: $jsonStr", e)
            }
        }

        @JavascriptInterface
        fun onNowPlayingChange(jsonStr: String) {
            Log.d(TAG, "Now playing change: $jsonStr")
        }

        @JavascriptInterface
        fun onTrackEnded(jsonStr: String) {
            Log.d(TAG, "Track ended: $jsonStr")
            onTrackEnded?.invoke()
        }
    }

    // ── Private Helpers ───────────────────────────────────────────

    /**
     * Evaluate a JS expression in the WebView.
     * Wraps the script in an async IIFE so top-level await works.
     * Must be called on the Main thread (handled by withContext).
     */
    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: run {
            Log.e(TAG, "WebView not initialized")
            return@withContext null
        }
        val deferred = CompletableDeferred<String?>()
        val wrapped = "(async function() { return $script; })()"
        wv.evaluateJavascript(wrapped) { result -> deferred.complete(result) }
        deferred.await()
    }

    /**
     * Strip the outer quotes that evaluateJavascript wraps around string results.
     * e.g. "\"{ ... }\"" -> "{ ... }"
     * Also unescapes inner quotes.
     */
    private fun cleanJsString(raw: String): String {
        var s = raw
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        s = s.replace("\\\"", "\"")
        s = s.replace("\\\\", "\\")
        return s
    }

    private fun parseSuccessResponse(raw: String): Boolean {
        return try {
            val parsed = json.decodeFromString<GenericResponse>(cleanJsString(raw))
            parsed.success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $raw", e)
            false
        }
    }

    // ── Data Classes ──────────────────────────────────────────────

    @Serializable
    private data class GenericResponse(
        val success: Boolean = false,
        val error: String? = null,
    )

    @Serializable
    private data class AuthorizeResponse(
        val success: Boolean = false,
        val authorized: Boolean = false,
        val error: String? = null,
    )

    @Serializable
    data class MusicKitSearchResponse(
        val success: Boolean = false,
        val results: List<AppleMusicSearchResult> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    data class AppleMusicSearchResult(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: Long? = null,
        val artworkUrl: String? = null,
        val isrc: String? = null,
        val previewUrl: String? = null,
        val appleMusicUrl: String? = null,
    )

    @Serializable
    data class MusicKitPlaybackState(
        val state: String? = null,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val isPlaying: Boolean = false,
    )

    @Serializable
    data class MusicKitAuthState(
        val configured: Boolean = false,
        val authorized: Boolean = false,
    )
}

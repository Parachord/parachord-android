# Apple Music Resolver via MusicKit JS WebView

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add Apple Music as a resolver and playback source using MusicKit JS hosted in a hidden WebView, matching the desktop's cross-platform fallback tier.

**Architecture:** A hidden WebView loads Apple's MusicKit JS CDN library and exposes search/playback via a Kotlin bridge (`@JavascriptInterface` + `evaluateJavascript`). The resolver searches the Apple Music catalog and returns results with `appleMusicId`. The playback handler delegates play/pause/seek to MusicKit's built-in DRM player running inside the WebView. iTunes Search API is the no-auth fallback for resolution when no developer token is configured.

**Tech Stack:** Android WebView, MusicKit JS v3 (Apple CDN), `@JavascriptInterface`, OkHttp (iTunes API fallback), Hilt DI, Room migration

---

## Task 1: Add `appleMusicId` to TrackEntity + Room Migration

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/db/entity/TrackEntity.kt`
- Modify: `app/src/main/java/com/parachord/android/data/db/ParachordDatabase.kt`

**Step 1: Add field to TrackEntity**

In `TrackEntity.kt`, add after the `spotifyId` field:

```kotlin
/** Apple Music catalog song ID (e.g. "1440935467"). */
val appleMusicId: String? = null,
```

**Step 2: Add Room migration v9→v10**

In `ParachordDatabase.kt`:
- Bump `version = 10` in the `@Database` annotation
- Add migration:

```kotlin
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tracks` ADD COLUMN `appleMusicId` TEXT")
    }
}
```

- Add `MIGRATION_9_10` to `.addMigrations(...)` call

**Step 3: Add `appleMusicId` to ResolvedSource**

In `app/src/main/java/com/parachord/android/resolver/ResolverManager.kt`, add to `ResolvedSource`:

```kotlin
val appleMusicId: String? = null,
```

**Step 4: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A && git commit -m "feat: add appleMusicId to TrackEntity with Room migration v9→v10"
```

---

## Task 2: Create MusicKit WebView Bridge HTML

**Files:**
- Create: `app/src/main/assets/js/musickit-bridge.html`

**Step 1: Create the bridge HTML**

This is a minimal HTML page that:
1. Loads MusicKit JS v3 from Apple's CDN
2. Provides bridge functions callable from Kotlin via `evaluateJavascript()`
3. Reports playback state changes back to Kotlin via `MusicKitBridge.onPlaybackStateChange()` etc.

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>MusicKit Bridge</title>
</head>
<body>
<script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js"
        data-web-components
        crossorigin></script>
<script>
    // State
    let musicKit = null;
    let configured = false;
    let authorized = false;

    // ── Configure ──────────────────────────────────────────────
    async function configure(developerToken, appName) {
        try {
            await MusicKit.configure({
                developerToken: developerToken,
                app: { name: appName || 'Parachord', build: '1.0' }
            });
            musicKit = MusicKit.getInstance();
            configured = true;

            // Listen for auth changes
            musicKit.addEventListener('authorizationStatusDidChange', (e) => {
                authorized = musicKit.isAuthorized;
                MusicKitBridge.onAuthChange(JSON.stringify({
                    authorized: authorized,
                    configured: configured
                }));
            });

            // Listen for playback state changes
            musicKit.addEventListener('playbackStateDidChange', (e) => {
                MusicKitBridge.onPlaybackStateChange(JSON.stringify({
                    state: e.state, // none, loading, playing, paused, stopped, ended, etc.
                    position: musicKit.currentPlaybackTime * 1000,
                    duration: musicKit.currentPlaybackDuration * 1000,
                    isPlaying: musicKit.isPlaying
                }));
            });

            // Listen for track changes
            musicKit.addEventListener('nowPlayingItemDidChange', (e) => {
                const item = musicKit.nowPlayingItem;
                if (item) {
                    MusicKitBridge.onNowPlayingChange(JSON.stringify({
                        id: item.id,
                        title: item.title,
                        artist: item.artistName,
                        album: item.albumName,
                        duration: (item.playbackDuration || 0) * 1000,
                        artworkUrl: item.artwork ? MusicKit.formatArtworkURL(item.artwork, 600, 600) : null
                    }));
                }
            });

            // Track ended
            musicKit.addEventListener('mediaItemDidEndPlaying', () => {
                MusicKitBridge.onTrackEnded('{}');
            });

            return JSON.stringify({ success: true });
        } catch (e) {
            return JSON.stringify({ success: false, error: e.message });
        }
    }

    // ── Authorize ──────────────────────────────────────────────
    async function authorize() {
        try {
            const token = await musicKit.authorize();
            authorized = true;
            return JSON.stringify({ success: true, token: token });
        } catch (e) {
            return JSON.stringify({ success: false, error: e.message });
        }
    }

    // ── Search ─────────────────────────────────────────────────
    async function search(query, limit, storefront) {
        try {
            const sf = storefront || 'us';
            const results = await musicKit.api.music(
                `/v1/catalog/${sf}/search`,
                { term: query, types: 'songs', limit: limit || 10 }
            );
            const songs = results?.data?.results?.songs?.data || [];
            const mapped = songs.map(s => ({
                id: s.id,
                title: s.attributes.name,
                artist: s.attributes.artistName,
                album: s.attributes.albumName,
                duration: s.attributes.durationInMillis,
                artworkUrl: s.attributes.artwork ?
                    s.attributes.artwork.url.replace('{w}', '600').replace('{h}', '600') : null,
                isrc: s.attributes.isrc || null,
                previewUrl: s.attributes.previews?.[0]?.url || null,
                appleMusicUrl: s.attributes.url || null,
            }));
            return JSON.stringify({ success: true, results: mapped });
        } catch (e) {
            return JSON.stringify({ success: false, error: e.message, results: [] });
        }
    }

    // ── Playback ───────────────────────────────────────────────
    async function play(songId) {
        try {
            await musicKit.setQueue({ song: songId, startPlaying: true });
            return JSON.stringify({ success: true });
        } catch (e) {
            return JSON.stringify({ success: false, error: e.message });
        }
    }

    async function pause() {
        try { await musicKit.pause(); return '{"success":true}'; }
        catch (e) { return JSON.stringify({ success: false, error: e.message }); }
    }

    async function resume() {
        try { await musicKit.play(); return '{"success":true}'; }
        catch (e) { return JSON.stringify({ success: false, error: e.message }); }
    }

    async function seekTo(positionMs) {
        try {
            await musicKit.seekToTime(positionMs / 1000);
            return '{"success":true}';
        } catch (e) { return JSON.stringify({ success: false, error: e.message }); }
    }

    async function stop() {
        try { await musicKit.stop(); return '{"success":true}'; }
        catch (e) { return JSON.stringify({ success: false, error: e.message }); }
    }

    function getPlaybackState() {
        if (!musicKit) return JSON.stringify({ position: 0, duration: 0, isPlaying: false });
        return JSON.stringify({
            position: (musicKit.currentPlaybackTime || 0) * 1000,
            duration: (musicKit.currentPlaybackDuration || 0) * 1000,
            isPlaying: musicKit.isPlaying || false
        });
    }

    function getAuthStatus() {
        return JSON.stringify({
            configured: configured,
            authorized: authorized,
            hasDeveloperToken: configured
        });
    }
</script>
</body>
</html>
```

**Step 2: Build to verify asset is packaged**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add MusicKit JS bridge HTML for Apple Music WebView"
```

---

## Task 3: Create MusicKitWebBridge (Kotlin ↔ WebView)

**Files:**
- Create: `app/src/main/java/com/parachord/android/playback/handlers/MusicKitWebBridge.kt`

**Step 1: Implement the bridge**

This Kotlin singleton manages the hidden WebView lifecycle and provides suspend functions for all MusicKit operations:

```kotlin
package com.parachord.android.playback.handlers

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicKitWebBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    companion object {
        private const val TAG = "MusicKitWebBridge"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var webView: WebView? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized.asStateFlow()

    // Playback state reported by MusicKit JS
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // Callback for track ended (auto-advance)
    var onTrackEnded: (() -> Unit)? = null

    private var pageLoaded = CompletableDeferred<Unit>()

    /** Initialize the WebView and load the bridge HTML. */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(MusicKitJsInterface(), "MusicKitBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
                }
            }
        }
        webView = wv
        wv.loadUrl("file:///android_asset/js/musickit-bridge.html")
        pageLoaded.await()
        _ready.value = true
        Log.d(TAG, "MusicKit WebView bridge initialized")
    }

    /** Configure MusicKit with the developer token from settings. */
    suspend fun configure(): Boolean {
        val token = settingsStore.getAppleMusicDeveloperToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No Apple Music developer token configured")
            return false
        }
        initialize()
        val result = evaluate("configure('$token', 'Parachord')")
        val success = result?.contains("\"success\":true") == true
        _configured.value = success
        Log.d(TAG, "MusicKit configure: $success")
        return success
    }

    /** Prompt Apple ID authorization. */
    suspend fun authorize(): Boolean {
        val result = evaluate("authorize()")
        val success = result?.contains("\"success\":true") == true
        _authorized.value = success
        return success
    }

    /** Search the Apple Music catalog. */
    suspend fun search(query: String, limit: Int = 10): List<AppleMusicSearchResult> {
        val storefront = settingsStore.getAppleMusicStorefront() ?: "us"
        val escaped = query.replace("'", "\\'").replace("\\", "\\\\")
        val result = evaluate("search('$escaped', $limit, '$storefront')") ?: return emptyList()

        return try {
            val response = json.decodeFromString<MusicKitSearchResponse>(cleanJsString(result))
            response.results
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse search results: ${e.message}")
            emptyList()
        }
    }

    /** Play a song by Apple Music catalog ID. */
    suspend fun play(songId: String): Boolean {
        val result = evaluate("play('$songId')")
        return result?.contains("\"success\":true") == true
    }

    suspend fun pause(): Boolean {
        val result = evaluate("pause()")
        return result?.contains("\"success\":true") == true
    }

    suspend fun resume(): Boolean {
        val result = evaluate("resume()")
        return result?.contains("\"success\":true") == true
    }

    suspend fun seekTo(positionMs: Long): Boolean {
        val result = evaluate("seekTo($positionMs)")
        return result?.contains("\"success\":true") == true
    }

    suspend fun stop(): Boolean {
        val result = evaluate("stop()")
        return result?.contains("\"success\":true") == true
    }

    fun getPosition(): Long = _position.value
    fun getDuration(): Long = _duration.value
    fun getIsPlaying(): Boolean = _isPlaying.value

    fun teardown() {
        webView?.destroy()
        webView = null
        _ready.value = false
        _configured.value = false
    }

    // ── Internal ─────────────────────────────────────────────────

    private suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView ?: run {
            Log.w(TAG, "WebView not initialized, cannot evaluate: $script")
            return@withContext null
        }
        val deferred = CompletableDeferred<String?>()
        wv.evaluateJavascript("(async () => { return await $script; })()") { result ->
            deferred.complete(result)
        }
        deferred.await()
    }

    /** Strip JS string quotes from evaluateJavascript result. */
    private fun cleanJsString(raw: String): String {
        // evaluateJavascript wraps strings in quotes and escapes internal quotes
        return if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else raw
    }

    // ── JS → Kotlin callbacks ────────────────────────────────────

    inner class MusicKitJsInterface {
        @JavascriptInterface
        fun onPlaybackStateChange(jsonStr: String) {
            try {
                val state = json.decodeFromString<MusicKitPlaybackState>(jsonStr)
                _isPlaying.value = state.isPlaying
                _position.value = state.position.toLong()
                _duration.value = state.duration.toLong()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse playback state: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onAuthChange(jsonStr: String) {
            Log.d(TAG, "Auth change: $jsonStr")
            try {
                val auth = json.decodeFromString<MusicKitAuthState>(jsonStr)
                _authorized.value = auth.authorized
                _configured.value = auth.configured
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun onNowPlayingChange(jsonStr: String) {
            Log.d(TAG, "Now playing: $jsonStr")
        }

        @JavascriptInterface
        fun onTrackEnded(jsonStr: String) {
            Log.d(TAG, "Track ended")
            onTrackEnded?.invoke()
        }
    }
}

// ── Response models ──────────────────────────────────────────────

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
```

**Step 2: Add SettingsStore methods for Apple Music**

In `SettingsStore.kt`, add these methods:

```kotlin
// Apple Music
suspend fun getAppleMusicDeveloperToken(): String? =
    dataStore.data.first()[APPLE_MUSIC_DEVELOPER_TOKEN]

suspend fun setAppleMusicDeveloperToken(token: String) {
    dataStore.edit { it[APPLE_MUSIC_DEVELOPER_TOKEN] = token }
}

suspend fun getAppleMusicStorefront(): String? =
    dataStore.data.first()[APPLE_MUSIC_STOREFRONT]

suspend fun setAppleMusicStorefront(storefront: String) {
    dataStore.edit { it[APPLE_MUSIC_STOREFRONT] = storefront }
}
```

And the preference keys:

```kotlin
val APPLE_MUSIC_DEVELOPER_TOKEN = stringPreferencesKey("apple_music_developer_token")
val APPLE_MUSIC_STOREFRONT = stringPreferencesKey("apple_music_storefront")
```

**Step 3: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add MusicKitWebBridge for Kotlin-WebView communication"
```

---

## Task 4: Create AppleMusicPlaybackHandler

**Files:**
- Create: `app/src/main/java/com/parachord/android/playback/handlers/AppleMusicPlaybackHandler.kt`
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackRouter.kt`

**Step 1: Implement the handler**

Apple Music uses `ExternalPlaybackHandler` like Spotify — playback is managed by MusicKit JS inside the WebView, not ExoPlayer.

```kotlin
package com.parachord.android.playback.handlers

import android.util.Log
import com.parachord.android.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback handler for Apple Music tracks via MusicKit JS WebView.
 *
 * Like Spotify, Apple Music manages its own playback lifecycle externally
 * (inside the WebView's audio context). Position/state updates flow back
 * to Kotlin via @JavascriptInterface callbacks.
 */
@Singleton
class AppleMusicPlaybackHandler @Inject constructor(
    private val musicKitBridge: MusicKitWebBridge,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "AppleMusicHandler"
    }

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "applemusic" && track.appleMusicId != null

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    override suspend fun play(track: TrackEntity) {
        val songId = track.appleMusicId ?: run {
            Log.w(TAG, "No appleMusicId for track: ${track.title}")
            return
        }

        // Ensure MusicKit is configured
        if (!musicKitBridge.configured.value) {
            if (!musicKitBridge.configure()) {
                Log.w(TAG, "MusicKit not configured, cannot play")
                return
            }
        }

        // Ensure authorized
        if (!musicKitBridge.authorized.value) {
            if (!musicKitBridge.authorize()) {
                Log.w(TAG, "MusicKit not authorized, cannot play")
                return
            }
        }

        Log.d(TAG, "Playing Apple Music song: $songId (${track.title})")
        musicKitBridge.play(songId)
    }

    override suspend fun pause() { musicKitBridge.pause() }
    override suspend fun resume() { musicKitBridge.resume() }
    override suspend fun seekTo(positionMs: Long) { musicKitBridge.seekTo(positionMs) }
    override suspend fun stop() { musicKitBridge.stop() }

    override val isConnected: Boolean
        get() = musicKitBridge.configured.value

    // State accessors for PlaybackController polling
    fun getPosition(): Long = musicKitBridge.getPosition()
    fun getDuration(): Long = musicKitBridge.getDuration()
    fun isPlaying(): Boolean = musicKitBridge.getIsPlaying()

    /**
     * Track completion detection.
     * MusicKit JS fires mediaItemDidEndPlaying, so this is primarily a safety net.
     */
    fun isOurTrackDone(): Boolean {
        val duration = getDuration()
        val position = getPosition()
        if (duration <= 0) return false
        // Near end: within 1.5s of duration while not playing
        return !isPlaying() && position > 0 && duration - position < 1500
    }
}
```

**Step 2: Add Apple Music handler to PlaybackRouter**

In `PlaybackRouter.kt`:

1. Add constructor parameter:
```kotlin
class PlaybackRouter @Inject constructor(
    private val spotifyHandler: SpotifyPlaybackHandler,
    private val soundCloudHandler: SoundCloudPlaybackHandler,
    private val appleMusicHandler: AppleMusicPlaybackHandler,
)
```

2. Add to handlers list (after Spotify, before SoundCloud — matching canonical priority):
```kotlin
private val handlers: List<SourceHandler>
    get() = listOf(spotifyHandler, appleMusicHandler, soundCloudHandler, localFileHandler, directStreamHandler)
```

3. Update the `route()` method to handle Apple Music like Spotify:
```kotlin
// Spotify and Apple Music are external playback
if (handler is ExternalPlaybackHandler) {
    stopExternalPlayback()
    activeExternalHandler = handler
    return PlaybackAction.ExternalPlayback(handler)
}
```

4. Add accessor:
```kotlin
fun getAppleMusicHandler(): AppleMusicPlaybackHandler = appleMusicHandler
```

**Step 3: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add -A && git commit -m "feat: add AppleMusicPlaybackHandler with WebView-based playback"
```

---

## Task 5: Add Apple Music to ResolverManager

**Files:**
- Modify: `app/src/main/java/com/parachord/android/resolver/ResolverManager.kt`

**Step 1: Register the Apple Music resolver**

Add to `_resolvers` list:
```kotlin
private val _resolvers = MutableStateFlow(
    listOf(
        ResolverInfo(id = "spotify", name = "Spotify", enabled = true),
        ResolverInfo(id = "applemusic", name = "Apple Music", enabled = true),
        ResolverInfo(id = "soundcloud", name = "SoundCloud", enabled = true),
    )
)
```

**Step 2: Add MusicKitWebBridge dependency**

Add to constructor:
```kotlin
class ResolverManager @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val musicKitBridge: MusicKitWebBridge,
)
```

**Step 3: Add Apple Music to resolve pipeline**

In `resolve()`, add Apple Music task:
```kotlin
if (activeResolvers.isEmpty() || "applemusic" in activeResolvers) {
    add(async { resolveAppleMusic(query) })
}
```

**Step 4: Implement resolveAppleMusic**

Two-tier resolution: MusicKit JS (if configured) → iTunes Search API (fallback).

```kotlin
// ── Apple Music Resolver ────────────────────────────────────────

private suspend fun resolveAppleMusic(query: String): ResolvedSource? {
    // Tier 1: MusicKit JS (requires developer token + auth)
    if (musicKitBridge.configured.value) {
        try {
            val results = musicKitBridge.search(query, limit = 5)
            val best = results.firstOrNull()
            if (best != null) {
                Log.d(TAG, "Apple Music (MusicKit) matched '${best.title}' by ${best.artist}")
                return ResolvedSource(
                    url = best.appleMusicUrl ?: "applemusic:song:${best.id}",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = best.id,
                    confidence = 0.9,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "MusicKit search failed, falling back to iTunes: ${e.message}")
        }
    }

    // Tier 2: iTunes Search API (no auth, metadata-only)
    return resolveViaiTunes(query)
}

/**
 * iTunes Search API fallback — no auth required.
 * Returns a result with appleMusicId but playback will only work
 * as 30-second preview unless MusicKit JS is configured.
 */
private suspend fun resolveViaiTunes(query: String): ResolvedSource? =
    withContext(Dispatchers.IO) {
        try {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("itunes.apple.com")
                .addPathSegment("search")
                .addQueryParameter("term", query)
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", "song")
                .addQueryParameter("limit", "5")
                .build()

            val request = Request.Builder().url(url).get().build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val result = json.decodeFromString<ITunesSearchResponse>(body)
            val best = result.results.firstOrNull() ?: return@withContext null

            Log.d(TAG, "Apple Music (iTunes) matched '${best.trackName}' by ${best.artistName}")

            ResolvedSource(
                url = best.trackViewUrl ?: "applemusic:song:${best.trackId}",
                sourceType = "applemusic",
                resolver = "applemusic",
                appleMusicId = best.trackId.toString(),
                confidence = 0.85, // Slightly lower than MusicKit (less precise matching)
            )
        } catch (e: Exception) {
            Log.w(TAG, "iTunes search failed for '$query': ${e.message}")
            null
        }
    }
```

**Step 5: Add iTunes API response models**

At the bottom of `ResolverManager.kt`:

```kotlin
// ── iTunes Search API Models ──────────────────────────────────────

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesTrack> = emptyList(),
)

@Serializable
private data class ITunesTrack(
    val trackId: Long,
    val trackName: String,
    val artistName: String,
    val collectionName: String? = null,
    val trackViewUrl: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val previewUrl: String? = null,
)
```

**Step 6: Add to resolveWithHints**

Add Apple Music ID hint support:

```kotlin
suspend fun resolveWithHints(
    query: String,
    spotifyId: String? = null,
    soundcloudId: String? = null,
    appleMusicId: String? = null,
): List<ResolvedSource> = coroutineScope {
    // ... existing Spotify and SoundCloud hint handling ...

    // If we have an Apple Music ID, use it directly
    if (appleMusicId != null) {
        results.add(
            ResolvedSource(
                url = "applemusic:song:$appleMusicId",
                sourceType = "applemusic",
                resolver = "applemusic",
                appleMusicId = appleMusicId,
                confidence = 0.95,
            )
        )
    }

    // ... rest of method ...
}
```

**Step 7: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add -A && git commit -m "feat: add Apple Music to resolver pipeline with MusicKit + iTunes fallback"
```

---

## Task 6: Wire Apple Music Playback State in PlaybackController

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`

**Step 1: Add Apple Music state polling**

Follow the same pattern as Spotify: when external playback is routed to `AppleMusicPlaybackHandler`, poll its state and detect track completion.

In `PlaybackController`, add alongside the existing Spotify polling:

```kotlin
// In playTrackInternal(), after routing:
if (handler is AppleMusicPlaybackHandler) {
    startAppleMusicStatePolling()
}
```

Add the polling method (mirrors `startSpotifyStatePolling()`):

```kotlin
private var appleMusicPollingJob: Job? = null

private fun startAppleMusicStatePolling() {
    appleMusicPollingJob?.cancel()
    val handler = router.getAppleMusicHandler()

    // Register track-ended callback
    handler.musicKitBridge.onTrackEnded = {
        scope.launch { skipNext() }
    }

    appleMusicPollingJob = scope.launch {
        while (true) {
            delay(500)
            val position = handler.getPosition()
            val duration = handler.getDuration()
            val isPlaying = handler.isPlaying()

            updateState {
                it.copy(
                    position = position,
                    duration = duration,
                    isPlaying = isPlaying,
                )
            }

            // Safety-net track completion detection
            if (handler.isOurTrackDone()) {
                Log.d(TAG, "Apple Music track done (safety net)")
                skipNext()
                break
            }
        }
    }
}
```

Cancel the polling when switching away:
```kotlin
// In playTrackInternal(), before routing a new track:
appleMusicPollingJob?.cancel()
```

**Step 2: Build to verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: wire Apple Music playback state polling in PlaybackController"
```

---

## Task 7: Apple Music Settings UI

**Files:**
- Modify: Settings screen (wherever Apple Music configuration belongs)

**Step 1: Add Apple Music section to Settings**

Add UI fields for:
- Developer Token (text input, saved to SettingsStore)
- Storefront / Country (dropdown or text, default "us")
- Authorization status indicator
- "Sign In with Apple ID" button (calls `musicKitBridge.authorize()`)

This mirrors the desktop's configurable settings in the resolver manifest:
```json
"configurable": {
    "developerToken": { "type": "string", "label": "Developer Token" },
    "storefront": { "type": "string", "label": "Country", "default": "us" }
}
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A && git commit -m "feat: add Apple Music settings for developer token and storefront"
```

---

## Verification Checklist

1. `./gradlew assembleDebug` passes after each task
2. Apple Music appears in resolver list with correct icon (already defined in `ResolverIcon.kt`)
3. With developer token configured:
   - Search returns Apple Music results alongside Spotify/SoundCloud
   - Apple Music results show the correct resolver icon badge
   - Selecting an Apple Music result plays via MusicKit JS
   - Play/pause/seek controls work
   - Track auto-advances on completion
4. Without developer token:
   - iTunes Search API returns metadata results
   - Results have `appleMusicId` for potential future playback
5. Resolver priority/scoring respects user's configured order
6. Volume offset for `applemusic` (0 dB default) is applied correctly

## Key Risks

- **WebView audio session**: MusicKit plays audio inside the WebView. Need to test that it coexists with ExoPlayer for other resolvers without audio focus conflicts.
- **MusicKit JS authorization**: The OAuth flow opens in a browser. On Android WebView, this may require `WebChromeClient.onCreateWindow()` or redirecting to a Custom Tab. Test and adjust as needed.
- **DRM requirements**: Apple Music streams require DRM (FairPlay). MusicKit JS handles this internally, but it must run in a WebView with media capabilities (Widevine L3 minimum).
- **Rate limiting**: iTunes Search API has undocumented rate limits. The desktop uses a 1.2s minimum between requests with exponential backoff — implement the same if needed.

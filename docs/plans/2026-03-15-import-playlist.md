# Import Playlist Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to import playlists from Spotify URLs, Apple Music URLs, hosted .xspf files, and local .xspf files — matching the desktop app's Import Playlist dialog.

**Architecture:** An `ImportPlaylistDialog` UI (URL input + file picker) dispatches to a `PlaylistImportManager` that detects URL type, fetches metadata via existing APIs (SpotifyApi, MusicKit bridge, OkHttp for XSPF), parses results into `TrackEntity` lists, and saves via `LibraryRepository.createPlaylistWithTracks()`. XSPF parsing uses Android's built-in `XmlPullParser`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, MusicKit JS WebView bridge, Retrofit (SpotifyApi), OkHttp (XSPF fetch), XmlPullParser (XSPF parse), Room (playlist storage).

---

## Task 1: Create XspfParser

**Files:**
- Create: `app/src/main/java/com/parachord/android/playlist/XspfParser.kt`

Parses XSPF XML (standard playlist format) into a playlist name + track list. Uses Android's `XmlPullParser` (no external dependency). Matches the desktop's `parseXSPF()` function in app.js:23917-23950.

**Implementation:**

```kotlin
package com.parachord.android.playlist

import android.util.Xml
import com.parachord.android.data.db.entity.TrackEntity
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class XspfPlaylist(
    val title: String,
    val creator: String?,
    val tracks: List<TrackEntity>,
)

/**
 * Parse XSPF (XML Shareable Playlist Format) into a playlist.
 * Matches the desktop's parseXSPF() function.
 *
 * XSPF spec: https://xspf.org/spec
 * Desktop equivalent: app.js parseXSPF() lines 23917-23950
 */
object XspfParser {

    fun parse(xspfContent: String): XspfPlaylist {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xspfContent))

        var playlistTitle = "Imported Playlist"
        var playlistCreator: String? = null
        val tracks = mutableListOf<TrackEntity>()

        // Current track being parsed
        var inTrack = false
        var trackTitle: String? = null
        var trackArtist: String? = null
        var trackAlbum: String? = null
        var trackDuration: Long? = null
        var trackLocation: String? = null
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "track") {
                        inTrack = true
                        trackTitle = null
                        trackArtist = null
                        trackAlbum = null
                        trackDuration = null
                        trackLocation = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isEmpty()) {
                        // skip
                    } else if (inTrack) {
                        when (currentTag) {
                            "title" -> trackTitle = text
                            "creator" -> trackArtist = text
                            "album" -> trackAlbum = text
                            "duration" -> trackDuration = text.toLongOrNull()?.let { it / 1000 } // ms → seconds stored as ms internally... keep as ms
                            "location" -> trackLocation = text
                        }
                    } else {
                        when (currentTag) {
                            "title" -> playlistTitle = text
                            "creator" -> playlistCreator = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "track" && inTrack) {
                        val title = trackTitle ?: "Unknown"
                        val artist = trackArtist ?: "Unknown"
                        tracks.add(
                            TrackEntity(
                                id = "xspf:${artist}:${title}:${tracks.size}",
                                title = title,
                                artist = artist,
                                album = trackAlbum,
                                duration = trackDuration, // XSPF stores ms, we store ms
                                sourceUrl = trackLocation,
                                sourceType = if (trackLocation != null) "stream" else null,
                            )
                        )
                        inTrack = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return XspfPlaylist(
            title = playlistTitle,
            creator = playlistCreator,
            tracks = tracks,
        )
    }
}
```

**Step 1:** Write the file.
**Step 2:** `./gradlew assembleDebug` — verify it compiles.
**Step 3:** Commit.

---

## Task 2: Add getPlaylist to MusicKit JS Bridge

**Files:**
- Modify: `app/src/main/assets/js/musickit-bridge.html` — add `getPlaylist()` JS function
- Modify: `app/src/main/java/com/parachord/android/playback/handlers/MusicKitWebBridge.kt` — add Kotlin `getPlaylist()` method

The MusicKit JS API supports fetching playlists via `/v1/catalog/{storefront}/playlists/{id}?include=tracks`. We add a JS function and a Kotlin wrapper matching the existing `search()` pattern.

**JS function to add** (after the `search()` function in musickit-bridge.html):

```javascript
    /**
     * Get playlist tracks by Apple Music playlist ID.
     * Returns JSON: { success: bool, name: string, tracks: [...], error?: string }
     */
    async function getPlaylist(playlistId, storefront) {
        try {
            if (!music) return JSON.stringify({ success: false, error: 'Not configured' });
            var sf = storefront || 'us';
            var response = await music.api.music(
                '/v1/catalog/' + sf + '/playlists/' + playlistId,
                { include: 'tracks' }
            );
            var playlist = response.data.data[0];
            if (!playlist) return JSON.stringify({ success: false, error: 'Playlist not found' });
            var name = playlist.attributes.name || 'Untitled Playlist';
            var trackData = (playlist.relationships && playlist.relationships.tracks &&
                             playlist.relationships.tracks.data) || [];
            var tracks = trackData.map(function(song) {
                var artwork = song.attributes.artwork;
                var artworkUrl = null;
                if (artwork && artwork.url) {
                    artworkUrl = artwork.url.replace('{w}', '600').replace('{h}', '600');
                }
                return {
                    id: song.id,
                    title: song.attributes.name,
                    artist: song.attributes.artistName,
                    album: song.attributes.albumName || null,
                    duration: song.attributes.durationInMillis || null,
                    artworkUrl: artworkUrl,
                };
            });
            return JSON.stringify({ success: true, name: name, tracks: tracks });
        } catch (e) {
            return JSON.stringify({ success: false, error: e.message || String(e) });
        }
    }
```

**Kotlin wrapper to add** (in MusicKitWebBridge.kt, after the `search()` method):

```kotlin
    data class AppleMusicPlaylistResult(
        val name: String,
        val tracks: List<AppleMusicSearchResult>,
    )

    /**
     * Fetch an Apple Music playlist's tracks by catalog playlist ID.
     * Returns null if MusicKit is not configured or the playlist can't be found.
     */
    suspend fun getPlaylist(playlistId: String): AppleMusicPlaylistResult? {
        musicKitReady.await()
        val storefront = settingsStore.getAppleMusicStorefront() ?: "us"
        val escaped = playlistId.replace("'", "\\'")
        val result = evaluate("getPlaylist('$escaped', '$storefront')") ?: return null
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val response = json.decodeFromString<GetPlaylistResponse>(result)
            if (!response.success || response.tracks.isNullOrEmpty()) return null
            AppleMusicPlaylistResult(
                name = response.name ?: "Playlist",
                tracks = response.tracks.map { t ->
                    AppleMusicSearchResult(
                        id = t.id,
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        duration = t.duration,
                        artworkUrl = t.artworkUrl,
                        isrc = null,
                        previewUrl = null,
                        appleMusicUrl = null,
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse getPlaylist response: ${e.message}")
            null
        }
    }

    @kotlinx.serialization.Serializable
    private data class GetPlaylistResponse(
        val success: Boolean,
        val name: String? = null,
        val tracks: List<GetPlaylistTrack>? = null,
        val error: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class GetPlaylistTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: Long? = null,
        val artworkUrl: String? = null,
    )
```

**Step 1:** Add JS function to musickit-bridge.html.
**Step 2:** Add Kotlin wrapper to MusicKitWebBridge.kt. Check the existing `AppleMusicSearchResult` data class — reuse it.
**Step 3:** `./gradlew assembleDebug` — verify it compiles.
**Step 4:** Commit.

---

## Task 3: Create PlaylistImportManager

**Files:**
- Create: `app/src/main/java/com/parachord/android/playlist/PlaylistImportManager.kt`

Central orchestrator that detects URL type, fetches metadata, and saves the imported playlist. Injects `SpotifyApi`, `MusicKitWebBridge`, `LibraryRepository`, `SettingsStore`, `OkHttpClient`.

```kotlin
package com.parachord.android.playlist

import android.util.Log
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlaylistImportManager"

data class ImportResult(
    val playlistId: String,
    val playlistName: String,
    val trackCount: Int,
)

@Singleton
class PlaylistImportManager @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val musicKitBridge: MusicKitWebBridge,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val httpClient: OkHttpClient,
) {
    /**
     * Import a playlist from a URL.
     * Detects type (Spotify, Apple Music, hosted XSPF) and fetches accordingly.
     *
     * Desktop equivalent: handleImportPlaylistFromUrl() in app.js:30973-31102
     */
    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Importing playlist from URL: $url")

        when {
            isSpotifyPlaylistUrl(url) -> importSpotifyPlaylist(url)
            isAppleMusicPlaylistUrl(url) -> importAppleMusicPlaylist(url)
            else -> importXspfFromUrl(url)  // Assume hosted XSPF
        }
    }

    /**
     * Import a playlist from local XSPF file content.
     *
     * Desktop equivalent: handleImportPlaylist() in main.js:4352-4393
     */
    suspend fun importFromXspfContent(content: String, filename: String? = null): ImportResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Importing playlist from XSPF content (filename=$filename)")
            val parsed = XspfParser.parse(content)
            savePlaylist(
                name = parsed.title,
                source = "imported-xspf",
                tracks = parsed.tracks,
                artworkUrl = null,
            )
        }

    // ── URL Detection ─────────────────────────────────────────────

    private fun isSpotifyPlaylistUrl(url: String): Boolean =
        url.contains("open.spotify.com/playlist/") || url.startsWith("spotify:playlist:")

    private fun isAppleMusicPlaylistUrl(url: String): Boolean =
        url.contains("music.apple.com") && url.contains("/playlist/")

    // ── Spotify Import ────────────────────────────────────────────

    private suspend fun importSpotifyPlaylist(url: String): ImportResult {
        val playlistId = extractSpotifyPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Spotify playlist ID from URL")

        val token = settingsStore.getSpotifyAccessToken()
            ?: throw IllegalStateException("Spotify not connected. Connect Spotify in Settings to import playlists.")
        val auth = "Bearer $token"

        val playlist = spotifyApi.getPlaylist(auth, playlistId)
        val playlistName = playlist.name ?: "Spotify Playlist"
        val artworkUrl = playlist.images?.firstOrNull()?.url

        // Fetch all tracks (paginate if needed)
        val allTracks = mutableListOf<TrackEntity>()
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val page = spotifyApi.getPlaylistTracks(auth, playlistId, limit = 100, offset = offset)
            page.items.forEach { item ->
                val track = item.track ?: return@forEach
                allTracks.add(
                    TrackEntity(
                        id = "spotify:${track.id}",
                        title = track.name ?: "Unknown",
                        artist = track.artistName,
                        album = track.album?.name,
                        albumId = track.album?.id,
                        duration = track.durationMs,
                        artworkUrl = track.album?.images.bestImageUrl(),
                        resolver = "spotify",
                        spotifyId = track.id,
                        spotifyUri = "spotify:track:${track.id}",
                    )
                )
            }
            offset += page.items.size
            hasMore = page.next != null && page.items.isNotEmpty()
        }

        return savePlaylist(
            name = playlistName,
            source = "spotify-import",
            tracks = allTracks,
            artworkUrl = artworkUrl,
        )
    }

    private fun extractSpotifyPlaylistId(url: String): String? {
        // spotify:playlist:37i9dQZF1DXcBWIGoYBM5M
        if (url.startsWith("spotify:playlist:")) {
            return url.removePrefix("spotify:playlist:")
        }
        // https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=xxx
        val regex = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    // ── Apple Music Import ────────────────────────────────────────

    private suspend fun importAppleMusicPlaylist(url: String): ImportResult {
        val playlistId = extractAppleMusicPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Apple Music playlist ID from URL")

        if (!musicKitBridge.configured.value) {
            throw IllegalStateException("Apple Music not configured. Set up Apple Music in Settings to import playlists.")
        }

        val result = musicKitBridge.getPlaylist(playlistId)
            ?: throw IllegalStateException("Could not load Apple Music playlist. It may be private or unavailable in your region.")

        val tracks = result.tracks.mapIndexed { index, t ->
            TrackEntity(
                id = "applemusic:${t.id}",
                title = t.title,
                artist = t.artist,
                album = t.album,
                duration = t.duration,
                artworkUrl = t.artworkUrl,
                resolver = "applemusic",
                appleMusicId = t.id,
            )
        }

        return savePlaylist(
            name = result.name,
            source = "applemusic-import",
            tracks = tracks,
            artworkUrl = tracks.firstOrNull()?.artworkUrl,
        )
    }

    private fun extractAppleMusicPlaylistId(url: String): String? {
        // https://music.apple.com/us/playlist/some-name/pl.xxx
        val regex = Regex("music\\.apple\\.com/[a-z]{2}/playlist/[^/]+/(pl\\.[a-zA-Z0-9]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    // ── XSPF Import from URL ──────────────────────────────────────

    private suspend fun importXspfFromUrl(url: String): ImportResult {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch XSPF: HTTP ${response.code}")
        }
        val content = response.body?.string()
            ?: throw IllegalStateException("Empty response from XSPF URL")
        if (!content.contains("<playlist") || !content.contains("</playlist>")) {
            throw IllegalArgumentException("URL does not contain a valid XSPF playlist")
        }
        val parsed = XspfParser.parse(content)
        return savePlaylist(
            name = parsed.title,
            source = "hosted-xspf",
            tracks = parsed.tracks,
            artworkUrl = null,
        )
    }

    // ── Save Playlist ─────────────────────────────────────────────

    private suspend fun savePlaylist(
        name: String,
        source: String,
        tracks: List<TrackEntity>,
        artworkUrl: String?,
    ): ImportResult {
        val playlistId = "${source}-${UUID.randomUUID()}"
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            artworkUrl = artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
            trackCount = tracks.size,
        )
        libraryRepository.createPlaylistWithTracks(playlist, tracks)
        Log.d(TAG, "Imported playlist '$name' with ${tracks.size} tracks (id=$playlistId)")
        return ImportResult(
            playlistId = playlistId,
            playlistName = name,
            trackCount = tracks.size,
        )
    }
}
```

**Step 1:** Write the file.
**Step 2:** `./gradlew assembleDebug` — verify it compiles.
**Step 3:** Commit.

---

## Task 4: Create ImportPlaylistDialog UI

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/components/ImportPlaylistDialog.kt`

A dialog matching the desktop screenshot: URL input field with Import button, divider with "or", and file picker button. Uses the app's purple accent color.

```kotlin
package com.parachord.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val Purple = Color(0xFF7C3AED)

@Composable
fun ImportPlaylistDialog(
    onDismiss: () -> Unit,
    onImportUrl: (String) -> Unit,
    onImportFile: (Uri) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null,
) {
    var url by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { onImportFile(it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Import Playlist",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Import from URL section ──
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Purple, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Import from URL",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Spotify, Apple Music, or hosted .xspf",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                placeholder = { Text("Paste playlist URL...") },
                                singleLine = true,
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(
                                    onGo = { if (url.isNotBlank()) onImportUrl(url.trim()) },
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Purple,
                                    cursorColor = Purple,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { if (url.isNotBlank()) onImportUrl(url.trim()) },
                                enabled = url.isNotBlank() && !isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Import")
                                }
                            }
                        }
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Divider with "or" ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text(
                        "or",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── Load from File section ──
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLoading) {
                            filePicker.launch(arrayOf("application/xspf+xml", "text/xml", "application/xml", "*/*"))
                        }
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp),
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Load from File",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Choose an .xspf file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
```

**Step 1:** Write the file.
**Step 2:** `./gradlew assembleDebug` — verify it compiles.
**Step 3:** Commit.

---

## Task 5: Wire Import Dialog into MainActivity

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/MainViewModel.kt` — add import methods
- Modify: `app/src/main/java/com/parachord/android/ui/MainActivity.kt` — show dialog, handle callbacks

**Changes to MainViewModel:**

Add `PlaylistImportManager` injection and import methods:

```kotlin
@Inject constructor(
    ...
    private val playlistImportManager: PlaylistImportManager,
)

// Import state
private val _importLoading = MutableStateFlow(false)
val importLoading: StateFlow<Boolean> = _importLoading.asStateFlow()

private val _importError = MutableStateFlow<String?>(null)
val importError: StateFlow<String?> = _importError.asStateFlow()

fun importPlaylistFromUrl(url: String, onSuccess: (String) -> Unit) {
    viewModelScope.launch {
        _importLoading.value = true
        _importError.value = null
        try {
            val result = playlistImportManager.importFromUrl(url)
            _importLoading.value = false
            onSuccess(result.playlistId)
            _toastEvents.emit("Imported '${result.playlistName}' (${result.trackCount} tracks)")
        } catch (e: Exception) {
            _importLoading.value = false
            _importError.value = e.message ?: "Import failed"
        }
    }
}

fun importPlaylistFromFile(content: String, filename: String?, onSuccess: (String) -> Unit) {
    viewModelScope.launch {
        _importLoading.value = true
        _importError.value = null
        try {
            val result = playlistImportManager.importFromXspfContent(content, filename)
            _importLoading.value = false
            onSuccess(result.playlistId)
            _toastEvents.emit("Imported '${result.playlistName}' (${result.trackCount} tracks)")
        } catch (e: Exception) {
            _importLoading.value = false
            _importError.value = e.message ?: "Import failed"
        }
    }
}

fun clearImportError() {
    _importError.value = null
}
```

**Changes to MainActivity ParachordAppContent:**

Replace the no-op `onImportPlaylist` callback with dialog state:

```kotlin
var showImportPlaylistDialog by remember { mutableStateOf(false) }

// In ActionOverlay:
onImportPlaylist = {
    showActionOverlay = false
    showImportPlaylistDialog = true
},

// After CreatePlaylistDialog block:
if (showImportPlaylistDialog) {
    val importLoading by mainViewModel.importLoading.collectAsStateWithLifecycle()
    val importError by mainViewModel.importError.collectAsStateWithLifecycle()
    val contentResolver = LocalContext.current.contentResolver

    ImportPlaylistDialog(
        onDismiss = {
            showImportPlaylistDialog = false
            mainViewModel.clearImportError()
        },
        onImportUrl = { url ->
            mainViewModel.importPlaylistFromUrl(url) { playlistId ->
                showImportPlaylistDialog = false
                navController.navigate(Routes.playlistDetail(playlistId)) {
                    launchSingleTop = true
                }
            }
        },
        onImportFile = { uri ->
            // Read .xspf file content from the content URI
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (content != null) {
                val filename = uri.lastPathSegment
                mainViewModel.importPlaylistFromFile(content, filename) { playlistId ->
                    showImportPlaylistDialog = false
                    navController.navigate(Routes.playlistDetail(playlistId)) {
                        launchSingleTop = true
                    }
                }
            }
        },
        isLoading = importLoading,
        errorMessage = importError,
    )
}
```

**Step 1:** Read MainViewModel.kt to find exact injection point and existing patterns.
**Step 2:** Add PlaylistImportManager injection and import methods to MainViewModel.
**Step 3:** Add dialog state and wiring to MainActivity.kt.
**Step 4:** `./gradlew assembleDebug` — verify it compiles.
**Step 5:** Commit.

---

## Task 6: Wire DeepLinkViewModel Import Action

**Files:**
- Modify: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt`

Update the `ImportPlaylist` handler to actually import instead of showing a toast:

```kotlin
is DeepLinkAction.ImportPlaylist -> {
    try {
        val result = playlistImportManager.importFromUrl(action.url)
        _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
        _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
    } catch (e: Exception) {
        _navEvents.emit(DeepLinkNavEvent.Toast("Import failed: ${e.message}"))
    }
}
```

Also update Spotify/Apple Music playlist deep link handlers to use import:

```kotlin
is DeepLinkAction.SpotifyPlaylist -> {
    try {
        val url = "https://open.spotify.com/playlist/${action.playlistId}"
        val result = playlistImportManager.importFromUrl(url)
        _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
        _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
    } catch (e: Exception) {
        _navEvents.emit(DeepLinkNavEvent.Toast("Could not import Spotify playlist: ${e.message}"))
    }
}

is DeepLinkAction.AppleMusicPlaylist -> {
    try {
        val url = "https://music.apple.com/us/playlist/imported/${action.playlistId}"
        val result = playlistImportManager.importFromUrl(url)
        _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
        _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
    } catch (e: Exception) {
        _navEvents.emit(DeepLinkNavEvent.Toast("Could not import Apple Music playlist: ${e.message}"))
    }
}
```

This requires adding `PlaylistImportManager` to DeepLinkViewModel's constructor.

**Step 1:** Add `PlaylistImportManager` injection to DeepLinkViewModel constructor.
**Step 2:** Update the three handler blocks.
**Step 3:** `./gradlew assembleDebug` — verify it compiles.
**Step 4:** Commit.

---

## Task 7: Build Verification & Final Commit

**Step 1:** `./gradlew assembleDebug` — must pass.
**Step 2:** Fix any compilation errors.
**Step 3:** Final commit with all files.

## Verification Checklist

1. `./gradlew assembleDebug` passes
2. Tap + → Import Playlist → dialog appears matching desktop screenshot
3. Paste Spotify playlist URL → imports tracks → navigates to playlist detail
4. Paste Apple Music playlist URL (with MusicKit configured) → imports tracks
5. Paste hosted .xspf URL → fetches and imports
6. Tap "Load from File" → file picker opens → select .xspf → imports
7. Error states: invalid URL, no Spotify token, empty playlist → shows error message
8. `parachord://import?url=https://...` deep link → imports and navigates
9. Opening a `https://open.spotify.com/playlist/xxx` link → imports as playlist

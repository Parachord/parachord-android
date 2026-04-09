# Protocol URLs & External Link Handling — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Support all `parachord://` protocol URLs and register as a handler for Spotify and Apple Music URLs — parsing them, fetching metadata, navigating to the right page, and resolving via the resolver pipeline.

**Architecture:** A central `DeepLinkHandler` class parses incoming URIs (parachord://, open.spotify.com/*, music.apple.com/*), maps them to navigation actions or playback commands, and dispatches accordingly. External URLs (Spotify/Apple Music) go through a metadata fetch step before navigation. Intent filters in the manifest register the app for all supported URL schemes.

**Tech Stack:** Kotlin, Hilt DI, Jetpack Navigation, Retrofit/OkHttp (Spotify Web API, iTunes Search API), Room (TrackEntity), existing ResolverManager + ResolverScoring pipeline.

---

## Task 1: Register Intent Filters in AndroidManifest.xml

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Add parachord:// scheme intent filter (all hosts)**

Add a new intent filter for the full `parachord://` scheme (not just `auth`):

```xml
<!-- Deep link for all Parachord protocol URLs -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="parachord" />
</intent-filter>
```

Note: The existing `parachord://auth` filter can remain — Android matches the more specific filter first for auth callbacks.

**Step 2: Add Spotify URL intent filters**

```xml
<!-- Spotify web URLs -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="open.spotify.com"
        android:pathPrefix="/track" />
    <data android:scheme="https" android:host="open.spotify.com"
        android:pathPrefix="/album" />
    <data android:scheme="https" android:host="open.spotify.com"
        android:pathPrefix="/playlist" />
    <data android:scheme="https" android:host="open.spotify.com"
        android:pathPrefix="/artist" />
</intent-filter>

<!-- Spotify URIs (spotify:track:xxx) -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="spotify" />
</intent-filter>
```

**Step 3: Add Apple Music URL intent filters**

```xml
<!-- Apple Music URLs -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="music.apple.com"
        android:pathPrefix="/" />
</intent-filter>
```

**Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register intent filters for parachord://, Spotify, and Apple Music URLs"
```

---

## Task 2: Create iTunes/Apple Music API Client

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/api/AppleMusicApi.kt`
- Create: `app/src/main/java/com/parachord/android/data/api/AppleMusicApiModule.kt`

The desktop app uses the iTunes Search/Lookup API (no auth required) for URL-based lookups. We need a Retrofit client for this.

**Step 1: Create AppleMusicApi interface**

```kotlin
package com.parachord.android.data.api

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search/Lookup API — no authentication required.
 * Used for looking up Apple Music content by ID (from shared URLs).
 *
 * Desktop equivalent: apple-music.axe → lookupUrl(), lookupAlbum()
 */
interface AppleMusicApi {

    /**
     * Look up a specific item by its Apple Music/iTunes ID.
     * For albums, use entity=song to get all tracks.
     *
     * @param id Apple Music catalog ID (e.g., "1440935467")
     * @param entity "song" to include album tracks, omit for just the item
     */
    @GET("lookup")
    suspend fun lookup(
        @Query("id") id: String,
        @Query("entity") entity: String? = null,
    ): AppleMusicLookupResponse

    /**
     * Search for content by term.
     *
     * @param term Search query
     * @param media "music" for songs/albums
     * @param entity "song", "album", "musicArtist"
     * @param limit Max results (default 25)
     */
    @GET("search")
    suspend fun search(
        @Query("term") term: String,
        @Query("media") media: String = "music",
        @Query("entity") entity: String = "song",
        @Query("limit") limit: Int = 25,
    ): AppleMusicSearchResponse
}

data class AppleMusicLookupResponse(
    val resultCount: Int,
    val results: List<AppleMusicItem>,
)

data class AppleMusicSearchResponse(
    val resultCount: Int,
    val results: List<AppleMusicItem>,
)

data class AppleMusicItem(
    val wrapperType: String? = null,       // "track", "collection", "artist"
    val kind: String? = null,              // "song", "album"
    val trackId: Long? = null,
    val collectionId: Long? = null,
    val artistId: Long? = null,
    val trackName: String? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val collectionType: String? = null,    // "Album"
)
```

**Step 2: Create Hilt module for AppleMusicApi**

```kotlin
package com.parachord.android.data.api

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppleMusicApiModule {

    @Provides
    @Singleton
    @Named("itunes")
    fun provideItunesRetrofit(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideAppleMusicApi(@Named("itunes") retrofit: Retrofit): AppleMusicApi =
        retrofit.create(AppleMusicApi::class.java)
}
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/parachord/android/data/api/AppleMusicApi.kt \
       app/src/main/java/com/parachord/android/data/api/AppleMusicApiModule.kt
git commit -m "feat: add iTunes/Apple Music API client for URL lookups"
```

---

## Task 3: Create DeepLinkHandler

**Files:**
- Create: `app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt`

This is the central class that parses all incoming URIs and returns a sealed class of actions. It does NOT perform navigation or playback directly — it returns an action that the Activity/ViewModel dispatches.

**Step 1: Create the DeepLinkAction sealed class and DeepLinkHandler**

```kotlin
package com.parachord.android.deeplink

import android.net.Uri
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeepLinkHandler"

/**
 * All possible actions from a deep link or external URL.
 */
sealed class DeepLinkAction {
    // ── Playback ──
    data class Play(val artist: String, val title: String) : DeepLinkAction()
    data class Control(val action: String) : DeepLinkAction()  // pause, resume, play, skip, next, previous
    data class QueueAdd(val artist: String, val title: String, val album: String?) : DeepLinkAction()
    data object QueueClear : DeepLinkAction()
    data class Shuffle(val enabled: Boolean) : DeepLinkAction()
    data class Volume(val level: Int) : DeepLinkAction()

    // ── Navigation ──
    data object NavigateHome : DeepLinkAction()
    data class NavigateArtist(val name: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateAlbum(val artist: String, val title: String) : DeepLinkAction()
    data class NavigateLibrary(val tab: String? = null) : DeepLinkAction()
    data class NavigateHistory(val tab: String? = null, val period: String? = null) : DeepLinkAction()
    data class NavigateFriend(val id: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateRecommendations(val tab: String? = null) : DeepLinkAction()
    data object NavigateCharts : DeepLinkAction()
    data object NavigateCriticalDarlings : DeepLinkAction()
    data object NavigatePlaylists : DeepLinkAction()
    data class NavigatePlaylist(val id: String) : DeepLinkAction()
    data class NavigateSettings(val tab: String? = null) : DeepLinkAction()
    data class NavigateSearch(val query: String?, val source: String? = null) : DeepLinkAction()
    data class NavigateChat(val prompt: String? = null) : DeepLinkAction()

    // ── Import ──
    data class ImportPlaylist(val url: String) : DeepLinkAction()

    // ── External URL lookups ──
    data class SpotifyTrack(val trackId: String) : DeepLinkAction()
    data class SpotifyAlbum(val albumId: String) : DeepLinkAction()
    data class SpotifyPlaylist(val playlistId: String) : DeepLinkAction()
    data class SpotifyArtist(val artistId: String) : DeepLinkAction()
    data class AppleMusicSong(val songId: String) : DeepLinkAction()
    data class AppleMusicAlbum(val albumId: String) : DeepLinkAction()
    data class AppleMusicPlaylist(val playlistId: String) : DeepLinkAction()

    // ── Auth (existing, pass-through) ──
    data class OAuthCallback(val uri: Uri) : DeepLinkAction()

    // ── Unknown ──
    data class Unknown(val uri: Uri) : DeepLinkAction()
}

/**
 * Parses incoming URIs into [DeepLinkAction]s.
 *
 * Supports:
 * - parachord:// protocol URLs (play, control, queue, shuffle, volume, navigation, import)
 * - Spotify web URLs (open.spotify.com/track/*, /album/*, /playlist/*, /artist/*)
 * - Spotify URIs (spotify:track:*, spotify:album:*, etc.)
 * - Apple Music URLs (music.apple.com/.../album/*, /song/*, /playlist/*)
 *
 * Desktop equivalent: protocol URL handler in app.js:9648-10122,
 * external URL parsing in resolver-loader.js:217-413
 */
@Singleton
class DeepLinkHandler @Inject constructor() {

    fun parse(uri: Uri): DeepLinkAction {
        Log.d(TAG, "Parsing URI: $uri")

        return when (uri.scheme) {
            "parachord" -> parseParachord(uri)
            "spotify" -> parseSpotifyUri(uri)
            "https", "http" -> parseHttpUrl(uri)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    // ── parachord:// ──────────────────────────────────────────────────

    private fun parseParachord(uri: Uri): DeepLinkAction {
        val host = uri.host ?: return DeepLinkAction.Unknown(uri)
        val pathSegments = uri.pathSegments

        return when (host) {
            "auth" -> DeepLinkAction.OAuthCallback(uri)

            "play" -> {
                val artist = uri.getQueryParameter("artist") ?: return DeepLinkAction.Unknown(uri)
                val title = uri.getQueryParameter("title") ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Play(artist, title)
            }

            "control" -> {
                val action = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Control(action)
            }

            "queue" -> {
                when (pathSegments.firstOrNull()) {
                    "add" -> {
                        val artist = uri.getQueryParameter("artist") ?: return DeepLinkAction.Unknown(uri)
                        val title = uri.getQueryParameter("title") ?: return DeepLinkAction.Unknown(uri)
                        val album = uri.getQueryParameter("album")
                        DeepLinkAction.QueueAdd(artist, title, album)
                    }
                    "clear" -> DeepLinkAction.QueueClear
                    else -> DeepLinkAction.Unknown(uri)
                }
            }

            "shuffle" -> {
                val state = pathSegments.firstOrNull()
                when (state) {
                    "on" -> DeepLinkAction.Shuffle(enabled = true)
                    "off" -> DeepLinkAction.Shuffle(enabled = false)
                    else -> DeepLinkAction.Unknown(uri)
                }
            }

            "volume" -> {
                val level = pathSegments.firstOrNull()?.toIntOrNull()
                    ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Volume(level.coerceIn(0, 100))
            }

            // ── Navigation hosts ──
            "home" -> DeepLinkAction.NavigateHome
            "artist" -> {
                val name = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateArtist(name, tab)
            }
            "album" -> {
                val artist = pathSegments.getOrNull(0) ?: return DeepLinkAction.Unknown(uri)
                val title = pathSegments.getOrNull(1) ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.NavigateAlbum(artist, title)
            }
            "library" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateLibrary(tab)
            }
            "history" -> {
                val tab = pathSegments.firstOrNull()
                val period = uri.getQueryParameter("period")
                DeepLinkAction.NavigateHistory(tab, period)
            }
            "friend" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateFriend(id, tab)
            }
            "recommendations" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateRecommendations(tab)
            }
            "charts" -> DeepLinkAction.NavigateCharts
            "critics-picks" -> DeepLinkAction.NavigateCriticalDarlings
            "playlists" -> DeepLinkAction.NavigatePlaylists
            "playlist" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.NavigatePlaylist(id)
            }
            "settings" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateSettings(tab)
            }
            "search" -> {
                val query = uri.getQueryParameter("q")
                val source = uri.getQueryParameter("source")
                DeepLinkAction.NavigateSearch(query, source)
            }
            "chat" -> {
                val prompt = uri.getQueryParameter("prompt")
                DeepLinkAction.NavigateChat(prompt)
            }
            "import" -> {
                val url = uri.getQueryParameter("url") ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.ImportPlaylist(url)
            }

            else -> DeepLinkAction.Unknown(uri)
        }
    }

    // ── spotify:track:xxx etc. ────────────────────────────────────────

    private fun parseSpotifyUri(uri: Uri): DeepLinkAction {
        // spotify:track:6rqhFgbbKwnb9MLmUQDhG6
        val ssp = uri.schemeSpecificPart ?: return DeepLinkAction.Unknown(uri)
        val parts = ssp.split(":")
        if (parts.size < 2) return DeepLinkAction.Unknown(uri)

        return when (parts[0]) {
            "track" -> DeepLinkAction.SpotifyTrack(parts[1])
            "album" -> DeepLinkAction.SpotifyAlbum(parts[1])
            "playlist" -> DeepLinkAction.SpotifyPlaylist(parts[1])
            "artist" -> DeepLinkAction.SpotifyArtist(parts[1])
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    // ── https:// URLs ─────────────────────────────────────────────────

    private fun parseHttpUrl(uri: Uri): DeepLinkAction {
        return when (uri.host) {
            "open.spotify.com" -> parseSpotifyUrl(uri)
            "music.apple.com" -> parseAppleMusicUrl(uri)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    /**
     * Parse Spotify web URLs.
     * Patterns: /track/{id}, /album/{id}, /playlist/{id}, /artist/{id}
     * May have query params like ?si=... which we ignore.
     */
    private fun parseSpotifyUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.size < 2) return DeepLinkAction.Unknown(uri)

        // Handle /intl-xx/ prefix (e.g., /intl-en/track/xxx)
        val (type, id) = if (segments[0].startsWith("intl-") && segments.size >= 3) {
            segments[1] to segments[2]
        } else {
            segments[0] to segments[1]
        }

        return when (type) {
            "track" -> DeepLinkAction.SpotifyTrack(id)
            "album" -> DeepLinkAction.SpotifyAlbum(id)
            "playlist" -> DeepLinkAction.SpotifyPlaylist(id)
            "artist" -> DeepLinkAction.SpotifyArtist(id)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    /**
     * Parse Apple Music URLs.
     * Patterns:
     *   /us/album/{album-name}/{id}        — album (id = numeric)
     *   /us/album/{album-name}/{id}?i={songId}  — specific song on album
     *   /us/song/{song-name}/{id}          — direct song link
     *   /us/playlist/{name}/{id}           — playlist (id = pl.xxxxx)
     *   /us/artist/{name}/{id}             — artist
     *
     * The first segment is always a country code (us, gb, etc.) which we skip.
     */
    private fun parseAppleMusicUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        // Need at least: /{country}/{type}/{name}/{id}
        if (segments.size < 3) return DeepLinkAction.Unknown(uri)

        // Skip country code (first segment)
        val type = segments[1]
        val id = segments.lastOrNull() ?: return DeepLinkAction.Unknown(uri)

        // Check for ?i= param (specific song within album)
        val songId = uri.getQueryParameter("i")

        return when (type) {
            "album" -> {
                if (songId != null) {
                    DeepLinkAction.AppleMusicSong(songId)
                } else {
                    DeepLinkAction.AppleMusicAlbum(id)
                }
            }
            "song" -> DeepLinkAction.AppleMusicSong(id)
            "playlist" -> DeepLinkAction.AppleMusicPlaylist(id)
            "artist" -> {
                // For artists, navigate to our artist page by name (segment before ID)
                val artistName = if (segments.size >= 4) segments[2] else id
                DeepLinkAction.NavigateArtist(
                    artistName.replace("-", " "),
                )
            }
            else -> DeepLinkAction.Unknown(uri)
        }
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/parachord/android/deeplink/DeepLinkHandler.kt
git commit -m "feat: add DeepLinkHandler for parachord://, Spotify, and Apple Music URL parsing"
```

---

## Task 4: Create ExternalLinkResolver

**Files:**
- Create: `app/src/main/java/com/parachord/android/deeplink/ExternalLinkResolver.kt`

This class handles the metadata fetch step for external URLs (Spotify, Apple Music). It takes a `DeepLinkAction.SpotifyTrack/Album/etc.` and fetches metadata, converts to navigation-ready data.

**Step 1: Create ExternalLinkResolver**

```kotlin
package com.parachord.android.deeplink

import android.util.Log
import com.parachord.android.data.api.AppleMusicApi
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExternalLinkResolver"

/**
 * Resolves external Spotify / Apple Music URLs into metadata.
 *
 * Desktop equivalent:
 * - Spotify: spotify.axe → lookupUrl() calls /v1/tracks/{id}, /v1/albums/{id}
 * - Apple Music: apple-music.axe → lookupUrl() calls iTunes /lookup?id={id}
 *
 * After fetching metadata, results can be:
 * 1. Played immediately (single track → resolve + play)
 * 2. Navigated to (album/artist/playlist → open the page with pre-populated metadata)
 */
@Singleton
class ExternalLinkResolver @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val appleMusicApi: AppleMusicApi,
) {
    data class TrackResult(
        val track: TrackEntity,
    )

    data class AlbumResult(
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        val tracks: List<TrackEntity> = emptyList(),
    )

    data class PlaylistResult(
        val name: String,
        val tracks: List<TrackEntity> = emptyList(),
    )

    data class ArtistResult(
        val name: String,
    )

    // ── Spotify ───────────────────────────────────────────────────────

    suspend fun resolveSpotifyTrack(trackId: String): TrackResult? = try {
        val response = spotifyApi.getTrack(trackId)
        val artworkUrl = response.album?.images?.firstOrNull()?.url
        TrackResult(
            track = TrackEntity(
                id = "spotify:$trackId",
                title = response.name,
                artist = response.artists.firstOrNull()?.name ?: "Unknown",
                album = response.album?.name,
                duration = response.durationMs,
                artworkUrl = artworkUrl,
                resolver = "spotify",
                spotifyId = trackId,
                spotifyUri = response.uri,
            ),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify track $trackId", e)
        null
    }

    suspend fun resolveSpotifyAlbum(albumId: String): AlbumResult? = try {
        val albumTracks = spotifyApi.getAlbumTracks(albumId)
        val album = spotifyApi.getTrack(albumTracks.items.firstOrNull()?.id ?: albumId)
        val artworkUrl = album.album?.images?.firstOrNull()?.url
        val albumName = album.album?.name ?: "Unknown Album"
        val artistName = album.artists.firstOrNull()?.name ?: "Unknown"

        AlbumResult(
            title = albumName,
            artist = artistName,
            artworkUrl = artworkUrl,
            tracks = albumTracks.items.map { item ->
                TrackEntity(
                    id = "spotify:${item.id}",
                    title = item.name,
                    artist = item.artists.firstOrNull()?.name ?: artistName,
                    album = albumName,
                    duration = item.durationMs,
                    artworkUrl = artworkUrl,
                    resolver = "spotify",
                    spotifyId = item.id,
                    spotifyUri = item.uri,
                )
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify album $albumId", e)
        null
    }

    suspend fun resolveSpotifyPlaylist(playlistId: String): PlaylistResult? = try {
        val playlist = spotifyApi.getPlaylist(playlistId)
        val tracks = spotifyApi.getPlaylistTracks(playlistId)
        PlaylistResult(
            name = playlist.name,
            tracks = tracks.items.mapNotNull { item ->
                val track = item.track ?: return@mapNotNull null
                val artworkUrl = track.album?.images?.firstOrNull()?.url
                TrackEntity(
                    id = "spotify:${track.id}",
                    title = track.name,
                    artist = track.artists.firstOrNull()?.name ?: "Unknown",
                    album = track.album?.name,
                    duration = track.durationMs,
                    artworkUrl = artworkUrl,
                    resolver = "spotify",
                    spotifyId = track.id,
                    spotifyUri = track.uri,
                )
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify playlist $playlistId", e)
        null
    }

    suspend fun resolveSpotifyArtist(artistId: String): ArtistResult? = try {
        val artist = spotifyApi.getArtist(artistId)
        ArtistResult(name = artist.name)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify artist $artistId", e)
        null
    }

    // ── Apple Music ───────────────────────────────────────────────────

    suspend fun resolveAppleMusicSong(songId: String): TrackResult? = try {
        val response = appleMusicApi.lookup(songId)
        val item = response.results.firstOrNull { it.wrapperType == "track" || it.kind == "song" }
            ?: return null
        val artworkUrl = item.artworkUrl100?.replace("100x100", "600x600")
        TrackResult(
            track = TrackEntity(
                id = "applemusic:$songId",
                title = item.trackName ?: "Unknown",
                artist = item.artistName ?: "Unknown",
                album = item.collectionName,
                duration = item.trackTimeMillis,
                artworkUrl = artworkUrl,
                resolver = "applemusic",
                appleMusicId = songId,
            ),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Apple Music song $songId", e)
        null
    }

    suspend fun resolveAppleMusicAlbum(albumId: String): AlbumResult? = try {
        val response = appleMusicApi.lookup(albumId, entity = "song")
        val collection = response.results.firstOrNull { it.wrapperType == "collection" }
        val songs = response.results.filter { it.wrapperType == "track" || it.kind == "song" }
        val albumName = collection?.collectionName ?: songs.firstOrNull()?.collectionName ?: "Unknown"
        val artistName = collection?.artistName ?: songs.firstOrNull()?.artistName ?: "Unknown"
        val artworkUrl = (collection?.artworkUrl100 ?: songs.firstOrNull()?.artworkUrl100)
            ?.replace("100x100", "600x600")

        AlbumResult(
            title = albumName,
            artist = artistName,
            artworkUrl = artworkUrl,
            tracks = songs.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
                .map { item ->
                    TrackEntity(
                        id = "applemusic:${item.trackId}",
                        title = item.trackName ?: "Unknown",
                        artist = item.artistName ?: artistName,
                        album = albumName,
                        duration = item.trackTimeMillis,
                        artworkUrl = item.artworkUrl100?.replace("100x100", "600x600") ?: artworkUrl,
                        resolver = "applemusic",
                        appleMusicId = item.trackId?.toString(),
                    )
                },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Apple Music album $albumId", e)
        null
    }

    suspend fun resolveAppleMusicPlaylist(playlistId: String): PlaylistResult? {
        // Apple Music playlists via iTunes API are limited.
        // The iTunes API doesn't support playlist lookups by curator playlist ID (pl.xxx).
        // For now, return null — this can be enhanced with the Apple Music API (MusicKit) later.
        Log.w(TAG, "Apple Music playlist lookup not supported via iTunes API: $playlistId")
        return null
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/parachord/android/deeplink/ExternalLinkResolver.kt
git commit -m "feat: add ExternalLinkResolver for Spotify and Apple Music URL metadata fetching"
```

---

## Task 5: Create DeepLinkViewModel

**Files:**
- Create: `app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt`

This ViewModel handles the async work of resolving external links and dispatching navigation/playback actions. It receives DeepLinkActions from MainActivity, processes them, and emits navigation events.

**Step 1: Create DeepLinkViewModel**

```kotlin
package com.parachord.android.deeplink

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.QueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DeepLinkViewModel"

/**
 * Navigation events emitted by deep link processing.
 * MainActivity observes these and calls navController.navigate().
 */
sealed class DeepLinkNavEvent {
    data class Artist(val name: String) : DeepLinkNavEvent()
    data class Album(val title: String, val artist: String) : DeepLinkNavEvent()
    data class Playlist(val id: String) : DeepLinkNavEvent()
    data object Home : DeepLinkNavEvent()
    data class Library(val tab: Int) : DeepLinkNavEvent()
    data object History : DeepLinkNavEvent()
    data class Friend(val id: String) : DeepLinkNavEvent()
    data object Recommendations : DeepLinkNavEvent()
    data object Charts : DeepLinkNavEvent()
    data object CriticalDarlings : DeepLinkNavEvent()
    data object Playlists : DeepLinkNavEvent()
    data object Settings : DeepLinkNavEvent()
    data class Search(val query: String?) : DeepLinkNavEvent()
    data class Chat(val prompt: String?) : DeepLinkNavEvent()
    data class Toast(val message: String) : DeepLinkNavEvent()
}

@HiltViewModel
class DeepLinkViewModel @Inject constructor(
    private val deepLinkHandler: DeepLinkHandler,
    private val externalLinkResolver: ExternalLinkResolver,
    private val playbackController: PlaybackController,
    private val queueManager: QueueManager,
) : ViewModel() {

    private val _navEvents = MutableSharedFlow<DeepLinkNavEvent>()
    val navEvents: SharedFlow<DeepLinkNavEvent> = _navEvents.asSharedFlow()

    fun handleUri(uri: Uri) {
        val action = deepLinkHandler.parse(uri)
        handleAction(action)
    }

    private fun handleAction(action: DeepLinkAction) {
        viewModelScope.launch {
            when (action) {
                // ── Auth (pass-through, handled by OAuthManager in MainActivity) ──
                is DeepLinkAction.OAuthCallback -> { /* no-op, already handled */ }

                // ── Playback ──
                is DeepLinkAction.Play -> {
                    playbackController.playBySearch(action.artist, action.title)
                }
                is DeepLinkAction.Control -> {
                    when (action.action) {
                        "pause" -> playbackController.pause()
                        "resume", "play" -> playbackController.resume()
                        "skip", "next" -> playbackController.skipNext()
                        "previous" -> playbackController.skipPrevious()
                    }
                }
                is DeepLinkAction.QueueAdd -> {
                    playbackController.queueBySearch(action.artist, action.title)
                    _navEvents.emit(DeepLinkNavEvent.Toast("Added to queue: ${action.title}"))
                }
                is DeepLinkAction.QueueClear -> {
                    queueManager.clearQueue()
                    _navEvents.emit(DeepLinkNavEvent.Toast("Queue cleared"))
                }
                is DeepLinkAction.Shuffle -> {
                    val current = queueManager.shuffleEnabled
                    if (current != action.enabled) {
                        queueManager.toggleShuffle()
                    }
                    _navEvents.emit(
                        DeepLinkNavEvent.Toast(
                            "Shuffle ${if (action.enabled) "on" else "off"}"
                        )
                    )
                }
                is DeepLinkAction.Volume -> {
                    playbackController.setVolume(action.level / 100f)
                }

                // ── Navigation ──
                is DeepLinkAction.NavigateHome ->
                    _navEvents.emit(DeepLinkNavEvent.Home)
                is DeepLinkAction.NavigateArtist ->
                    _navEvents.emit(DeepLinkNavEvent.Artist(action.name))
                is DeepLinkAction.NavigateAlbum ->
                    _navEvents.emit(DeepLinkNavEvent.Album(action.title, action.artist))
                is DeepLinkAction.NavigateLibrary -> {
                    val tab = when (action.tab) {
                        "albums" -> 1
                        "artists" -> 2
                        else -> 0
                    }
                    _navEvents.emit(DeepLinkNavEvent.Library(tab))
                }
                is DeepLinkAction.NavigateHistory ->
                    _navEvents.emit(DeepLinkNavEvent.History)
                is DeepLinkAction.NavigateFriend ->
                    _navEvents.emit(DeepLinkNavEvent.Friend(action.id))
                is DeepLinkAction.NavigateRecommendations ->
                    _navEvents.emit(DeepLinkNavEvent.Recommendations)
                is DeepLinkAction.NavigateCharts ->
                    _navEvents.emit(DeepLinkNavEvent.Charts)
                is DeepLinkAction.NavigateCriticalDarlings ->
                    _navEvents.emit(DeepLinkNavEvent.CriticalDarlings)
                is DeepLinkAction.NavigatePlaylists ->
                    _navEvents.emit(DeepLinkNavEvent.Playlists)
                is DeepLinkAction.NavigatePlaylist ->
                    _navEvents.emit(DeepLinkNavEvent.Playlist(action.id))
                is DeepLinkAction.NavigateSettings ->
                    _navEvents.emit(DeepLinkNavEvent.Settings)
                is DeepLinkAction.NavigateSearch ->
                    _navEvents.emit(DeepLinkNavEvent.Search(action.query))
                is DeepLinkAction.NavigateChat ->
                    _navEvents.emit(DeepLinkNavEvent.Chat(action.prompt))

                // ── Import ──
                is DeepLinkAction.ImportPlaylist -> {
                    _navEvents.emit(DeepLinkNavEvent.Toast("Playlist import not yet supported"))
                }

                // ── External URL lookups (async metadata fetch) ──
                is DeepLinkAction.SpotifyTrack -> resolveSpotifyTrack(action.trackId)
                is DeepLinkAction.SpotifyAlbum -> resolveSpotifyAlbum(action.albumId)
                is DeepLinkAction.SpotifyPlaylist -> resolveSpotifyPlaylist(action.playlistId)
                is DeepLinkAction.SpotifyArtist -> resolveSpotifyArtist(action.artistId)
                is DeepLinkAction.AppleMusicSong -> resolveAppleMusicSong(action.songId)
                is DeepLinkAction.AppleMusicAlbum -> resolveAppleMusicAlbum(action.albumId)
                is DeepLinkAction.AppleMusicPlaylist -> resolveAppleMusicPlaylist(action.playlistId)

                is DeepLinkAction.Unknown -> {
                    Log.w(TAG, "Unknown deep link: ${action.uri}")
                }
            }
        }
    }

    // ── External URL resolution (fetch metadata, then navigate/play) ──

    private suspend fun resolveSpotifyTrack(trackId: String) {
        val result = externalLinkResolver.resolveSpotifyTrack(trackId)
        if (result != null) {
            playbackController.playTrack(result.track)
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify track"))
        }
    }

    private suspend fun resolveSpotifyAlbum(albumId: String) {
        val result = externalLinkResolver.resolveSpotifyAlbum(albumId)
        if (result != null) {
            _navEvents.emit(DeepLinkNavEvent.Album(result.title, result.artist))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify album"))
        }
    }

    private suspend fun resolveSpotifyPlaylist(playlistId: String) {
        val result = externalLinkResolver.resolveSpotifyPlaylist(playlistId)
        if (result != null) {
            // TODO: Import as a Parachord playlist and navigate to it
            _navEvents.emit(DeepLinkNavEvent.Toast("Opened playlist: ${result.name}"))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify playlist"))
        }
    }

    private suspend fun resolveSpotifyArtist(artistId: String) {
        val result = externalLinkResolver.resolveSpotifyArtist(artistId)
        if (result != null) {
            _navEvents.emit(DeepLinkNavEvent.Artist(result.name))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify artist"))
        }
    }

    private suspend fun resolveAppleMusicSong(songId: String) {
        val result = externalLinkResolver.resolveAppleMusicSong(songId)
        if (result != null) {
            playbackController.playTrack(result.track)
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Apple Music song"))
        }
    }

    private suspend fun resolveAppleMusicAlbum(albumId: String) {
        val result = externalLinkResolver.resolveAppleMusicAlbum(albumId)
        if (result != null) {
            _navEvents.emit(DeepLinkNavEvent.Album(result.title, result.artist))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Apple Music album"))
        }
    }

    private suspend fun resolveAppleMusicPlaylist(playlistId: String) {
        _navEvents.emit(
            DeepLinkNavEvent.Toast("Apple Music playlist import coming soon")
        )
    }
}
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/parachord/android/deeplink/DeepLinkViewModel.kt
git commit -m "feat: add DeepLinkViewModel for dispatching deep link actions"
```

---

## Task 6: Wire DeepLinkHandler into MainActivity

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt`

**Step 1: Update MainActivity to use DeepLinkHandler for all URIs**

Replace the existing `handleOAuthIntent()` with a more general approach that routes all URIs through `DeepLinkHandler`:

```kotlin
// In MainActivity:

@Inject
lateinit var deepLinkHandler: DeepLinkHandler

private fun handleIntent(intent: Intent?) {
    val uri = intent?.data ?: return
    // Auth callbacks still go to OAuthManager directly (fast path)
    if (uri.scheme == "parachord" && uri.host == "auth") {
        CoroutineScope(Dispatchers.IO).launch {
            oAuthManager.handleRedirect(uri)
        }
        return
    }
    // All other URIs go to DeepLinkHandler via the ViewModel
    pendingDeepLinkUri = uri
}

private var pendingDeepLinkUri: Uri? = null
```

**Step 2: In ParachordAppContent, observe DeepLinkViewModel nav events**

Add a `DeepLinkViewModel` to `ParachordAppContent` and observe its `navEvents`:

```kotlin
// In ParachordAppContent:
val deepLinkViewModel: DeepLinkViewModel = hiltViewModel()

// Observe navigation events from deep links
LaunchedEffect(Unit) {
    deepLinkViewModel.navEvents.collect { event ->
        when (event) {
            is DeepLinkNavEvent.Artist -> navController.navigate(Routes.artist(event.name)) { launchSingleTop = true }
            is DeepLinkNavEvent.Album -> navController.navigate(Routes.album(event.title, event.artist)) { launchSingleTop = true }
            is DeepLinkNavEvent.Playlist -> navController.navigate(Routes.playlistDetail(event.id)) { launchSingleTop = true }
            is DeepLinkNavEvent.Home -> navController.navigate(Routes.HOME) { launchSingleTop = true }
            is DeepLinkNavEvent.Library -> navController.navigate(Routes.collection(event.tab)) { launchSingleTop = true }
            is DeepLinkNavEvent.History -> navController.navigate(Routes.HISTORY) { launchSingleTop = true }
            is DeepLinkNavEvent.Friend -> navController.navigate(Routes.friendDetail(event.id)) { launchSingleTop = true }
            is DeepLinkNavEvent.Recommendations -> navController.navigate(Routes.RECOMMENDATIONS) { launchSingleTop = true }
            is DeepLinkNavEvent.Charts -> navController.navigate(Routes.POP_OF_THE_TOPS) { launchSingleTop = true }
            is DeepLinkNavEvent.CriticalDarlings -> navController.navigate(Routes.CRITICAL_DARLINGS) { launchSingleTop = true }
            is DeepLinkNavEvent.Playlists -> navController.navigate(Routes.PLAYLISTS) { launchSingleTop = true }
            is DeepLinkNavEvent.Settings -> navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
            is DeepLinkNavEvent.Search -> navController.navigate(Routes.SEARCH) { launchSingleTop = true }
            is DeepLinkNavEvent.Chat -> navController.navigate(Routes.CHAT) { launchSingleTop = true }
            is DeepLinkNavEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
        }
    }
}
```

**Step 3: Pass pending deep link URI from Activity to Composable**

The Activity needs to communicate the pending URI to the Composable. Use a mutable state or pass via the ViewModel. Simplest approach: the Activity calls `deepLinkViewModel.handleUri(uri)` directly from `handleIntent()`.

Since `DeepLinkViewModel` is scoped to the Activity (via `hiltViewModel()`), we can access it from the Activity via `ViewModelProvider`:

```kotlin
// In onCreate, after setContent:
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
        ParachordApp()
    }
    handleIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIntent(intent)
}

private fun handleIntent(intent: Intent?) {
    val uri = intent?.data ?: return
    if (uri.scheme == "parachord" && uri.host == "auth") {
        CoroutineScope(Dispatchers.IO).launch {
            oAuthManager.handleRedirect(uri)
        }
        return
    }
    // Route through DeepLinkViewModel
    val vm = androidx.lifecycle.ViewModelProvider(this)[DeepLinkViewModel::class.java]
    vm.handleUri(uri)
}
```

**Step 4: Commit**

```bash
git add app/src/main/java/com/parachord/android/ui/MainActivity.kt
git commit -m "feat: wire DeepLinkHandler into MainActivity for all URL types"
```

---

## Task 7: Add Missing PlaybackController Methods

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`

The DeepLinkViewModel calls `playBySearch()`, `queueBySearch()`, `setVolume()`, and `playTrack()`. Some of these may not exist yet. Check and add:

**Step 1: Add playBySearch() if missing**

```kotlin
/**
 * Search for a track by artist + title, resolve it, and play it.
 * Used by parachord://play deep links.
 */
suspend fun playBySearch(artist: String, title: String) {
    val results = resolverManager.search("$artist $title", limit = 1)
    val track = results.firstOrNull() ?: run {
        Log.w(TAG, "playBySearch: no results for '$artist - $title'")
        return
    }
    playTrack(track)
}
```

**Step 2: Add queueBySearch() if missing**

```kotlin
/**
 * Search for a track by artist + title, resolve it, and add to queue.
 * Used by parachord://queue/add deep links.
 */
suspend fun queueBySearch(artist: String, title: String) {
    val results = resolverManager.search("$artist $title", limit = 1)
    val track = results.firstOrNull() ?: run {
        Log.w(TAG, "queueBySearch: no results for '$artist - $title'")
        return
    }
    queueManager.addToQueue(listOf(track))
}
```

**Step 3: Add setVolume() if missing**

```kotlin
/**
 * Set playback volume (0.0 to 1.0).
 */
fun setVolume(volume: Float) {
    player?.volume = volume.coerceIn(0f, 1f)
}
```

**Step 4: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/PlaybackController.kt
git commit -m "feat: add playBySearch, queueBySearch, setVolume to PlaybackController"
```

---

## Task 8: Build Verification

**Step 1: Run assembleDebug**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 2: Fix any compilation errors**

The SpotifyApi and PlaybackController interfaces may need adjustments depending on exact existing method signatures. Fix any type mismatches or missing methods.

**Step 3: Commit fixes if any**

```bash
git add -A
git commit -m "fix: resolve compilation errors in deep link integration"
```

---

## Verification Checklist

1. `./gradlew assembleDebug` passes
2. `parachord://play?artist=Radiohead&title=Creep` → searches and plays the track
3. `parachord://control/pause` → pauses playback
4. `parachord://control/skip` → skips to next track
5. `parachord://queue/add?artist=Radiohead&title=Karma+Police` → adds to queue
6. `parachord://home` → navigates to home screen
7. `parachord://artist/Radiohead` → navigates to artist page
8. `parachord://album/Radiohead/OK+Computer` → navigates to album page
9. `parachord://search?q=shoegaze` → navigates to search with query
10. `parachord://chat?prompt=recommend+something` → opens chat
11. `https://open.spotify.com/track/6rqhFgbbKwnb9MLmUQDhG6` → fetches track metadata, plays it
12. `https://open.spotify.com/album/6dVIqQ8qmQ5GBnJ9shOYGE` → fetches album, navigates to album page
13. `https://open.spotify.com/artist/4Z8W4fKeB5YxbusRsdQVPb` → fetches artist name, navigates to artist page
14. `spotify:track:6rqhFgbbKwnb9MLmUQDhG6` → same as Spotify web URL for tracks
15. `https://music.apple.com/us/album/ok-computer/1097861387` → fetches from iTunes API, navigates to album
16. `https://music.apple.com/us/album/ok-computer/1097861387?i=1097862703` → fetches song, plays it

## Notes

- The existing `parachord://auth` handler in `handleOAuthIntent` is preserved as a fast path — auth callbacks don't go through `DeepLinkHandler` to avoid latency.
- Apple Music playlist lookups via iTunes API are limited (the public API doesn't support curator playlist IDs like `pl.xxx`). This is noted as a known limitation. The MusicKit JS bridge could potentially be used for this in the future.
- `playBySearch()` and `queueBySearch()` depend on `ResolverManager.search()` existing. If it doesn't exist, the search needs to be routed through the existing Spotify search API or a general search mechanism.
- External Spotify URLs require a valid Spotify API token. If the user isn't authenticated, the lookup will fail gracefully with a toast message.

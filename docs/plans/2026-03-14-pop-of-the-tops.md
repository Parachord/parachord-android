# Pop of the Tops Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Port the desktop's "Pop of the Tops" charts discovery feature to Android — two tabs (Albums from Apple Music RSS, Songs from Apple Music or Last.fm) with territory/source dropdown selectors.

**Architecture:** ViewModel fetches chart data from Apple Music JSON RSS feed (albums + songs) and Last.fm global/geo chart APIs. A `ChartsRepository` handles fetching, parsing, and 24-hour caching. The existing `SwipeableTabLayout` provides Albums/Songs tabs. Dropdown selectors for country (15 territories) and source (Apple Music / Last.fm on Songs tab) use Material 3 `ExposedDropdownMenuBox`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp (Apple Music RSS), Retrofit (Last.fm), kotlinx.serialization, existing `ResolverManager` + `PlaybackController` for playback.

---

### Task 1: Data Models & Country Constants

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/repository/ChartsRepository.kt`

**Step 1: Create data models and country list**

```kotlin
package com.parachord.android.data.repository

data class ChartAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val rank: Int = 0,
    val genres: List<String> = emptyList(),
    val url: String? = null,
)

data class ChartSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val rank: Int = 0,
    val listeners: Long? = null,
    val playcount: Long? = null,
    val url: String? = null,
    val source: String = "", // "apple" or "lastfm"
    val mbid: String? = null,
    val spotifyId: String? = null,
)

data class ChartCountry(
    val code: String,       // ISO 3166-1 alpha-2 (e.g. "us")
    val name: String,       // Display name (e.g. "United States")
    val lastfmName: String, // Last.fm API name (e.g. "United States")
)

val CHARTS_COUNTRIES = listOf(
    ChartCountry("us", "United States", "United States"),
    ChartCountry("gb", "United Kingdom", "United Kingdom"),
    ChartCountry("ca", "Canada", "Canada"),
    ChartCountry("au", "Australia", "Australia"),
    ChartCountry("de", "Germany", "Germany"),
    ChartCountry("fr", "France", "France"),
    ChartCountry("jp", "Japan", "Japan"),
    ChartCountry("kr", "South Korea", "South Korea"),
    ChartCountry("br", "Brazil", "Brazil"),
    ChartCountry("mx", "Mexico", "Mexico"),
    ChartCountry("es", "Spain", "Spain"),
    ChartCountry("it", "Italy", "Italy"),
    ChartCountry("nl", "Netherlands", "Netherlands"),
    ChartCountry("se", "Sweden", "Sweden"),
    ChartCountry("pl", "Poland", "Poland"),
)
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```bash
git add app/src/main/java/com/parachord/android/data/repository/ChartsRepository.kt
git commit -m "feat(charts): add data models and country constants for Pop of the Tops"
```

---

### Task 2: Last.fm Chart API Endpoints

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/api/LastFmApi.kt`

Add two new endpoints to the `LastFmApi` interface and their response models.

**Step 1: Add global and geo chart endpoints**

Add to the `LastFmApi` interface (after `getUserFriends`):

```kotlin
// --- Global & geo chart endpoints ---

@GET(".")
suspend fun getChartTopTracks(
    @Query("method") method: String = "chart.gettoptracks",
    @Query("limit") limit: Int = 50,
    @Query("api_key") apiKey: String,
    @Query("format") format: String = "json",
): LfmChartTopTracksResponse

@GET(".")
suspend fun getGeoTopTracks(
    @Query("method") method: String = "geo.gettoptracks",
    @Query("country") country: String,
    @Query("limit") limit: Int = 50,
    @Query("api_key") apiKey: String,
    @Query("format") format: String = "json",
): LfmGeoTopTracksResponse
```

Add response models at the bottom of the file:

```kotlin
// --- Chart / Geo top tracks ---

@Serializable
data class LfmChartTopTracksResponse(
    val tracks: LfmChartTracks? = null,
)

@Serializable
data class LfmChartTracks(
    val track: List<LfmChartTrack> = emptyList(),
)

@Serializable
data class LfmGeoTopTracksResponse(
    val tracks: LfmGeoTracks? = null,
)

@Serializable
data class LfmGeoTracks(
    val track: List<LfmChartTrack> = emptyList(),
)

@Serializable
data class LfmChartTrack(
    val name: String,
    val artist: LfmChartTrackArtist? = null,
    val url: String? = null,
    val listeners: String? = null,
    val playcount: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmChartTrackArtist(
    val name: String,
    val mbid: String? = null,
    val url: String? = null,
)
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```bash
git add app/src/main/java/com/parachord/android/data/api/LastFmApi.kt
git commit -m "feat(charts): add chart.gettoptracks and geo.gettoptracks Last.fm endpoints"
```

---

### Task 3: ChartsRepository — Apple Music RSS + Last.fm Fetching

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/repository/ChartsRepository.kt`

**Step 1: Implement the full repository**

Replace the file with the full repository that handles:
- Apple Music albums RSS: `GET https://rss.marketingtools.apple.com/api/v2/{cc}/music/most-played/50/albums.json`
- Apple Music songs RSS: `GET https://rss.marketingtools.apple.com/api/v2/{cc}/music/most-played/50/songs.json`
- Last.fm global charts: `chart.gettoptracks`
- Last.fm geo charts: `geo.gettoptracks` with country name
- 24-hour in-memory cache per source+country key

```kotlin
package com.parachord.android.data.repository

import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.bestImageUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChartsRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val lastFmApi: LastFmApi,
) {
    companion object {
        private const val TAG = "ChartsRepo"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val apiKey: String get() = BuildConfig.LASTFM_API_KEY

    // Cache: key → (data, timestamp)
    private val albumsCache = mutableMapOf<String, Pair<List<ChartAlbum>, Long>>()
    private val songsCache = mutableMapOf<String, Pair<List<ChartSong>, Long>>()

    // ── Apple Music Albums ─────────────────────────────────────────

    suspend fun getAppleMusicAlbums(countryCode: String = "us"): List<ChartAlbum> =
        withContext(Dispatchers.IO) {
            val cacheKey = "apple-albums-$countryCode"
            albumsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }

            try {
                val url = "https://rss.marketingtools.apple.com/api/v2/$countryCode/music/most-played/50/albums.json"
                val body = fetchJson(url)
                val results = body?.optJSONObject("feed")?.optJSONArray("results") ?: return@withContext emptyList()
                val albums = (0 until results.length()).map { i ->
                    val obj = results.getJSONObject(i)
                    ChartAlbum(
                        id = "apple-album-$countryCode-$i",
                        title = obj.optString("name", ""),
                        artist = obj.optString("artistName", ""),
                        artworkUrl = obj.optString("artworkUrl100", "")
                            .replace("100x100", "300x300")
                            .ifBlank { null },
                        rank = i + 1,
                        genres = obj.optJSONArray("genres")?.let { arr ->
                            (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "") }
                        } ?: emptyList(),
                        url = obj.optString("url", "").ifBlank { null },
                    )
                }
                albumsCache[cacheKey] = albums to System.currentTimeMillis()
                albums
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Apple Music albums ($countryCode)", e)
                albumsCache[cacheKey]?.first ?: emptyList() // stale cache fallback
            }
        }

    // ── Apple Music Songs ──────────────────────────────────────────

    suspend fun getAppleMusicSongs(countryCode: String = "us"): List<ChartSong> =
        withContext(Dispatchers.IO) {
            val cacheKey = "apple-songs-$countryCode"
            songsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }

            try {
                val url = "https://rss.marketingtools.apple.com/api/v2/$countryCode/music/most-played/50/songs.json"
                val body = fetchJson(url)
                val results = body?.optJSONObject("feed")?.optJSONArray("results") ?: return@withContext emptyList()
                val songs = (0 until results.length()).map { i ->
                    val obj = results.getJSONObject(i)
                    ChartSong(
                        id = "apple-song-$countryCode-$i",
                        title = obj.optString("name", ""),
                        artist = obj.optString("artistName", ""),
                        artworkUrl = obj.optString("artworkUrl100", "")
                            .replace("100x100", "600x600")
                            .ifBlank { null },
                        rank = i + 1,
                        source = "apple",
                        url = obj.optString("url", "").ifBlank { null },
                    )
                }
                songsCache[cacheKey] = songs to System.currentTimeMillis()
                songs
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Apple Music songs ($countryCode)", e)
                songsCache[cacheKey]?.first ?: emptyList()
            }
        }

    // ── Last.fm Charts ─────────────────────────────────────────────

    suspend fun getLastfmCharts(countryCode: String? = null): List<ChartSong> =
        withContext(Dispatchers.IO) {
            val cacheKey = "lastfm-${countryCode ?: "global"}"
            songsCache[cacheKey]?.let { (data, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) return@withContext data
            }

            try {
                val tracks = if (countryCode == null) {
                    // Global charts
                    val response = lastFmApi.getChartTopTracks(apiKey = apiKey)
                    response.tracks?.track ?: emptyList()
                } else {
                    // Country charts — Last.fm requires full country name
                    val countryName = CHARTS_COUNTRIES.find { it.code == countryCode }?.lastfmName
                        ?: return@withContext emptyList()
                    val response = lastFmApi.getGeoTopTracks(country = countryName, apiKey = apiKey)
                    response.tracks?.track ?: emptyList()
                }

                val songs = tracks.mapIndexed { i, t ->
                    ChartSong(
                        id = "lastfm-chart-${countryCode ?: "global"}-$i",
                        title = t.name,
                        artist = t.artist?.name ?: "",
                        artworkUrl = t.image.bestImageUrl(),
                        rank = i + 1,
                        listeners = t.listeners?.toLongOrNull(),
                        playcount = t.playcount?.toLongOrNull(),
                        url = t.url,
                        source = "lastfm",
                        mbid = t.mbid?.takeIf { it.isNotBlank() },
                    )
                }
                songsCache[cacheKey] = songs to System.currentTimeMillis()
                songs
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch Last.fm charts ($countryCode)", e)
                songsCache[cacheKey]?.first ?: emptyList()
            }
        }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun fetchJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).get().build()
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()
        if (!response.isSuccessful || body == null) return null
        return JSONObject(body)
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```bash
git add app/src/main/java/com/parachord/android/data/repository/ChartsRepository.kt
git commit -m "feat(charts): implement ChartsRepository with Apple Music RSS and Last.fm chart APIs"
```

---

### Task 4: PopOfTheTopsViewModel

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/discover/PopOfTheTopsViewModel.kt`

**Step 1: Create the ViewModel**

```kotlin
package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.CHARTS_COUNTRIES
import com.parachord.android.data.repository.ChartAlbum
import com.parachord.android.data.repository.ChartSong
import com.parachord.android.data.repository.ChartsRepository
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PopOfTheTopsViewModel @Inject constructor(
    private val chartsRepository: ChartsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
) : ViewModel() {

    companion object {
        private const val TAG = "PopOfTheTopsVM"
    }

    // ── State ───────────────────────────────────────────────────────

    private val _albums = MutableStateFlow<List<ChartAlbum>>(emptyList())
    val albums: StateFlow<List<ChartAlbum>> = _albums

    private val _songs = MutableStateFlow<List<ChartSong>>(emptyList())
    val songs: StateFlow<List<ChartSong>> = _songs

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading

    private val _songsLoading = MutableStateFlow(false)
    val songsLoading: StateFlow<Boolean> = _songsLoading

    // Country code for territory filtering (default: "us")
    private val _selectedCountry = MutableStateFlow("us")
    val selectedCountry: StateFlow<String> = _selectedCountry

    // Songs source: "apple" or "lastfm"
    private val _songsSource = MutableStateFlow("apple")
    val songsSource: StateFlow<String> = _songsSource

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val countries = CHARTS_COUNTRIES

    init {
        loadAlbums()
        loadSongs()
    }

    // ── Actions ─────────────────────────────────────────────────────

    fun setCountry(code: String) {
        _selectedCountry.value = code
        loadAlbums()
        loadSongs()
    }

    fun setSongsSource(source: String) {
        _songsSource.value = source
        loadSongs()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun filterAlbums(albums: List<ChartAlbum>): List<ChartAlbum> {
        val q = _searchQuery.value.lowercase().trim()
        if (q.isBlank()) return albums
        return albums.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }

    fun filterSongs(songs: List<ChartSong>): List<ChartSong> {
        val q = _searchQuery.value.lowercase().trim()
        if (q.isBlank()) return songs
        return songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }

    fun playSong(song: ChartSong) {
        viewModelScope.launch {
            try {
                val query = "${song.artist} - ${song.title}"
                val sources = resolverManager.resolveWithHints(query = query)
                val best = resolverScoring.selectBest(sources) ?: return@launch
                val entity = TrackEntity(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUrl = song.artworkUrl,
                    sourceType = best.sourceType,
                    sourceUrl = best.url,
                    resolver = best.resolver,
                    spotifyUri = best.spotifyUri,
                    soundcloudId = best.soundcloudId,
                )
                playbackController.playTrack(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play '${song.title}'", e)
            }
        }
    }

    // ── Loading ─────────────────────────────────────────────────────

    private fun loadAlbums() {
        viewModelScope.launch {
            _albumsLoading.value = true
            _albums.value = chartsRepository.getAppleMusicAlbums(_selectedCountry.value)
            _albumsLoading.value = false
        }
    }

    private fun loadSongs() {
        viewModelScope.launch {
            _songsLoading.value = true
            _songs.value = when (_songsSource.value) {
                "lastfm" -> chartsRepository.getLastfmCharts(_selectedCountry.value)
                else -> chartsRepository.getAppleMusicSongs(_selectedCountry.value)
            }
            _songsLoading.value = false
        }
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/discover/PopOfTheTopsViewModel.kt
git commit -m "feat(charts): add PopOfTheTopsViewModel with country/source selection and playback"
```

---

### Task 5: PopOfTheTopsScreen — Full UI

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/discover/PopOfTheTopsScreen.kt`

**Step 1: Replace the stub with the full screen**

The screen has:
- Top app bar with "POP OF THE TOPS" title
- `SwipeableTabLayout` with Albums and Songs tabs
- Filter bar below tabs: Country dropdown (both tabs), Source dropdown (Songs tab only), Search toggle
- Albums tab: Grid of album cards with artwork, title, artist, rank badge
- Songs tab: List rows with rank, title, artist, listeners count

Key UI details matching desktop:
- Orange accent color (`#F97316`)
- Album grid: 2 columns on phone
- Song rows: rank number, title/artist, listener count (Last.fm only)
- Country dropdown: `ExposedDropdownMenuBox` with 15 territories
- Source dropdown: "Apple Music" or "Last.fm" (Songs tab only)
- Last.fm Songs has a "Global" option in the country dropdown (no country = global)
- Search: filters by title or artist

Also wire up navigation callbacks for `onNavigateToAlbum` and `onNavigateToArtist`.

**Step 2: Update Navigation.kt**

Add `onNavigateToAlbum` and `onNavigateToArtist` callbacks to the `PopOfTheTopsScreen` composable in Navigation.kt:

```kotlin
composable(Routes.POP_OF_THE_TOPS) {
    com.parachord.android.ui.screens.discover.PopOfTheTopsScreen(
        onBack = { navController.popBackStack() },
        onNavigateToAlbum = { albumTitle, artistName ->
            navController.navigate(Routes.album(albumTitle, artistName))
        },
        onNavigateToArtist = { name ->
            navController.navigate(Routes.artist(name))
        },
    )
}
```

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```bash
git add app/src/main/java/com/parachord/android/ui/screens/discover/PopOfTheTopsScreen.kt \
       app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt
git commit -m "feat(charts): implement Pop of the Tops screen with albums grid, songs list, and filter dropdowns"
```

---

### Task 6: Final Integration & Polish

**Files:**
- Various UI polish

**Step 1: Verify end-to-end flow**

1. Navigate to Pop of the Tops from drawer or home
2. Albums tab loads Apple Music top 50 for US
3. Switch country → reloads albums
4. Songs tab loads Apple Music songs by default
5. Switch source to Last.fm → loads global charts
6. Select a country on Last.fm → loads geo charts
7. Search filters results
8. Tap album → navigates to album detail
9. Tap song → resolves and plays

**Step 2: Commit all remaining changes**

```bash
git add -A
git commit -m "feat(charts): Pop of the Tops — Apple Music RSS albums/songs + Last.fm global/geo charts"
```

---

## Notes

- **Apple Music RSS** is a public JSON feed, no auth required. Rate limits are generous.
- **Last.fm geo.gettoptracks** requires the full country name (not ISO code). The `CHARTS_COUNTRIES` list maps codes to names.
- **Last.fm chart.gettoptracks** returns global charts (no country param).
- **Last.fm response format difference**: `chart.gettoptracks` wraps in `tracks.track`, `geo.gettoptracks` also wraps in `tracks.track` (same structure, confirmed in desktop tests).
- **Caching**: 24-hour in-memory cache per source+country. Stale cache served as fallback on network error.
- **Desktop parity**: Desktop uses the same RSS URLs and Last.fm endpoints. Country list matches exactly.

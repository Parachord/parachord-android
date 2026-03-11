package com.parachord.android.data.metadata

import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify metadata provider.
 * Best album art and preview URLs. Only available when user is authenticated.
 * Lowest priority since it requires OAuth and has rate limits.
 */
@Singleton
class SpotifyProvider @Inject constructor(
    private val api: SpotifyApi,
    private val settingsStore: SettingsStore,
) : MetadataProvider {

    override val name = "spotify"
    override val priority = 20

    override suspend fun isAvailable(): Boolean =
        getAccessToken() != null

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        try {
            val token = getAccessToken() ?: return emptyList()
            val response = api.search(
                auth = "Bearer $token",
                query = query,
                type = "track",
                limit = limit,
            )
            response.tracks?.items?.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artistName,
                    album = t.album?.name,
                    duration = t.durationMs,
                    artworkUrl = t.album?.images?.bestImageUrl(),
                    previewUrl = t.previewUrl,
                    provider = name,
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        try {
            val token = getAccessToken() ?: return emptyList()
            val response = api.search(
                auth = "Bearer $token",
                query = query,
                type = "album",
                limit = limit,
            )
            response.albums?.items?.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artistName,
                    artworkUrl = a.images.bestImageUrl(),
                    year = a.year,
                    trackCount = a.totalTracks,
                    provider = name,
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        try {
            val token = getAccessToken() ?: return emptyList()
            val response = api.search(
                auth = "Bearer $token",
                query = query,
                type = "artist",
                limit = limit,
            )
            response.artists?.items?.map { a ->
                ArtistInfo(
                    name = a.name,
                    imageUrl = a.images.bestImageUrl(),
                    tags = a.genres,
                    provider = name,
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        searchArtists(artistName, limit = 1).firstOrNull()

    private suspend fun getAccessToken(): String? =
        settingsStore.getSpotifyAccessTokenFlow().firstOrNull()
}

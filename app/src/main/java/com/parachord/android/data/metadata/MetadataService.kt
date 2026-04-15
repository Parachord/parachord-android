package com.parachord.android.data.metadata

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.TrackSearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Android wrapper for the shared [com.parachord.shared.metadata.MetadataService].
 * Bridges concrete provider implementations and SettingsStore to the shared module.
 */
class MetadataService constructor(
    private val musicBrainz: MusicBrainzProvider,
    private val wikipedia: WikipediaProvider,
    private val lastFm: LastFmProvider,
    private val discogs: DiscogsProvider,
    private val spotify: SpotifyProvider,
    private val settingsStore: SettingsStore,
    private val appleMusicApi: com.parachord.android.data.api.AppleMusicApi,
) {
    private val providers: List<com.parachord.shared.metadata.MetadataProvider> by lazy {
        listOf(musicBrainz, wikipedia, lastFm, discogs, spotify).sortedBy { it.priority }
    }

    private val shared by lazy {
        com.parachord.shared.metadata.MetadataService(
            providers = providers,
            getDisabledProviders = { settingsStore.getDisabledMetaProviders() },
            enrichAlbumArtwork = { artistName, albums -> enrichDiscographyArtwork(artistName, albums) },
        )
    }

    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSearchResult> =
        shared.searchTracks(query, limit)

    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult> =
        shared.searchAlbums(query, limit)

    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistInfo> =
        shared.searchArtists(query, limit)

    suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        shared.getArtistInfo(artistName)

    suspend fun getArtistTopTracks(artistName: String, limit: Int = 10): List<TrackSearchResult> =
        shared.getArtistTopTracks(artistName, limit)

    suspend fun getArtistAlbums(artistName: String, limit: Int = 50): List<AlbumSearchResult> =
        shared.getArtistAlbums(artistName, limit)

    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? =
        shared.getAlbumTracks(albumTitle, artistName)

    fun getAlbumTracksProgressively(albumTitle: String, artistName: String): Flow<AlbumDetail> =
        shared.getAlbumTracksProgressively(albumTitle, artistName)

    /**
     * For albums whose artworkUrl points to Cover Art Archive (which may 404),
     * attempt to find better artwork from iTunes Search API.
     */
    private suspend fun enrichDiscographyArtwork(
        artistName: String,
        albums: List<AlbumSearchResult>,
    ): List<AlbumSearchResult> = coroutineScope {
        albums.map { album ->
            async {
                val url = album.artworkUrl
                if (url != null && url.contains("coverartarchive.org")) {
                    try {
                        val term = "$artistName ${album.title}"
                        val response = appleMusicApi.search(
                            term = term,
                            entity = "album",
                            limit = 3,
                        )
                        val match = response.results.firstOrNull { item ->
                            item.collectionName != null &&
                                item.artistName?.lowercase()?.contains(artistName.lowercase()) == true
                        }
                        val itunesArt = match?.artworkUrl100?.replace("100x100", "600x600")
                        if (itunesArt != null) {
                            album.copy(artworkUrl = itunesArt)
                        } else {
                            album
                        }
                    } catch (_: Exception) {
                        album
                    }
                } else {
                    album
                }
            }
        }.awaitAll()
    }
}

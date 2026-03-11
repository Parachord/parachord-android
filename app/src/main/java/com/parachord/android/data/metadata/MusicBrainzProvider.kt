package com.parachord.android.data.metadata

import com.parachord.android.data.api.MusicBrainzApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MusicBrainz metadata provider.
 * Free, no auth needed. Primary source for MBIDs and structured metadata.
 * Highest priority (tried first) because it's always available.
 */
@Singleton
class MusicBrainzProvider @Inject constructor(
    private val api: MusicBrainzApi,
) : MetadataProvider {

    override val name = "musicbrainz"
    override val priority = 0

    override suspend fun isAvailable(): Boolean = true

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        try {
            api.searchRecordings(query, limit).recordings.map { rec ->
                TrackSearchResult(
                    title = rec.title,
                    artist = rec.artistName,
                    album = rec.albumTitle,
                    duration = rec.length,
                    mbid = rec.id,
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        try {
            api.searchReleases(query, limit).releases.map { rel ->
                AlbumSearchResult(
                    title = rel.title,
                    artist = rel.artistName,
                    year = rel.year,
                    trackCount = rel.trackCount,
                    mbid = rel.id,
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        try {
            api.searchArtists(query, limit).artists.map { a ->
                ArtistInfo(
                    name = a.name,
                    mbid = a.id,
                    tags = a.tags.sortedByDescending { it.count }.take(5).map { it.name },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        try {
            val results = api.searchArtists(artistName, limit = 1)
            results.artists.firstOrNull()?.let { a ->
                ArtistInfo(
                    name = a.name,
                    mbid = a.id,
                    tags = a.tags.sortedByDescending { it.count }.take(5).map { it.name },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            null
        }

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        try {
            api.searchReleases("artist:\"$artistName\"", limit).releases.map { rel ->
                AlbumSearchResult(
                    title = rel.title,
                    artist = rel.artistName,
                    year = rel.year,
                    trackCount = rel.trackCount,
                    mbid = rel.id,
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
}

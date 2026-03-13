package com.parachord.android.data.metadata

import com.parachord.android.data.api.MbReleaseGroup
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
                    artworkUrl = coverArtUrl(rel.id),
                    year = rel.year,
                    trackCount = rel.trackCount,
                    mbid = rel.id,
                    releaseType = normalizeReleaseType(rel.releaseGroup),
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

    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
        return try {
            // Search for the release to get its MBID
            val query = "release:\"$albumTitle\" AND artist:\"$artistName\""
            val results = api.searchReleases(query, limit = 1)
            val release = results.releases.firstOrNull() ?: return null

            // Look up the release with recordings included
            val detail = api.getRelease(release.id)
            val tracks = detail.media.flatMap { it.tracks }
            if (tracks.isEmpty()) return null

            val artwork = coverArtUrl(detail.id)

            AlbumDetail(
                title = detail.title,
                artist = detail.artistName,
                artworkUrl = artwork,
                year = detail.year,
                tracks = tracks.map { t ->
                    TrackSearchResult(
                        title = t.recording?.title ?: t.title,
                        artist = t.artistName.ifBlank { detail.artistName },
                        album = detail.title,
                        duration = t.length ?: t.recording?.length,
                        artworkUrl = artwork,
                        mbid = t.recording?.id ?: t.id,
                        provider = name,
                    )
                },
                provider = name,
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        try {
            api.searchReleases("artist:\"$artistName\"", limit).releases.map { rel ->
                AlbumSearchResult(
                    title = rel.title,
                    artist = rel.artistName,
                    artworkUrl = coverArtUrl(rel.id),
                    year = rel.year,
                    trackCount = rel.trackCount,
                    mbid = rel.id,
                    releaseType = normalizeReleaseType(rel.releaseGroup),
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /** Cover Art Archive front cover URL. Returns 404 if no art exists (handled by Coil). */
        fun coverArtUrl(mbid: String): String =
            "https://coverartarchive.org/release/$mbid/front-250"

        /**
         * Normalize MusicBrainz release-group types to our canonical types:
         * "album", "single", "ep", "live", "compilation".
         *
         * MusicBrainz uses primary-type (Album, Single, EP) and secondary-types
         * (Live, Compilation, Remix, etc.). If a secondary type like "Live" is
         * present, it takes precedence for filtering purposes.
         */
        fun normalizeReleaseType(rg: MbReleaseGroup?): String? {
            if (rg == null) return null
            // Secondary types take precedence for filtering
            val secondary = rg.secondaryTypes.firstOrNull()?.lowercase()
            if (secondary == "live") return "live"
            if (secondary == "compilation") return "compilation"
            return when (rg.primaryType?.lowercase()) {
                "album" -> "album"
                "single" -> "single"
                "ep" -> "ep"
                else -> rg.primaryType?.lowercase()
            }
        }
    }
}

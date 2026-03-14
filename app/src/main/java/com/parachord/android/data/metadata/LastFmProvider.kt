package com.parachord.android.data.metadata

import com.parachord.android.BuildConfig
import com.parachord.android.data.api.LastFmApi
import com.parachord.android.data.api.bestImageUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last.fm metadata provider.
 * Rich artist bios, images, similar artists, and tags.
 * Requires a Last.fm API key (set in BuildConfig or constants).
 */
@Singleton
class LastFmProvider @Inject constructor(
    private val api: LastFmApi,
) : MetadataProvider {

    override val name = "lastfm"
    override val priority = 10

    private val apiKey: String get() = BuildConfig.LASTFM_API_KEY

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        try {
            val tracks = api.searchTracks(track = query, apiKey = apiKey, limit = limit)
                .results?.trackmatches?.track ?: emptyList()
            tracks.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artist,
                    artworkUrl = t.image.bestImageUrl(),
                    mbid = t.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        try {
            val albums = api.searchAlbums(album = query, apiKey = apiKey, limit = limit)
                .results?.albummatches?.album ?: emptyList()
            albums.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artist,
                    artworkUrl = a.image.bestImageUrl(),
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        try {
            val artists = api.searchArtists(artist = query, apiKey = apiKey, limit = limit)
                .results?.artistmatches?.artist ?: emptyList()
            artists.map { a ->
                ArtistInfo(
                    name = a.name,
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    imageUrl = a.image.bestImageUrl(),
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
        return try {
            val info = api.getAlbumInfo(album = albumTitle, artist = artistName, apiKey = apiKey)
            val detail = info.album ?: return null
            val tracks = detail.tracks?.track?.items ?: emptyList()
            if (tracks.isEmpty()) return null

            AlbumDetail(
                title = detail.name,
                artist = detail.artist,
                artworkUrl = detail.image.bestImageUrl(),
                tracks = tracks.map { t ->
                    TrackSearchResult(
                        title = t.name,
                        artist = t.artist?.name ?: detail.artist,
                        album = detail.name,
                        duration = t.duration?.toLongOrNull()?.let { it * 1000 }, // Last.fm returns seconds
                        artworkUrl = detail.image.bestImageUrl(),
                        provider = name,
                    )
                },
                provider = name,
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getArtistTopTracks(artistName: String, limit: Int): List<TrackSearchResult> =
        try {
            val tracks = api.getArtistTopTracks(artist = artistName, apiKey = apiKey, limit = limit)
                .toptracks?.track ?: emptyList()
            tracks.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artist?.name ?: artistName,
                    artworkUrl = t.image.bestImageUrl(),
                    duration = t.duration?.toLongOrNull()?.let { if (it > 0) it * 1000 else null },
                    mbid = t.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        try {
            val albums = api.getArtistTopAlbums(artist = artistName, apiKey = apiKey, limit = limit)
                .topalbums?.album ?: emptyList()
            albums.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artist?.name ?: artistName,
                    artworkUrl = a.image.bestImageUrl(),
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        try {
            val detail = api.getArtistInfo(artist = artistName, apiKey = apiKey).artist
            detail?.let { d ->
                val bioText = d.bio?.summary?.stripHtmlTags()?.stripLastFmSuffix()

                // Use the dedicated similar artists endpoint for more results (20)
                // with images, instead of the limited set from artist.getinfo (~5, no images)
                val similarArtists = try {
                    api.getSimilarArtists(artist = artistName, apiKey = apiKey, limit = 20)
                        .similarartists?.artist?.map { a ->
                            SimilarArtist(
                                name = a.name,
                                imageUrl = a.image.bestImageUrl(),
                            )
                        } ?: emptyList()
                } catch (_: Exception) {
                    // Fall back to the limited set from artist.getinfo
                    d.similar?.artist?.map { a ->
                        SimilarArtist(name = a.name, imageUrl = a.image.bestImageUrl())
                    } ?: emptyList()
                }

                ArtistInfo(
                    name = d.name,
                    mbid = d.mbid?.takeIf { it.isNotBlank() },
                    imageUrl = d.image.bestImageUrl(),
                    bio = bioText,
                    bioSource = if (bioText != null) "lastfm" else null,
                    bioUrl = d.url?.takeIf { bioText != null },
                    tags = d.tags?.tag?.map { it.name } ?: emptyList(),
                    similarArtists = similarArtists,
                    provider = name,
                )
            }
        } catch (_: Exception) {
            null
        }
}

private fun String.stripHtmlTags(): String =
    replace(Regex("<[^>]*>"), "").trim()

private fun String.stripLastFmSuffix(): String =
    replace(Regex("\\s*Read more on Last\\.?\\s*fm\\.?\\s*\\.?\\s*$", RegexOption.IGNORE_CASE), "").trim()

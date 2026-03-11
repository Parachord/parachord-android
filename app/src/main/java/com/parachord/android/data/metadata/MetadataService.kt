package com.parachord.android.data.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cascading metadata service that aggregates results from multiple providers.
 *
 * Mirrors the desktop app's cascading provider pattern:
 * 1. MusicBrainz (free, always available, MBIDs)
 * 2. Last.fm (images, bios, similar artists)
 * 3. Spotify (album art, preview URLs — only when authenticated)
 *
 * For search: results from all available providers are merged, deduplicated,
 * and sorted by provider priority.
 *
 * For artist lookup: providers cascade — each fills in missing fields from
 * the previous provider's result.
 */
@Singleton
class MetadataService @Inject constructor(
    private val musicBrainz: MusicBrainzProvider,
    private val lastFm: LastFmProvider,
    private val spotify: SpotifyProvider,
) {
    private val providers: List<MetadataProvider> by lazy {
        listOf(musicBrainz, lastFm, spotify).sortedBy { it.priority }
    }

    /** Search tracks across all available providers in parallel, merge and deduplicate. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.searchTracks(query, limit) } }
            .awaitAll()
            .flatten()

        deduplicateTracks(results).take(limit)
    }

    /** Search albums across all available providers in parallel, merge and deduplicate. */
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.searchAlbums(query, limit) } }
            .awaitAll()
            .flatten()

        deduplicateAlbums(results).take(limit)
    }

    /** Search artists across all available providers in parallel, merge and deduplicate. */
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistInfo> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.searchArtists(query, limit) } }
            .awaitAll()
            .flatten()

        deduplicateArtists(results).take(limit)
    }

    /**
     * Get detailed artist info using cascading fallback.
     * Tries each provider in priority order, merging fields so that
     * later providers fill in gaps left by earlier ones.
     */
    suspend fun getArtistInfo(artistName: String): ArtistInfo? = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.getArtistInfo(artistName) } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Cascade: start with the highest-priority result, fill gaps from others
        results.reduce { acc, info -> acc.mergeWith(info) }
    }

    /** Get an artist's discography from all available providers. */
    suspend fun getArtistAlbums(artistName: String, limit: Int = 50): List<AlbumSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.getArtistAlbums(artistName, limit) } }
            .awaitAll()
            .flatten()

        deduplicateAlbums(results).take(limit)
    }

    /** Get album tracklist, trying Spotify first (our primary resolver). */
    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = coroutineScope {
        val results = availableProviders()
            .map { provider -> async { provider.getAlbumTracks(albumTitle, artistName) } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Prefer the result with the most tracks (usually Spotify)
        results.maxByOrNull { it.tracks.size }
    }

    private suspend fun availableProviders(): List<MetadataProvider> =
        providers.filter { it.isAvailable() }

    /** Deduplicate tracks by normalized title+artist, preferring results with more metadata. */
    private fun deduplicateTracks(tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        tracks.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .values
            .map { group -> group.reduce { acc, t -> acc.mergeWith(t) } }

    /** Deduplicate albums by normalized title+artist. */
    private fun deduplicateAlbums(albums: List<AlbumSearchResult>): List<AlbumSearchResult> =
        albums.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .values
            .map { group -> group.reduce { acc, a -> acc.mergeWith(a) } }

    /** Deduplicate artists by normalized name. */
    private fun deduplicateArtists(artists: List<ArtistInfo>): List<ArtistInfo> =
        artists.groupBy { it.name.lowercase() }
            .values
            .map { group -> group.reduce { acc, a -> acc.mergeWith(a) } }
}

/** Merge two ArtistInfo, preferring non-null/non-empty fields from the receiver. */
private fun ArtistInfo.mergeWith(other: ArtistInfo) = ArtistInfo(
    name = name,
    mbid = mbid ?: other.mbid,
    imageUrl = imageUrl ?: other.imageUrl,
    bio = bio ?: other.bio,
    tags = tags.ifEmpty { other.tags },
    similarArtists = similarArtists.ifEmpty { other.similarArtists },
    provider = "$provider+${other.provider}",
)

private fun TrackSearchResult.mergeWith(other: TrackSearchResult) = TrackSearchResult(
    title = title,
    artist = artist,
    album = album ?: other.album,
    duration = duration ?: other.duration,
    artworkUrl = artworkUrl ?: other.artworkUrl,
    previewUrl = previewUrl ?: other.previewUrl,
    spotifyId = spotifyId ?: other.spotifyId,
    mbid = mbid ?: other.mbid,
    provider = "$provider+${other.provider}",
)

private fun AlbumSearchResult.mergeWith(other: AlbumSearchResult) = AlbumSearchResult(
    title = title,
    artist = artist,
    artworkUrl = artworkUrl ?: other.artworkUrl,
    year = year ?: other.year,
    trackCount = trackCount ?: other.trackCount,
    mbid = mbid ?: other.mbid,
    spotifyId = spotifyId ?: other.spotifyId,
    provider = "$provider+${other.provider}",
)

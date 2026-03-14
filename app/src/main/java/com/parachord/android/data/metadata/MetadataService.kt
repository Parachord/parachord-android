package com.parachord.android.data.metadata

import com.parachord.android.data.store.SettingsStore
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
    private val wikipedia: WikipediaProvider,
    private val lastFm: LastFmProvider,
    private val discogs: DiscogsProvider,
    private val spotify: SpotifyProvider,
    private val settingsStore: SettingsStore,
) {
    private val providers: List<MetadataProvider> by lazy {
        listOf(musicBrainz, wikipedia, lastFm, discogs, spotify).sortedBy { it.priority }
    }

    /** Search tracks across all available providers in parallel, merge and deduplicate. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchTracks(query, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateTracks(results).take(limit)
    }

    /** Search albums across all available providers in parallel, merge and deduplicate. */
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchAlbums(query, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateAlbums(results).take(limit)
    }

    /** Search artists across all available providers in parallel, merge and deduplicate. */
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistInfo> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchArtists(query, limit) } catch (_: Exception) { emptyList() }
            } }
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
            .map { provider -> async {
                try { provider.getArtistInfo(artistName) } catch (_: Exception) { null }
            } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Cascade: start with the highest-priority result, fill gaps from others
        var merged = results.reduce { acc, info -> acc.mergeWith(info) }

        // Bio source preference: Wikipedia > Discogs > Last.fm.
        // The general cascade picks the first non-null bio (by provider priority),
        // which means Last.fm (priority 10) always beats Discogs (priority 15).
        // Override with the best available bio based on source quality.
        val bioPreference = listOf("wikipedia", "discogs", "lastfm")
        val bestBio = bioPreference.firstNotNullOfOrNull { preferredSource ->
            results.firstOrNull { it.bioSource == preferredSource && !it.bio.isNullOrBlank() }
        }
        if (bestBio != null && merged.bioSource != bestBio.bioSource) {
            merged = merged.copy(
                bio = bestBio.bio,
                bioSource = bestBio.bioSource,
                bioUrl = bestBio.bioUrl,
            )
        }

        // Enrich similar artists with Spotify images (prefer Spotify over Last.fm which is often wrong)
        val disabled = settingsStore.getDisabledMetaProviders()
        if (merged.similarArtists.isNotEmpty() && spotify.isAvailable() && "spotify" !in disabled) {
            val enriched = merged.similarArtists.map { similar ->
                async {
                    try {
                        val spotifyArtist = spotify.searchArtists(similar.name, limit = 1).firstOrNull()
                        similar.copy(imageUrl = spotifyArtist?.imageUrl ?: similar.imageUrl)
                    } catch (_: Exception) {
                        similar
                    }
                }
            }.awaitAll()
            merged = merged.copy(similarArtists = enriched)
        }

        merged
    }

    /** Get an artist's top tracks from all available providers, merged and deduplicated. */
    suspend fun getArtistTopTracks(artistName: String, limit: Int = 10): List<TrackSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.getArtistTopTracks(artistName, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateTracks(results).take(limit)
    }

    /** Get an artist's discography from all available providers. */
    suspend fun getArtistAlbums(artistName: String, limit: Int = 50): List<AlbumSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try {
                    provider.getArtistAlbums(artistName, limit)
                } catch (_: Exception) {
                    emptyList()
                }
            } }
            .awaitAll()
            .flatten()

        deduplicateAlbums(results).take(limit)
    }

    /**
     * Get album tracklist from all providers and merge.
     *
     * Picks the result with the most tracks as the base, then enriches
     * each track with metadata from other providers (spotifyId, artworkUrl, etc.)
     * using normalized title matching. This ensures MusicBrainz tracklists
     * get Spotify IDs for playback resolution.
     */
    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.getAlbumTracks(albumTitle, artistName) } catch (_: Exception) { null }
            } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Use the result with the most tracks as base
        val base = results.maxByOrNull { it.tracks.size } ?: return@coroutineScope null
        val others = results.filter { it !== base }

        if (others.isEmpty()) return@coroutineScope base

        // Build a lookup of tracks from other providers by normalized title
        val otherTracksByTitle = others
            .flatMap { it.tracks }
            .groupBy { it.title.lowercase().trim() }

        // Enrich base tracks with data from other providers
        val enrichedTracks = base.tracks.map { track ->
            val matches = otherTracksByTitle[track.title.lowercase().trim()] ?: emptyList()
            matches.fold(track) { acc, other -> acc.mergeWith(other) }
        }

        val mergedArtwork = base.artworkUrl ?: others.firstNotNullOfOrNull { it.artworkUrl }

        // Propagate album artwork to tracks that don't have their own
        val tracksWithArt = if (mergedArtwork != null) {
            enrichedTracks.map { t -> t.copy(artworkUrl = t.artworkUrl ?: mergedArtwork) }
        } else enrichedTracks

        base.copy(
            tracks = tracksWithArt,
            artworkUrl = mergedArtwork,
            year = base.year ?: others.firstNotNullOfOrNull { it.year },
            releaseType = base.releaseType ?: others.firstNotNullOfOrNull { it.releaseType },
            provider = results.joinToString("+") { it.provider },
        )
    }

    private suspend fun availableProviders(): List<MetadataProvider> {
        val disabled = settingsStore.getDisabledMetaProviders()
        return providers.filter { it.isAvailable() && it.name !in disabled }
    }

    /** Deduplicate tracks by normalized title+artist, preferring results with more metadata. */
    private fun deduplicateTracks(tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        tracks.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .values
            .map { group -> group.reduce { acc, t -> acc.mergeWith(t) } }

    /** Deduplicate albums by normalized title+artist.
     *  Within each title+artist group, typed entries (album/single/ep) are kept separate,
     *  but entries with null releaseType are merged into the best-matching typed entry
     *  (or kept as a single entry if no typed match exists). */
    private fun deduplicateAlbums(albums: List<AlbumSearchResult>): List<AlbumSearchResult> {
        // Phase 1: group by title+artist
        val byTitleArtist = albums.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
        return byTitleArtist.values.flatMap { group ->
            val typed = group.filter { it.releaseType != null }
            val untyped = group.filter { it.releaseType == null }
            if (typed.isEmpty()) {
                // No typed entries — merge all untyped into one
                listOf(group.reduce { acc, a -> acc.mergeWith(a) })
            } else {
                // Merge untyped entries into the first typed entry, then dedup typed by releaseType
                val merged = if (untyped.isEmpty()) typed
                else listOf(typed.first().let { base -> untyped.fold(base) { acc, a -> acc.mergeWith(a) } }) +
                    typed.drop(1)
                // Dedup remaining typed entries by releaseType
                merged.groupBy { it.releaseType?.lowercase() }
                    .values
                    .map { sub -> sub.reduce { acc, a -> acc.mergeWith(a) } }
            }
        }
    }

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
    bioSource = if (bio != null) bioSource else other.bioSource,
    bioUrl = if (bio != null) bioUrl else other.bioUrl,
    tags = tags.ifEmpty { other.tags },
    similarArtists = if (similarArtists.isNotEmpty()) similarArtists else other.similarArtists,
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
    releaseType = releaseType ?: other.releaseType,
    provider = "$provider+${other.provider}",
)

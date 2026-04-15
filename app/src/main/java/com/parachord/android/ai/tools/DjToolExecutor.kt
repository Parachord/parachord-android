package com.parachord.android.ai.tools

import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import java.util.UUID

/**
 * Executes DJ tool calls from the AI chat.
 * Maps tool names + arguments to PlaybackController / MetadataService / etc. calls.
 * Mirrors the desktop app's dj-tools.js executor logic.
 */
class DjToolExecutor constructor(
    private val playbackController: PlaybackController,
    private val stateHolder: PlaybackStateHolder,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
) {

    /**
     * Execute a DJ tool by name with the given arguments.
     * Returns a result map that will be serialized as the tool response to the AI.
     */
    suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?> {
        return try {
            when (name) {
                "play" -> executPlay(args)
                "control" -> executeControl(args)
                "search" -> executeSearch(args)
                "queue_add" -> executeQueueAdd(args)
                "queue_clear" -> executeQueueClear()
                "create_playlist" -> executeCreatePlaylist(args)
                "shuffle" -> executeShuffle(args)
                "block_recommendation" -> executeBlockRecommendation(args)
                else -> mapOf("error" to "Unknown tool: $name")
            }
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown error executing tool $name"))
        }
    }

    private suspend fun executPlay(args: Map<String, Any?>): Map<String, Any?> {
        val artist = args["artist"] as? String ?: return mapOf("error" to "Missing artist")
        val title = args["title"] as? String ?: return mapOf("error" to "Missing title")

        val query = "$artist $title"
        val results = metadataService.searchTracks(query, 20)
        if (results.isEmpty()) {
            return mapOf("error" to "No results found for \"$artist - $title\"")
        }

        // Prefer exact case-insensitive match, else take first result
        val bestMatch = results.find {
            it.artist.equals(artist, ignoreCase = true) &&
                it.title.equals(title, ignoreCase = true)
        } ?: results.first()

        val track = bestMatch.toTrackEntity()
        playbackController.playTrack(track)

        return mapOf(
            "success" to true,
            "track" to mapOf(
                "title" to track.title,
                "artist" to track.artist,
                "album" to track.album,
            ),
        )
    }

    private fun executeControl(args: Map<String, Any?>): Map<String, Any?> {
        val action = args["action"] as? String ?: return mapOf("error" to "Missing action")
        val state = stateHolder.state.value

        when (action) {
            "pause" -> {
                if (state.isPlaying) playbackController.togglePlayPause()
            }
            "resume" -> {
                if (!state.isPlaying) playbackController.togglePlayPause()
            }
            "skip" -> playbackController.skipNext()
            "previous" -> playbackController.skipPrevious()
            else -> return mapOf("error" to "Unknown action: $action")
        }

        return mapOf("success" to true, "action" to action)
    }

    private suspend fun executeSearch(args: Map<String, Any?>): Map<String, Any?> {
        val query = args["query"] as? String ?: return mapOf("error" to "Missing query")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10

        val results = metadataService.searchTracks(query, limit)
        return mapOf(
            "success" to true,
            "results" to results.map { result ->
                mapOf(
                    "artist" to result.artist,
                    "title" to result.title,
                    "album" to result.album,
                )
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeQueueAdd(args: Map<String, Any?>): Map<String, Any?> {
        val tracksArg = args["tracks"] as? List<Map<String, Any?>>
            ?: return mapOf("error" to "Missing tracks")
        val playFirst = args["playFirst"] as? Boolean ?: true

        if (tracksArg.isEmpty()) {
            return mapOf("error" to "Empty tracks list")
        }

        // Resolve each track via search
        val resolvedTracks = mutableListOf<TrackEntity>()
        for (trackMap in tracksArg) {
            val artist = trackMap["artist"] as? String ?: continue
            val title = trackMap["title"] as? String ?: continue
            val query = "$artist $title"
            val results = metadataService.searchTracks(query, 5)
            val bestMatch = results.find {
                it.artist.equals(artist, ignoreCase = true) &&
                    it.title.equals(title, ignoreCase = true)
            } ?: results.firstOrNull()
            if (bestMatch != null) {
                resolvedTracks.add(bestMatch.toTrackEntity())
            }
        }

        if (resolvedTracks.isEmpty()) {
            return mapOf("error" to "Could not find any of the requested tracks")
        }

        if (playFirst) {
            playbackController.clearQueue()
            playbackController.playTrack(resolvedTracks.first())
            if (resolvedTracks.size > 1) {
                playbackController.addToQueue(resolvedTracks.drop(1))
            }
        } else {
            playbackController.addToQueue(resolvedTracks)
        }

        return mapOf(
            "success" to true,
            "queued" to resolvedTracks.size,
            "tracks" to resolvedTracks.map { mapOf("artist" to it.artist, "title" to it.title) },
        )
    }

    private fun executeQueueClear(): Map<String, Any?> {
        playbackController.clearQueue()
        return mapOf("success" to true)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeCreatePlaylist(args: Map<String, Any?>): Map<String, Any?> {
        val name = args["name"] as? String ?: return mapOf("error" to "Missing playlist name")
        val tracksArg = args["tracks"] as? List<Map<String, Any?>>
            ?: return mapOf("error" to "Missing tracks")

        // Resolve tracks via search
        val resolvedTracks = mutableListOf<TrackEntity>()
        for (trackMap in tracksArg) {
            val artist = trackMap["artist"] as? String ?: continue
            val title = trackMap["title"] as? String ?: continue
            val query = "$artist $title"
            val results = metadataService.searchTracks(query, 5)
            val bestMatch = results.find {
                it.artist.equals(artist, ignoreCase = true) &&
                    it.title.equals(title, ignoreCase = true)
            } ?: results.firstOrNull()
            if (bestMatch != null) {
                resolvedTracks.add(bestMatch.toTrackEntity())
            }
        }

        val playlistId = UUID.randomUUID().toString()
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            trackCount = resolvedTracks.size,
            artworkUrl = resolvedTracks.firstOrNull()?.artworkUrl,
        )

        // Store playlist and its tracks in the junction table — NOT in the
        // Collection tracks table (which was the old broken behavior)
        libraryRepository.createPlaylistWithTracks(playlist, resolvedTracks)

        return mapOf(
            "success" to true,
            "playlistId" to playlistId,
            "name" to name,
            "trackCount" to resolvedTracks.size,
        )
    }

    private fun executeShuffle(args: Map<String, Any?>): Map<String, Any?> {
        val enabled = args["enabled"] as? Boolean
            ?: return mapOf("error" to "Missing enabled parameter")
        val currentState = stateHolder.state.value.shuffleEnabled

        if (currentState != enabled) {
            playbackController.toggleShuffle()
        }

        return mapOf("success" to true, "shuffleEnabled" to enabled)
    }

    private suspend fun executeBlockRecommendation(args: Map<String, Any?>): Map<String, Any?> {
        val type = args["type"] as? String
            ?: return mapOf("error" to "Missing type parameter")

        val entry = when (type) {
            "artist" -> {
                val name = args["name"] as? String
                    ?: return mapOf("error" to "Missing name for artist block")
                "artist:$name"
            }
            "album" -> {
                val artist = args["artist"] as? String
                    ?: return mapOf("error" to "Missing artist for album block")
                val title = args["title"] as? String
                    ?: return mapOf("error" to "Missing title for album block")
                "album:$artist:$title"
            }
            "track" -> {
                val artist = args["artist"] as? String
                    ?: return mapOf("error" to "Missing artist for track block")
                val title = args["title"] as? String
                    ?: return mapOf("error" to "Missing title for track block")
                "track:$artist:$title"
            }
            else -> return mapOf("error" to "Unknown block type: $type")
        }

        settingsStore.addBlockedRecommendation(entry)
        return mapOf("success" to true, "blocked" to entry)
    }

    /**
     * Convert a search result to a TrackEntity for playback.
     * Routes through the resolver pipeline to get proper source URLs and IDs
     * instead of using preview URLs (which are only 30-second samples).
     */
    private suspend fun TrackSearchResult.toTrackEntity(): TrackEntity {
        val query = "$artist - $title"
        val sources = resolverManager.resolveWithHints(
            query = query,
            spotifyId = spotifyId,
            targetTitle = title,
            targetArtist = artist,
        )
        val best = resolverScoring.selectBest(sources)

        val trackId = best?.spotifyId ?: spotifyId ?: UUID.randomUUID().toString()
        return TrackEntity(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            artworkUrl = artworkUrl,
            sourceType = best?.sourceType,
            sourceUrl = best?.url ?: previewUrl,
            resolver = best?.resolver ?: provider.ifBlank { null },
            spotifyUri = best?.spotifyUri ?: spotifyId?.let { "spotify:track:$it" },
            spotifyId = best?.spotifyId ?: spotifyId,
            soundcloudId = best?.soundcloudId,
            appleMusicId = best?.appleMusicId,
        )
    }
}

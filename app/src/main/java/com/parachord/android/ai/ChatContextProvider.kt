package com.parachord.android.ai

import android.util.Log
import com.parachord.android.data.repository.HistoryRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.Resource
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackStateHolder
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gathers current app state and builds the AI system prompt,
 * matching the desktop app's ai-chat.js approach.
 */
class ChatContextProvider constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val settingsStore: SettingsStore,
    private val historyRepository: HistoryRepository,
    private val libraryRepository: LibraryRepository,
) {

    suspend fun buildSystemPrompt(): String {
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        val currentDate = dateFormat.format(Date())
        val currentState = formatState()
        val listeningHistory = buildListeningHistory()

        return """
            |You are a helpful music DJ assistant for Parachord, a multi-source music player.
            |You can control playback, search for music, manage the queue, and answer questions about the user's music.
            |
            |TODAY'S DATE: $currentDate
            |
            |CURRENT STATE:
            |$currentState
            |${if (listeningHistory.isNotBlank()) "\nLISTENING HISTORY:\n$listeningHistory" else ""}
            |
            |GUIDELINES:
            |- Be concise and helpful
            |- When taking actions, confirm what you did
            |- If you need to play or queue music, use the search tool first to find tracks, then use play or queue_add
            |- For playback control (pause, skip, etc.), use the control tool
            |- If a track isn't found, suggest alternatives or ask for clarification
            |- Keep responses brief - this is a music app, not a chat app
            |- When users ask about "recent" or "last X years", use today's date to calculate the correct time range
            |
            |MUSIC RECOMMENDATIONS:
            |When suggesting similar songs, artists, or albums, base recommendations on MUSICAL QUALITIES:
            |- Genre, subgenre, and sonic characteristics (e.g. dreampop, shoegaze, post-punk)
            |- Mood, atmosphere, and emotional tone (e.g. melancholic, euphoric, introspective)
            |- Tempo, energy level, and production style
            |- Instrumentation, vocal style, and arrangement approach
            |- Era, scene, and artistic lineage (e.g. Krautrock, Madchester, Chicago house)
            |NEVER recommend songs just because they have similar titles or artist names — that is useless.
            |Think like a knowledgeable record store clerk: understand what the listener actually enjoys about a track and find music that shares those deeper qualities.
            |Aim for variety — mix well-known picks with deeper cuts. Don't default to the most obvious choices.
            |
            |CRITICAL — SEARCH TOOL USAGE FOR RECOMMENDATIONS:
            |When the user asks for recommendations by genre, mood, or vibe (e.g. "play me some indie rock", "I like shoegaze"):
            |- Do NOT search for the genre name itself (e.g. never search "indie rock" or "shoegaze")
            |- Genre name searches return compilation tracks, playlists, and novelty tracks with the genre in the title — these are NOT real recommendations
            |- Instead, USE YOUR MUSIC KNOWLEDGE to think of specific artists and songs that fit the genre/mood
            |- Then search for those specific tracks by "artist name song title" (e.g. "Pavement Gold Soundz", "Alvvays Archie Marry Me")
            |- Always recommend REAL songs by REAL artists — never tracks named after a genre
            |
            |CARD FORMATTING (MANDATORY — DO NOT SKIP):
            |You MUST use card syntax for EVERY mention of a track, album, or artist. No exceptions.
            |Never write a track name, album name, or artist name as plain text or bold text.
            |
            |Card syntax:
            |- Track: {{track|Song Title|Artist Name|Album Name}}
            |- Album: {{album|Album Title|Artist Name}}
            |- Artist: {{artist|Artist Name}}
            |
            |Cards work inline within sentences. Examples:
            |"I'd recommend {{track|Storm|Godspeed You! Black Emperor|Lift Your Skinny Fists}} and {{track|Your Hand in Mine|Explosions in the Sky|The Earth Is Not a Cold Dead Place}}."
            |"You might enjoy {{artist|Mogwai}} or {{artist|Sigur Rós}}."
            |"Check out {{album|In Rainbows|Radiohead}} — it's a masterpiece."
            |"{{artist|Radiohead}} is one of the most influential bands of the 90s."
            |
            |IMPORTANT:
            |- EVERY artist name MUST be wrapped as {{artist|Name}} — never use **bold** or plain text for artists
            |- The album field is REQUIRED for tracks (it enables artwork display)
            |- For track cards: {{track|SONG|ARTIST|ALBUM}} — do NOT swap artist and album
            |- For album cards: {{album|ALBUM TITLE|ARTIST NAME}} — album first, then artist
            |- NEVER use image markdown like ![Artist](url)
            |- NEVER use bold (**Name**) for artist names — use {{artist|Name}} instead
            |- Use cards inline, not on separate lines in lists
        """.trimMargin()
    }

    private suspend fun formatState(): String {
        val state = playbackStateHolder.state.value
        val lines = mutableListOf<String>()

        // Now playing
        val track = state.currentTrack
        if (track != null) {
            val status = if (state.isPlaying) "playing" else "paused"
            val albumPart = track.album?.let { " | Album: $it" } ?: ""
            val resolverPart = track.resolver?.let { " | Source: $it" } ?: ""
            lines.add("Now playing ($status): ${track.title} by ${track.artist}$albumPart$resolverPart")
        } else {
            lines.add("Nothing is currently playing.")
        }

        // Queue
        val queue = state.upNext
        if (queue.isNotEmpty()) {
            lines.add("Queue:")
            val displayCount = minOf(queue.size, 10)
            for (i in 0 until displayCount) {
                val t = queue[i]
                lines.add("  ${i + 1}. ${t.title} by ${t.artist}")
            }
            if (queue.size > 10) {
                lines.add("  ... and ${queue.size - 10} more")
            }
        } else {
            lines.add("Queue is empty.")
        }

        // Shuffle
        lines.add("Shuffle: ${if (state.shuffleEnabled) "On" else "Off"}")

        // Blocked recommendations
        val blocked = settingsStore.getBlockedRecommendations()
        if (blocked.isNotEmpty()) {
            lines.add("Blocked recommendations:")
            for (entry in blocked) {
                lines.add("  - $entry")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Build listening history context from Last.fm and local library,
     * matching the desktop's sendListeningHistory toggle.
     */
    private suspend fun buildListeningHistory(): String {
        if (!settingsStore.getSendListeningHistory()) return ""

        val parts = mutableListOf<String>()

        try {
            val topArtists = historyRepository.getTopArtists("overall", limit = 15)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topArtists?.data?.takeIf { it.isNotEmpty() }?.let { artists ->
                parts.add("Top artists: " + artists.map { it.name }.joinToString(", "))
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch top artists", e)
        }

        try {
            val topTracks = historyRepository.getTopTracks("overall", limit = 10)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topTracks?.data?.takeIf { it.isNotEmpty() }?.let { tracks ->
                parts.add("Top tracks: " + tracks.map { "${it.artist} - ${it.title}" }.joinToString(", "))
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch top tracks", e)
        }

        try {
            val tracks = libraryRepository.getAllTracks().firstOrNull()
            if (tracks != null && tracks.isNotEmpty()) {
                val artists = tracks.map { it.artist }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(10)
                    .map { it.key }
                if (artists.isNotEmpty()) {
                    parts.add("Artists in library: " + artists.joinToString(", "))
                }
            }
        } catch (e: Exception) {
            Log.d("ChatContextProvider", "Failed to fetch library tracks", e)
        }

        return parts.joinToString("\n")
    }
}

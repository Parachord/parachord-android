package com.parachord.android.ai

import android.content.Context
import android.util.Log
import com.parachord.android.ai.providers.ChatGptProvider
import com.parachord.android.ai.providers.ClaudeProvider
import com.parachord.android.ai.providers.GeminiProvider
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.HistoryRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.Resource
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@kotlinx.serialization.Serializable
data class AiAlbumSuggestion(
    val title: String,
    val artist: String,
    val reason: String,
    val artworkUrl: String? = null,
)

@kotlinx.serialization.Serializable
data class AiArtistSuggestion(
    val name: String,
    val reason: String,
    val imageUrl: String? = null,
)

data class AiRecommendations(
    val albums: List<AiAlbumSuggestion>,
    val artists: List<AiArtistSuggestion>,
)

@kotlinx.serialization.Serializable
internal data class AiSuggestionsDiskCache(
    val albums: List<AiAlbumSuggestion>,
    val artists: List<AiArtistSuggestion>,
    val savedAt: Long = 0L,
)

/**
 * Fetches AI-powered album and artist recommendations matching the desktop app's
 * loadAiRecommendations() logic. Uses the first configured AI provider to generate
 * suggestions based on the user's listening history and collection.
 */
class AiRecommendationService constructor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val historyRepository: HistoryRepository,
    private val libraryRepository: LibraryRepository,
    private val chatGptProvider: ChatGptProvider,
    private val claudeProvider: ClaudeProvider,
    private val geminiProvider: GeminiProvider,
    private val metadataService: MetadataService,
) {
    companion object {
        private const val TAG = "AiRecommendationService"
        private const val CACHE_FILE = "ai_suggestions_cache.json"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Previously suggested items to avoid repeats (matching desktop's previousAiSuggestions). */
    private val previousAlbums = mutableListOf<AiAlbumSuggestion>()
    private val previousArtists = mutableListOf<AiArtistSuggestion>()

    /** Whether the disk cache has been loaded yet. */
    private var diskCacheLoaded = false

    /** Last successfully fetched recommendations for stale-while-revalidate display. */
    var cachedRecommendations: AiRecommendations? = null
        get() {
            if (!diskCacheLoaded) loadDiskCache()
            return field
        }
        private set

    /** Load cached recommendations from disk on first access (stale-while-revalidate). */
    private fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return
            val wrapper = diskJson.decodeFromString<AiSuggestionsDiskCache>(file.readText())
            cachedRecommendations = AiRecommendations(
                albums = wrapper.albums,
                artists = wrapper.artists,
            )
            Log.d(TAG, "Loaded ${wrapper.albums.size} albums, ${wrapper.artists.size} artists from disk cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load AI suggestions disk cache", e)
        }
    }

    /** Persist recommendations to disk for stale-while-revalidate on next cold start. */
    private fun saveDiskCache(recommendations: AiRecommendations) {
        try {
            val wrapper = AiSuggestionsDiskCache(
                albums = recommendations.albums,
                artists = recommendations.artists,
                savedAt = System.currentTimeMillis(),
            )
            val file = File(context.filesDir, CACHE_FILE)
            file.writeText(diskJson.encodeToString(AiSuggestionsDiskCache.serializer(), wrapper))
            Log.d(TAG, "Saved ${recommendations.albums.size} albums, ${recommendations.artists.size} artists to disk cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save AI suggestions disk cache", e)
        }
    }

    /** 10 variety themes matching desktop exactly. */
    private val varietyThemes = listOf(
        "Focus on hidden gems and underrated albums that even dedicated music fans might have missed.",
        "Lean toward albums from different decades — mix classic and contemporary.",
        "Prioritize artists from diverse geographic regions and music scenes around the world.",
        "Recommend albums known for exceptional production quality or sonic experimentation.",
        "Focus on genre-crossing albums that blend unexpected influences.",
        "Highlight debut albums or breakthrough records that defined an artist's sound.",
        "Recommend albums that are critically acclaimed deep cuts, not the artist's most popular work.",
        "Focus on albums from the last 5 years that push their genre forward.",
        "Recommend albums with strong thematic or conceptual cohesion.",
        "Lean toward collaborative albums, supergroups, or unexpected artist pairings.",
    )

    /** Check if any AI provider has an API key configured. */
    suspend fun hasEnabledProvider(): Boolean {
        return listOf("chatgpt", "claude", "gemini").any { id ->
            settingsStore.getAiProviderApiKey(id) != null
        }
    }

    /** Flow-friendly check for whether any AI provider is configured. */
    fun hasEnabledProviderFlow() = kotlinx.coroutines.flow.flow {
        emit(hasEnabledProvider())
    }

    /**
     * Fetch AI recommendations. Returns up to 5 albums and 5 artists.
     * Matches desktop's loadAiRecommendations() logic.
     */
    suspend fun loadRecommendations(): AiRecommendations {
        // Use the user's selected chat provider (matching desktop's selectedChatProvider),
        // falling back to the first configured provider if none is selected.
        val allProviders = listOf(
            Triple("chatgpt", chatGptProvider as AiChatProvider, "gpt-4o-mini"),
            Triple("claude", claudeProvider as AiChatProvider, "claude-sonnet-4-6-20250320"),
            Triple("gemini", geminiProvider as AiChatProvider, "gemini-2.0-flash"),
        )

        // Only consider providers that have an API key configured
        data class ConfiguredProvider(val id: String, val provider: AiChatProvider, val config: AiProviderConfig)
        val configured = allProviders.mapNotNull { (id, provider, defaultModel) ->
            val apiKey = settingsStore.getAiProviderApiKey(id) ?: return@mapNotNull null
            val model = settingsStore.getAiProviderModel(id).ifBlank { defaultModel }
            ConfiguredProvider(id, provider, AiProviderConfig(apiKey = apiKey, model = model))
        }

        if (configured.isEmpty()) {
            return AiRecommendations(emptyList(), emptyList())
        }

        // Prefer the user's last-used chat provider, fall back to first configured
        val selectedId = settingsStore.getSelectedChatProvider()
        val chosen = configured.firstOrNull { it.id == selectedId } ?: configured.first()
        val selectedProvider = chosen.provider
        val selectedConfig = chosen.config

        // Build listening context (only if user has opted in, matching desktop's sendListeningHistory toggle)
        val sendHistory = settingsStore.getSendListeningHistory()
        val contextInfo = if (sendHistory) buildListeningContext() else "No listening history provided. Recommend diverse, acclaimed music spanning multiple genres, eras, and regions."
        val theme = varietyThemes.random()

        // Build exclusion note from previous suggestions
        val exclusionNote = buildExclusionNote()

        val systemPrompt = """You are a music recommendation engine. You MUST respond with ONLY a valid JSON object, no markdown, no explanations, no text before or after the JSON. The JSON must have exactly this structure:
{"albums":[{"title":"...","artist":"...","reason":"..."}],"artists":[{"name":"...","reason":"..."}]}
Provide exactly 12 albums and 20 artists. Each "reason" should be one short sentence explaining why this recommendation fits. Recommendations should be things the user has NOT already listened to — suggest new discoveries, not things already in their library or recent history. IMPORTANT: Only recommend full-length studio albums. Do NOT recommend singles, EPs, compilations, live albums, soundtracks, or remix albums. Every album must be a well-known, officially released studio album with a full tracklist.
Variety guidance: $theme Be creative and surprising — avoid defaulting to the most obvious or popular choices."""

        val userPrompt = "Based on this listening profile, recommend 12 albums and 20 artists I should check out. For artists, go deep — suggest a wide variety spanning different genres, eras, and regions. Avoid defaulting to the most well-known names:\n\n$contextInfo$exclusionNote"

        val messages = mutableListOf(
            ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt),
            ChatMessage(role = ChatRole.USER, content = userPrompt),
        )
        // For Claude, prefill assistant response with "{" to force JSON output
        // (Claude has no native JSON mode, but respects assistant prefill)
        if (chosen.id == "claude") {
            messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = "{"))
        }

        val response = selectedProvider.chat(messages, emptyList(), selectedConfig)
        // For Claude, prepend the prefilled "{" since the API continues from it
        val rawContent = if (chosen.id == "claude") "{${response.content}" else response.content
        val parsed = parseRecommendations(rawContent)

        if (parsed.albums.isEmpty() && parsed.artists.isEmpty()) {
            Log.w(TAG, "AI response parsed to empty recommendations. Raw content: ${response.content.take(200)}")
            throw Exception("AI returned no valid recommendations — response may have been malformed")
        }

        // Track previous suggestions (keep last 20 albums, 50 artists like desktop)
        previousAlbums.addAll(parsed.albums)
        if (previousAlbums.size > 20) {
            val excess = previousAlbums.size - 20
            repeat(excess) { previousAlbums.removeAt(0) }
        }
        previousArtists.addAll(parsed.artists)
        if (previousArtists.size > 50) {
            val excess = previousArtists.size - 50
            repeat(excess) { previousArtists.removeAt(0) }
        }

        // Enrich with artwork, then return up to 5 each
        val enriched = enrichWithArtwork(
            albums = parsed.albums.take(5),
            artists = parsed.artists.take(5),
        )
        cachedRecommendations = enriched
        saveDiskCache(enriched)
        return enriched
    }

    /**
     * Build listening context from history and collection, matching desktop's logic.
     */
    private suspend fun buildListeningContext(): String {
        val parts = mutableListOf<String>()

        // Top artists from Last.fm (overall period)
        try {
            val topArtists = historyRepository.getTopArtists("overall", limit = 25)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topArtists?.data?.let { artists ->
                if (artists.isNotEmpty()) {
                    parts.add("Top artists: " + artists.map { it.name }.joinToString(", "))
                }
            }
        } catch (_: Exception) {}

        // Top tracks from Last.fm
        try {
            val topTracks = historyRepository.getTopTracks("overall", limit = 20)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topTracks?.data?.let { tracks ->
                if (tracks.isNotEmpty()) {
                    parts.add("Top tracks: " + tracks.map { "${it.artist} - ${it.title}" }.joinToString(", "))
                }
            }
        } catch (_: Exception) {}

        // Top albums from Last.fm
        try {
            val topAlbums = historyRepository.getTopAlbums("overall", limit = 15)
                .firstOrNull { it is Resource.Success } as? Resource.Success
            topAlbums?.data?.let { albums ->
                if (albums.isNotEmpty()) {
                    parts.add("Top albums: " + albums.map { "${it.artist} - ${it.name}" }.joinToString(", "))
                }
            }
        } catch (_: Exception) {}

        // Collection tracks — extract unique artists and albums
        try {
            val tracks = libraryRepository.getAllTracks()
                .firstOrNull()
            if (tracks != null && tracks.isNotEmpty()) {
                val collectionArtists = tracks.map { it.artist }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(15)
                    .map { it.key }
                if (collectionArtists.isNotEmpty()) {
                    // Filter out artists already mentioned in top artists
                    val mentioned = parts.firstOrNull { it.startsWith("Top artists:") }
                        ?.removePrefix("Top artists: ")
                        ?.split(", ")
                        ?.map { it.lowercase() }
                        ?.toSet() ?: emptySet()
                    val newArtists = collectionArtists.filter { it.lowercase() !in mentioned }
                    if (newArtists.isNotEmpty()) {
                        parts.add("Artists from saved songs: " + newArtists.joinToString(", "))
                    }
                }

                val collectionAlbums = tracks
                    .filter { !it.album.isNullOrBlank() && it.artist.isNotBlank() }
                    .groupBy { "${it.artist}|${it.album}" }
                    .entries.sortedByDescending { it.value.size }
                    .take(10)
                    .map { entry ->
                        val parts2 = entry.key.split("|", limit = 2)
                        "${parts2[0]} - ${parts2[1]}"
                    }
                if (collectionAlbums.isNotEmpty()) {
                    parts.add("Albums from saved songs: " + collectionAlbums.joinToString(", "))
                }
            }
        } catch (_: Exception) {}

        return parts.joinToString("\n").ifBlank {
            "No listening history available. Recommend diverse, acclaimed music."
        }
    }

    private fun buildExclusionNote(): String {
        if (previousAlbums.isEmpty() && previousArtists.isEmpty()) return ""

        val parts = mutableListOf<String>()
        if (previousAlbums.isNotEmpty()) {
            parts.add("Albums already suggested (DO NOT repeat these): " +
                previousAlbums.joinToString(", ") { "${it.artist} - ${it.title}" })
        }
        if (previousArtists.isNotEmpty()) {
            parts.add("Artists already suggested (DO NOT repeat these): " +
                previousArtists.joinToString(", ") { it.name })
        }
        return "\n\n" + parts.joinToString("\n")
    }

    /**
     * Enrich album and artist suggestions with artwork from MetadataService.
     * Looks up album art and artist images concurrently, matching desktop's
     * pre-fetch pattern.
     */
    private suspend fun enrichWithArtwork(
        albums: List<AiAlbumSuggestion>,
        artists: List<AiArtistSuggestion>,
    ): AiRecommendations = coroutineScope {
        // Enrich albums with artwork
        val enrichedAlbums = albums.map { album ->
            async {
                try {
                    val results = metadataService.searchAlbums(
                        "${album.artist} ${album.title}",
                        limit = 1,
                    )
                    val artworkUrl = results.firstOrNull()?.artworkUrl
                    if (artworkUrl != null) album.copy(artworkUrl = artworkUrl) else album
                } catch (_: Exception) {
                    album
                }
            }
        }.awaitAll()

        // Enrich artists with images
        val enrichedArtists = artists.map { artist ->
            async {
                try {
                    val info = metadataService.getArtistInfo(artist.name)
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) artist.copy(imageUrl = imageUrl) else artist
                } catch (_: Exception) {
                    artist
                }
            }
        }.awaitAll()

        AiRecommendations(albums = enrichedAlbums, artists = enrichedArtists)
    }

    /**
     * Extract a JSON object from a string that may contain surrounding text.
     * Finds the outermost `{...}` by tracking brace depth, which handles
     * plain JSON, markdown-wrapped JSON, and responses with preamble text.
     */
    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until content.length) {
            val c = content[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return content.substring(start, i + 1) }
        }
        return null
    }

    /**
     * Parse AI response JSON into recommendations.
     * Handles plain JSON, markdown code blocks, and responses with preamble text.
     */
    private fun parseRecommendations(content: String): AiRecommendations {
        // Extract JSON object from the response — handles plain JSON, markdown code blocks,
        // and responses with preamble/postscript text around the JSON.
        val jsonStr = extractJsonObject(content)
            ?: throw Exception("No JSON object found in AI response")

        val root = json.parseToJsonElement(jsonStr).jsonObject

        val albums = root["albums"]?.jsonArray?.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                AiAlbumSuggestion(
                    title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    artist = obj["artist"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                )
            } catch (_: Exception) { null }
        } ?: emptyList()

        val artists = root["artists"]?.jsonArray?.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                AiArtistSuggestion(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                )
            } catch (_: Exception) { null }
        } ?: emptyList()

        return AiRecommendations(albums = albums, artists = artists)
    }
}

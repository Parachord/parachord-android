# Shuffleupagus AI Chat Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add conversational DJ chat with 3 AI providers (ChatGPT, Claude, Gemini) and 8 DJ tools that control playback, search, queue, playlists, and shuffle.

**Architecture:** Four layers — AI provider implementations (OkHttp API calls), DJ tool definitions + executors (thin wrappers around existing app components), chat service (conversation orchestrator with tool-call loop), and UI (full-screen chat composable with provider selector). API keys configured in Settings Plug-Ins tab.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp, kotlinx.serialization, DataStore, Room

---

### Task 1: Data Classes & Provider Interface

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/AiChatProvider.kt`

**Step 1: Create the shared data classes and provider interface**

```kotlin
package com.parachord.android.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Roles in the conversation, matching desktop's message format. */
enum class ChatRole { USER, ASSISTANT, SYSTEM, TOOL }

/** A single message in the conversation history. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    /** Tool name — needed for Gemini's functionResponse format. */
    val toolName: String? = null,
)

/** A tool call requested by the AI. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
)

/** Response from an AI provider. */
data class AiChatResponse(
    val content: String,
    val toolCalls: List<ToolCall>? = null,
)

/** Configuration for an AI provider (API key, model, endpoint). */
data class AiProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val endpoint: String = "",
)

/** Metadata about an available AI provider for the UI. */
data class AiProviderInfo(
    val id: String,
    val name: String,
    val isConfigured: Boolean,
    val currentModel: String,
)

/** Interface that all AI providers implement. */
interface AiChatProvider {
    val id: String
    val name: String

    /**
     * Send messages with tool definitions to the AI and get a response.
     * Each provider translates to/from its native API format.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse
}

/** Schema definition for a DJ tool, sent to the AI provider. */
data class DjToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add AI chat data classes and provider interface
```

---

### Task 2: DJ Tool Definitions

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/tools/DjToolDefinitions.kt`

**Step 1: Create the 8 tool definitions matching desktop's dj-tools.js**

```kotlin
package com.parachord.android.ai.tools

import com.parachord.android.ai.DjToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * DJ tool definitions matching the desktop's dj-tools.js.
 * These JSON Schema definitions are sent to AI providers so they know
 * what tools are available and how to call them.
 */
object DjToolDefinitions {

    fun all(): List<DjToolDefinition> = listOf(
        play, control, search, queueAdd, queueClear, createPlaylist, shuffle, blockRecommendation,
    )

    val play = DjToolDefinition(
        name = "play",
        description = "Play a specific track by searching for it and starting playback immediately. Clears the queue before playing.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("artist") {
                    put("type", "string")
                    put("description", "The artist name")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "The track title")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("artist")); add(JsonPrimitive("title")) }
        },
    )

    val control = DjToolDefinition(
        name = "control",
        description = "Control music playback - pause, resume, skip to next, go to previous track",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("pause"))
                        add(JsonPrimitive("resume"))
                        add(JsonPrimitive("skip"))
                        add(JsonPrimitive("previous"))
                    }
                    put("description", "The playback action to perform")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
        },
    )

    val search = DjToolDefinition(
        name = "search",
        description = "Search for tracks across all music sources. Returns a list of matching tracks.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query (artist name, track title, or both)")
                }
                putJsonObject("limit") {
                    put("type", "number")
                    put("description", "Maximum number of results to return (default 10)")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        },
    )

    val queueAdd = DjToolDefinition(
        name = "queue_add",
        description = "Add one or more tracks to the playback queue. By default (playFirst=true), clears the queue, plays the first track immediately, and queues the rest. Set playFirst to false to add tracks to the existing queue without clearing it or starting playback.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tracks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("title") { put("type", "string") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("artist")); add(JsonPrimitive("title")) }
                    }
                    put("description", "Tracks to add to the queue")
                }
                putJsonObject("position") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("next")); add(JsonPrimitive("last")) }
                    put("description", "Add after current track (next) or at end of queue (last). Default: last")
                }
                putJsonObject("playFirst") {
                    put("type", "boolean")
                    put("description", "If true (default), clears the queue, plays the first track immediately, and queues the rest. If false, adds tracks to existing queue without clearing or starting playback.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("tracks")) }
        },
    )

    val queueClear = DjToolDefinition(
        name = "queue_clear",
        description = "Clear all tracks from the playback queue",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        },
    )

    val createPlaylist = DjToolDefinition(
        name = "create_playlist",
        description = "Create a new playlist with the specified tracks",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Name for the new playlist")
                }
                putJsonObject("tracks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("artist") { put("type", "string") }
                            putJsonObject("title") { put("type", "string") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("artist")); add(JsonPrimitive("title")) }
                    }
                    put("description", "Tracks to include in the playlist")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("tracks")) }
        },
    )

    val shuffle = DjToolDefinition(
        name = "shuffle",
        description = "Turn shuffle mode on or off",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to enable shuffle, false to disable")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("enabled")) }
        },
    )

    val blockRecommendation = DjToolDefinition(
        name = "block_recommendation",
        description = "Block an artist, album, or track from future AI recommendations. Use when user says \"don't recommend X\", \"I don't like X\", \"stop suggesting X\", etc.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("artist"))
                        add(JsonPrimitive("album"))
                        add(JsonPrimitive("track"))
                    }
                    put("description", "What to block: artist (blocks all their music), album, or track")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "For artists: the artist name. Not used for albums/tracks.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "For albums/tracks: the album or track title")
                }
                putJsonObject("artist") {
                    put("type", "string")
                    put("description", "For albums/tracks: the artist name")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("type")) }
        },
    )
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add DJ tool definitions matching desktop dj-tools.js
```

---

### Task 3: DJ Tool Executor

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/tools/DjToolExecutor.kt`

**Dependencies to inject:** `PlaybackController`, `PlaybackStateHolder`, `MetadataService`, `LibraryRepository`, `SettingsStore`

**Step 1: Create the executor**

```kotlin
package com.parachord.android.ai.tools

import android.util.Log
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes DJ tools called by the AI, matching desktop's dj-tools.js executors.
 * Each tool is a thin wrapper around existing app components.
 */
@Singleton
class DjToolExecutor @Inject constructor(
    private val playbackController: PlaybackController,
    private val stateHolder: PlaybackStateHolder,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "DjToolExecutor"
    }

    /**
     * Execute a tool by name with the given arguments.
     * Returns a JSON-like map that gets serialized back to the AI.
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
                else -> mapOf("success" to false, "error" to "Unknown tool: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error ($name)", e)
            mapOf("success" to false, "error" to (e.message ?: "Tool execution failed"))
        }
    }

    private suspend fun executPlay(args: Map<String, Any?>): Map<String, Any?> {
        val artist = args["artist"] as? String ?: return mapOf("success" to false, "error" to "Missing artist")
        val title = args["title"] as? String ?: return mapOf("success" to false, "error" to "Missing title")

        val results = metadataService.searchTracks("$artist $title", limit = 10)
        if (results.isEmpty()) {
            return mapOf("success" to false, "error" to "Could not find \"$title\" by $artist")
        }

        // Find best match (exact artist/title match preferred)
        val bestMatch = results.find {
            it.artist.equals(artist, ignoreCase = true) && it.title.equals(title, ignoreCase = true)
        } ?: results.first()

        val track = bestMatch.toTrackEntity()
        playbackController.playTrack(track)

        return mapOf(
            "success" to true,
            "track" to mapOf(
                "artist" to track.artist,
                "title" to track.title,
                "album" to track.album,
            ),
        )
    }

    private fun executeControl(args: Map<String, Any?>): Map<String, Any?> {
        val action = args["action"] as? String ?: return mapOf("success" to false, "error" to "Missing action")
        return when (action) {
            "pause" -> {
                val state = stateHolder.state.value
                if (state.isPlaying) playbackController.togglePlayPause()
                mapOf("success" to true, "action" to "paused")
            }
            "resume" -> {
                val state = stateHolder.state.value
                if (!state.isPlaying) playbackController.togglePlayPause()
                mapOf("success" to true, "action" to "resumed")
            }
            "skip" -> {
                playbackController.skipNext()
                val nowPlaying = stateHolder.state.value.currentTrack
                mapOf(
                    "success" to true,
                    "action" to "skipped",
                    "nowPlaying" to if (nowPlaying != null) mapOf(
                        "artist" to nowPlaying.artist,
                        "title" to nowPlaying.title,
                    ) else null,
                )
            }
            "previous" -> {
                playbackController.skipPrevious()
                mapOf("success" to true, "action" to "previous")
            }
            else -> mapOf("success" to false, "error" to "Unknown action: $action")
        }
    }

    private suspend fun executeSearch(args: Map<String, Any?>): Map<String, Any?> {
        val query = args["query"] as? String ?: return mapOf("success" to false, "error" to "Missing query")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10

        val results = metadataService.searchTracks(query, limit = limit)
        val limitedResults = results.take(limit).map { r ->
            mapOf(
                "artist" to r.artist,
                "title" to r.title,
                "album" to r.album,
            )
        }

        return mapOf(
            "success" to true,
            "results" to limitedResults,
            "total" to results.size,
        )
    }

    private suspend fun executeQueueAdd(args: Map<String, Any?>): Map<String, Any?> {
        val tracksArg = args["tracks"] as? List<*> ?: return mapOf("success" to false, "error" to "Missing tracks")
        val playFirst = (args["playFirst"] as? Boolean) ?: true

        val tracks = tracksArg.filterIsInstance<Map<*, *>>().map { t ->
            val artist = t["artist"] as? String ?: ""
            val title = t["title"] as? String ?: ""
            TrackEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                artist = artist,
            )
        }

        if (tracks.isEmpty()) return mapOf("success" to false, "error" to "No valid tracks")

        var startedPlaying = false

        if (playFirst) {
            playbackController.clearQueue()
            // Search and play first track
            val first = tracks.first()
            val results = metadataService.searchTracks("${first.artist} ${first.title}", limit = 5)
            if (results.isNotEmpty()) {
                val match = results.find {
                    it.artist.equals(first.artist, ignoreCase = true) &&
                        it.title.equals(first.title, ignoreCase = true)
                } ?: results.first()
                playbackController.playTrack(match.toTrackEntity())
                startedPlaying = true
            }
            // Queue the rest
            if (tracks.size > 1) {
                playbackController.addToQueue(tracks.drop(1))
            }
        } else {
            playbackController.addToQueue(tracks)
        }

        return mapOf(
            "success" to true,
            "added" to tracks.size,
            "nowPlaying" to startedPlaying,
        )
    }

    private fun executeQueueClear(): Map<String, Any?> {
        playbackController.clearQueue()
        return mapOf("success" to true)
    }

    private suspend fun executeCreatePlaylist(args: Map<String, Any?>): Map<String, Any?> {
        val name = args["name"] as? String ?: return mapOf("success" to false, "error" to "Missing name")
        val tracksArg = args["tracks"] as? List<*> ?: return mapOf("success" to false, "error" to "Missing tracks")

        if (tracksArg.isEmpty()) {
            return mapOf("success" to false, "error" to "No tracks specified for playlist")
        }

        val playlistId = UUID.randomUUID().toString()
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            trackCount = tracksArg.size,
        )
        libraryRepository.addPlaylist(playlist)

        // Add tracks to library associated with this playlist
        val trackEntities = tracksArg.filterIsInstance<Map<*, *>>().map { t ->
            TrackEntity(
                id = UUID.randomUUID().toString(),
                title = t["title"] as? String ?: "",
                artist = t["artist"] as? String ?: "",
                album = t["album"] as? String,
            )
        }
        libraryRepository.addTracks(trackEntities)

        return mapOf(
            "success" to true,
            "playlist" to mapOf(
                "id" to playlistId,
                "name" to name,
                "trackCount" to trackEntities.size,
            ),
        )
    }

    private fun executeShuffle(args: Map<String, Any?>): Map<String, Any?> {
        val enabled = (args["enabled"] as? Boolean) ?: true
        val current = stateHolder.state.value.shuffleEnabled
        if (current != enabled) {
            playbackController.toggleShuffle()
        }
        return mapOf("success" to true, "shuffle" to enabled)
    }

    private suspend fun executeBlockRecommendation(args: Map<String, Any?>): Map<String, Any?> {
        val type = args["type"] as? String ?: return mapOf("success" to false, "error" to "Missing type")

        return when (type) {
            "artist" -> {
                val name = args["name"] as? String ?: return mapOf("success" to false, "error" to "Artist name is required")
                settingsStore.addBlockedRecommendation("artist:$name")
                mapOf("success" to true, "blocked" to mapOf("type" to "artist", "name" to name))
            }
            "album" -> {
                val title = args["title"] as? String ?: return mapOf("success" to false, "error" to "Album title required")
                val artist = args["artist"] as? String ?: return mapOf("success" to false, "error" to "Artist required")
                settingsStore.addBlockedRecommendation("album:$artist:$title")
                mapOf("success" to true, "blocked" to mapOf("type" to "album", "title" to title, "artist" to artist))
            }
            "track" -> {
                val title = args["title"] as? String ?: return mapOf("success" to false, "error" to "Track title required")
                val artist = args["artist"] as? String ?: return mapOf("success" to false, "error" to "Artist required")
                settingsStore.addBlockedRecommendation("track:$artist:$title")
                mapOf("success" to true, "blocked" to mapOf("type" to "track", "title" to title, "artist" to artist))
            }
            else -> mapOf("success" to false, "error" to "Invalid type. Use artist, album, or track.")
        }
    }
}

/** Convert a TrackSearchResult to a TrackEntity for playback. */
private fun com.parachord.android.data.metadata.TrackSearchResult.toTrackEntity() = TrackEntity(
    id = spotifyId ?: UUID.randomUUID().toString(),
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    artworkUrl = artworkUrl,
    sourceUrl = previewUrl,
    spotifyUri = spotifyId?.let { "spotify:track:$it" },
)
```

**Step 2: Add blocklist methods to SettingsStore**

In `app/src/main/java/com/parachord/android/data/store/SettingsStore.kt`, add:

```kotlin
// In companion object:
val BLOCKED_RECOMMENDATIONS = stringPreferencesKey("blocked_recommendations")

// New methods:
suspend fun getBlockedRecommendations(): Set<String> {
    val raw = dataStore.data.first()[BLOCKED_RECOMMENDATIONS] ?: return emptySet()
    return raw.split("\n").filter { it.isNotBlank() }.toSet()
}

fun getBlockedRecommendationsFlow(): Flow<Set<String>> =
    dataStore.data.map { prefs ->
        val raw = prefs[BLOCKED_RECOMMENDATIONS] ?: return@map emptySet()
        raw.split("\n").filter { it.isNotBlank() }.toSet()
    }

suspend fun addBlockedRecommendation(entry: String) {
    val current = getBlockedRecommendations().toMutableSet()
    current.add(entry)
    dataStore.edit { it[BLOCKED_RECOMMENDATIONS] = current.joinToString("\n") }
}
```

**Step 3: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat: add DJ tool executor with 8 tools matching desktop
```

---

### Task 4: Chat Context Provider

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/ChatContextProvider.kt`

**Step 1: Create the context provider that gathers app state for the system prompt**

```kotlin
package com.parachord.android.ai

import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers current app state for the AI system prompt.
 * Mirrors desktop's createContextGetter() in ai-chat-integration.js.
 */
@Singleton
class ChatContextProvider @Inject constructor(
    private val stateHolder: PlaybackStateHolder,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val SYSTEM_PROMPT_TEMPLATE = """You are a helpful music DJ assistant for Parachord, a multi-source music player.
You can control playback, search for music, manage the queue, and answer questions about the user's music.

TODAY'S DATE: {{currentDate}}

CURRENT STATE:
{{currentState}}

GUIDELINES:
- Be concise and helpful
- When taking actions, confirm what you did
- If you need to play or queue music, use the search tool first to find tracks, then use play or queue_add
- For playback control (pause, skip, etc.), use the control tool
- If a track isn't found, suggest alternatives or ask for clarification
- Keep responses brief - this is a music app, not a chat app
- When users ask about "recent" or "last X years", use today's date to calculate the correct time range"""
    }

    /** Build the full system prompt with current app state injected. */
    suspend fun buildSystemPrompt(): String {
        val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
        val currentDate = dateFormat.format(Date())
        val currentState = formatState()

        return SYSTEM_PROMPT_TEMPLATE
            .replace("{{currentDate}}", currentDate)
            .replace("{{currentState}}", currentState)
    }

    private suspend fun formatState(): String {
        val state = stateHolder.state.value
        val lines = mutableListOf<String>()

        // Now playing
        val track = state.currentTrack
        if (track != null) {
            lines.add("Now Playing: \"${track.title}\" by ${track.artist}")
            track.album?.let { lines.add("  Album: $it") }
            track.resolver?.let { lines.add("  Source: $it") }
            lines.add("  State: ${if (state.isPlaying) "playing" else "paused"}")
        } else {
            lines.add("Nothing is currently playing.")
        }

        // Queue
        val queue = state.upNext
        if (queue.isNotEmpty()) {
            lines.add("")
            lines.add("Queue (${queue.size} tracks):")
            queue.take(10).forEachIndexed { i, t ->
                lines.add("  ${i + 1}. \"${t.title}\" by ${t.artist}")
            }
            if (queue.size > 10) {
                lines.add("  ... and ${queue.size - 10} more")
            }
        } else {
            lines.add("")
            lines.add("Queue is empty.")
        }

        // Shuffle state
        lines.add("")
        lines.add("Shuffle: ${if (state.shuffleEnabled) "On" else "Off"}")

        // Recommendation blocklist
        val blocklist = settingsStore.getBlockedRecommendations()
        if (blocklist.isNotEmpty()) {
            lines.add("")
            lines.add("Blocked from Recommendations (${blocklist.size} items):")
            val artists = blocklist.filter { it.startsWith("artist:") }.map { it.removePrefix("artist:") }
            val albums = blocklist.filter { it.startsWith("album:") }.map { it.removePrefix("album:") }
            val tracks = blocklist.filter { it.startsWith("track:") }.map { it.removePrefix("track:") }
            if (artists.isNotEmpty()) lines.add("  Artists: ${artists.joinToString(", ")}")
            if (albums.isNotEmpty()) lines.add("  Albums: ${albums.joinToString(", ")}")
            if (tracks.isNotEmpty()) lines.add("  Tracks: ${tracks.joinToString(", ")}")
        }

        return lines.joinToString("\n")
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add chat context provider for system prompt injection
```

---

### Task 5: ChatGPT Provider

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/providers/ChatGptProvider.kt`

**Step 1: Implement the OpenAI API chat provider**

```kotlin
package com.parachord.android.ai.providers

import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatResponse
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.DjToolDefinition
import com.parachord.android.ai.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatGPT provider using OpenAI API.
 * Mirrors desktop's chatgpt.axe chat implementation.
 */
@Singleton
class ChatGptProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id = "chatgpt"
    override val name = "ChatGPT"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw Exception("OpenAI API key not configured")

        val model = config.model.ifBlank { "gpt-4o-mini" }

        // Convert messages to OpenAI format
        val openaiMessages = buildJsonArray {
            for (msg in messages) {
                add(buildJsonObject {
                    put("role", when (msg.role) {
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.SYSTEM -> "system"
                        ChatRole.TOOL -> "tool"
                    })
                    if (msg.role == ChatRole.TOOL) {
                        put("tool_call_id", msg.toolCallId ?: "")
                        put("content", msg.content)
                    } else if (msg.role == ChatRole.ASSISTANT && msg.toolCalls != null) {
                        put("content", msg.content.ifBlank { null as String? } ?: JsonNull)
                        put("tool_calls", buildJsonArray {
                            for (tc in msg.toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", json.encodeToString(JsonElement.serializer(), mapToJsonElement(tc.arguments)))
                                    })
                                })
                            }
                        })
                    } else {
                        put("content", msg.content)
                    }
                })
            }
        }

        // Convert tools to OpenAI format
        val openaiTools = buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                })
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("messages", openaiMessages)
            put("tools", openaiTools)
            put("tool_choice", "auto")
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorData = try { json.parseToJsonElement(responseBody).jsonObject } catch (_: Exception) { null }
            val errorMsg = errorData?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "OpenAI API error: ${response.code}"
            throw Exception(errorMsg)
        }

        val data = json.parseToJsonElement(responseBody).jsonObject
        val choice = data["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val message = choice?.get("message")?.jsonObject ?: throw Exception("No response message")

        val content = message["content"]?.jsonPrimitive?.content ?: ""
        val toolCallsJson = message["tool_calls"]?.jsonArray

        val toolCalls = toolCallsJson?.map { tc ->
            val tcObj = tc.jsonObject
            val function = tcObj["function"]?.jsonObject ?: throw Exception("Invalid tool call")
            ToolCall(
                id = tcObj["id"]?.jsonPrimitive?.content ?: "",
                name = function["name"]?.jsonPrimitive?.content ?: "",
                arguments = jsonElementToMap(json.parseToJsonElement(function["arguments"]?.jsonPrimitive?.content ?: "{}")),
            )
        }

        AiChatResponse(content = content, toolCalls = toolCalls?.takeIf { it.isNotEmpty() })
    }
}

/** Convert a Map<String, Any?> to a JsonElement for serialization. */
internal fun mapToJsonElement(map: Map<String, Any?>): JsonElement = buildJsonObject {
    for ((key, value) in map) {
        when (value) {
            null -> put(key, JsonNull)
            is String -> put(key, value)
            is Number -> put(key, value.toDouble())
            is Boolean -> put(key, value)
            is Map<*, *> -> put(key, mapToJsonElement(value as Map<String, Any?>))
            is List<*> -> put(key, buildJsonArray {
                for (item in value) {
                    when (item) {
                        null -> add(JsonNull)
                        is String -> add(JsonPrimitive(item))
                        is Number -> add(JsonPrimitive(item.toDouble()))
                        is Boolean -> add(JsonPrimitive(item))
                        is Map<*, *> -> add(mapToJsonElement(item as Map<String, Any?>))
                        else -> add(JsonPrimitive(item.toString()))
                    }
                }
            })
            else -> put(key, value.toString())
        }
    }
}

/** Convert a JsonElement back to a Map<String, Any?> for tool argument passing. */
internal fun jsonElementToMap(element: JsonElement): Map<String, Any?> {
    if (element !is JsonObject) return emptyMap()
    return element.entries.associate { (key, value) ->
        key to jsonElementToValue(value)
    }
}

internal fun jsonElementToValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" || element.content == "false" -> element.content.toBoolean()
        element.content.contains('.') -> element.content.toDoubleOrNull()
        else -> element.content.toIntOrNull() ?: element.content.toLongOrNull() ?: element.content
    }
    is JsonArray -> element.map { jsonElementToValue(it) }
    is JsonObject -> jsonElementToMap(element)
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add ChatGPT provider with OpenAI API integration
```

---

### Task 6: Claude Provider

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/providers/ClaudeProvider.kt`

**Step 1: Implement the Anthropic API chat provider**

```kotlin
package com.parachord.android.ai.providers

import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatResponse
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.DjToolDefinition
import com.parachord.android.ai.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claude provider using Anthropic API.
 * Mirrors desktop's claude.axe chat implementation.
 *
 * Key differences from OpenAI format:
 * - System prompt is a top-level field, not a message
 * - Tool calls are `tool_use` content blocks
 * - Tool results go as user messages with `tool_result` content blocks
 */
@Singleton
class ClaudeProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id = "claude"
    override val name = "Claude"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw Exception("API key is required")

        val model = config.model.ifBlank { "claude-sonnet-4-20250514" }

        // Extract system prompt and convert messages to Claude format
        var systemPrompt = ""
        val claudeMessages = buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    ChatRole.SYSTEM -> systemPrompt = msg.content
                    ChatRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("content", msg.content)
                    })
                    ChatRole.ASSISTANT -> {
                        if (msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                            // Assistant message with tool calls → content blocks
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", buildJsonArray {
                                    if (msg.content.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", msg.content)
                                        })
                                    }
                                    for (tc in msg.toolCalls) {
                                        add(buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", tc.id)
                                            put("name", tc.name)
                                            put("input", mapToJsonElement(tc.arguments))
                                        })
                                    }
                                })
                            })
                        } else {
                            add(buildJsonObject {
                                put("role", "assistant")
                                put("content", msg.content)
                            })
                        }
                    }
                    ChatRole.TOOL -> {
                        // Tool results go as user messages with tool_result content
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", msg.toolCallId ?: "")
                                    put("content", msg.content)
                                })
                            })
                        })
                    }
                }
            }
        }

        // Convert tools to Claude format
        val claudeTools = buildJsonArray {
            for (tool in tools) {
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("input_schema", tool.parameters)
                })
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            put("messages", claudeMessages)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            if (tools.isNotEmpty()) put("tools", claudeTools)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorData = try { json.parseToJsonElement(responseBody).jsonObject } catch (_: Exception) { null }
            val errorMsg = errorData?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
            val errorType = errorData?.get("error")?.jsonObject?.get("type")?.jsonPrimitive?.content ?: ""

            when {
                errorType == "authentication_error" -> throw Exception("Invalid API key. Please check your Claude API key in settings.")
                response.code == 429 -> throw Exception("Rate limit reached. Please wait a moment and try again.")
                response.code == 529 -> throw Exception("Claude is currently overloaded. Please try again in a few moments.")
                else -> throw Exception(errorMsg ?: "Claude API error (${response.code})")
            }
        }

        val data = json.parseToJsonElement(responseBody).jsonObject
        val contentBlocks = data["content"]?.jsonArray ?: throw Exception("No content in response")

        var content = ""
        val toolCalls = mutableListOf<ToolCall>()

        for (block in contentBlocks) {
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.content) {
                "text" -> content += blockObj["text"]?.jsonPrimitive?.content ?: ""
                "tool_use" -> {
                    toolCalls.add(ToolCall(
                        id = blockObj["id"]?.jsonPrimitive?.content ?: "",
                        name = blockObj["name"]?.jsonPrimitive?.content ?: "",
                        arguments = jsonElementToMap(blockObj["input"] ?: buildJsonObject {}),
                    ))
                }
            }
        }

        AiChatResponse(content = content, toolCalls = toolCalls.takeIf { it.isNotEmpty() })
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add Claude provider with Anthropic API integration
```

---

### Task 7: Gemini Provider

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/providers/GeminiProvider.kt`

**Step 1: Implement the Google AI API chat provider**

```kotlin
package com.parachord.android.ai.providers

import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatResponse
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.DjToolDefinition
import com.parachord.android.ai.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Gemini provider using Google AI API.
 * Mirrors desktop's gemini.axe chat implementation.
 *
 * Key differences:
 * - API key in URL query parameter, not header
 * - System prompt as `system_instruction`
 * - Roles: `user`/`model` (not `assistant`)
 * - Tool calls as `functionCall` parts, results as `functionResponse`
 */
@Singleton
class GeminiProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : AiChatProvider {

    override val id = "gemini"
    override val name = "Google Gemini"

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<DjToolDefinition>,
        config: AiProviderConfig,
    ): AiChatResponse = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw Exception("Google AI API key not configured")

        val model = config.model.ifBlank { "gemini-2.0-flash-001" }

        // Convert messages to Gemini format
        var systemInstruction: JsonElement? = null
        val contents = buildJsonArray {
            for (msg in messages) {
                when (msg.role) {
                    ChatRole.SYSTEM -> {
                        systemInstruction = buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", msg.content) })
                            })
                        }
                    }
                    ChatRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                    ChatRole.ASSISTANT -> add(buildJsonObject {
                        put("role", "model")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                    ChatRole.TOOL -> add(buildJsonObject {
                        put("role", "function")
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", msg.toolName ?: msg.toolCallId ?: "")
                                    put("response", buildJsonObject {
                                        put("result", msg.content)
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }

        // Convert tools to Gemini format
        val geminiTools = buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    }
                })
            })
        }

        val body = buildJsonObject {
            put("contents", contents)
            put("generationConfig", buildJsonObject { put("temperature", 0.7) })
            systemInstruction?.let { put("system_instruction", it) }
            if (tools.isNotEmpty()) put("tools", geminiTools)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${config.apiKey}"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorData = try { json.parseToJsonElement(responseBody).jsonObject } catch (_: Exception) { null }
            val errorMsg = errorData?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Gemini API error: ${response.code}"
            throw Exception(errorMsg)
        }

        val data = json.parseToJsonElement(responseBody).jsonObject
        val candidate = data["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: buildJsonArray {}

        var content = ""
        val toolCalls = mutableListOf<ToolCall>()

        for (part in parts) {
            val partObj = part.jsonObject
            partObj["text"]?.jsonPrimitive?.content?.let { content += it }
            partObj["functionCall"]?.jsonObject?.let { fc ->
                toolCalls.add(ToolCall(
                    id = "call_${System.currentTimeMillis()}_${toolCalls.size}",
                    name = fc["name"]?.jsonPrimitive?.content ?: "",
                    arguments = jsonElementToMap(fc["args"] ?: buildJsonObject {}),
                ))
            }
        }

        AiChatResponse(content = content, toolCalls = toolCalls.takeIf { it.isNotEmpty() })
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add Gemini provider with Google AI API integration
```

---

### Task 8: AI Chat Service

**Files:**
- Create: `app/src/main/java/com/parachord/android/ai/AiChatService.kt`

**Step 1: Create the conversation orchestrator**

```kotlin
package com.parachord.android.ai

import android.util.Log
import com.parachord.android.ai.tools.DjToolDefinitions
import com.parachord.android.ai.tools.DjToolExecutor
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates AI chat conversations, matching desktop's ai-chat.js AIChatService.
 *
 * Manages conversation history, builds system prompts with app context,
 * sends messages to providers, and handles the tool-call loop.
 */
@Singleton
class AiChatService @Inject constructor(
    private val toolExecutor: DjToolExecutor,
    private val contextProvider: ChatContextProvider,
    private val json: Json,
) {
    companion object {
        private const val TAG = "AiChatService"
        private const val MAX_HISTORY_LENGTH = 50
        private const val MAX_TOOL_ITERATIONS = 5
    }

    /** Per-provider conversation histories. */
    private val histories = mutableMapOf<String, MutableList<ChatMessage>>()

    /** Get or create history for a provider. */
    private fun getHistory(providerId: String): MutableList<ChatMessage> =
        histories.getOrPut(providerId) { mutableListOf() }

    /**
     * Send a user message and get an AI response.
     * Handles the full tool-call loop: AI calls tools → execute → send results → repeat.
     *
     * @param provider The AI provider to use
     * @param config Provider configuration (API key, model)
     * @param userMessage The user's message
     * @param onProgress Callback for progress updates during tool execution
     * @return The final assistant response content
     */
    suspend fun sendMessage(
        provider: AiChatProvider,
        config: AiProviderConfig,
        userMessage: String,
        onProgress: (String) -> Unit = {},
    ): String {
        val history = getHistory(provider.id)

        // Add user message
        history.add(ChatMessage(role = ChatRole.USER, content = userMessage))
        trimHistory(history)

        // Build messages with system prompt
        val systemPrompt = contextProvider.buildSystemPrompt()
        val messagesWithSystem = listOf(
            ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt),
        ) + history

        val tools = DjToolDefinitions.all()

        // Initial call to provider
        val response = try {
            provider.chat(messagesWithSystem, tools, config)
        } catch (e: Exception) {
            Log.e(TAG, "AI provider error", e)
            val errorMessage = formatProviderError(e)
            history.add(ChatMessage(role = ChatRole.ASSISTANT, content = errorMessage))
            return errorMessage
        }

        // Handle tool calls if any
        if (response.toolCalls != null && response.toolCalls.isNotEmpty()) {
            return handleToolCalls(provider, config, response, tools, history, onProgress)
        }

        // No tool calls — return response directly
        history.add(ChatMessage(role = ChatRole.ASSISTANT, content = response.content))
        return response.content
    }

    private suspend fun handleToolCalls(
        provider: AiChatProvider,
        config: AiProviderConfig,
        initialResponse: AiChatResponse,
        tools: List<DjToolDefinition>,
        history: MutableList<ChatMessage>,
        onProgress: (String) -> Unit,
    ): String {
        var currentResponse = initialResponse
        var iterations = 0

        while (currentResponse.toolCalls != null && currentResponse.toolCalls.isNotEmpty() && iterations < MAX_TOOL_ITERATIONS) {
            iterations++

            // Add assistant message with tool calls
            history.add(ChatMessage(
                role = ChatRole.ASSISTANT,
                content = currentResponse.content,
                toolCalls = currentResponse.toolCalls,
            ))

            // Execute each tool call
            for (call in currentResponse.toolCalls) {
                onProgress(toolProgressText(call.name))
                Log.d(TAG, "Executing tool: ${call.name} with args: ${call.arguments}")

                val result = toolExecutor.execute(call.name, call.arguments)
                val resultJson = json.encodeToString(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    com.parachord.android.ai.providers.mapToJsonElement(result),
                )

                // Add tool result message
                history.add(ChatMessage(
                    role = ChatRole.TOOL,
                    content = resultJson,
                    toolCallId = call.id,
                    toolName = call.name,
                ))
            }

            // Follow-up call with tool results
            val systemPrompt = contextProvider.buildSystemPrompt()
            val messagesWithSystem = listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = systemPrompt),
            ) + history

            currentResponse = try {
                provider.chat(messagesWithSystem, tools, config)
            } catch (e: Exception) {
                Log.e(TAG, "AI provider error during tool follow-up", e)
                val errorMessage = "I encountered an error while processing. Please try again."
                history.add(ChatMessage(role = ChatRole.ASSISTANT, content = errorMessage))
                return errorMessage
            }
        }

        // Add final response
        val finalContent = currentResponse.content.ifBlank { "Done." }
        history.add(ChatMessage(role = ChatRole.ASSISTANT, content = finalContent))
        return finalContent
    }

    /** Get conversation history for display. */
    fun getDisplayMessages(providerId: String): List<ChatMessage> =
        getHistory(providerId).filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }

    /** Get full history for a provider (including tool messages). */
    fun getFullHistory(providerId: String): List<ChatMessage> =
        getHistory(providerId).toList()

    /** Clear conversation for a provider. */
    fun clearHistory(providerId: String) {
        histories[providerId]?.clear()
    }

    /** Clear all histories. */
    fun clearAllHistories() {
        histories.clear()
    }

    private fun trimHistory(history: MutableList<ChatMessage>) {
        if (history.size > MAX_HISTORY_LENGTH) {
            val first = history.first()
            val rest = history.takeLast(MAX_HISTORY_LENGTH - 1)
            history.clear()
            history.add(first)
            history.addAll(rest)
        }
    }

    private fun formatProviderError(error: Exception): String {
        val message = error.message ?: ""
        return when {
            message.contains("ECONNREFUSED") || message.contains("Unable to resolve host") ||
                message.contains("failed to connect") ->
                "I couldn't connect to the AI service. Please check your internet connection."
            message.contains("401") || message.contains("Unauthorized") || message.contains("Invalid API key") ->
                "Invalid API key. Please check your settings."
            message.contains("429") || message.contains("rate limit", ignoreCase = true) ->
                "Rate limit reached. Please try again in a moment."
            message.contains("404") || message.contains("not found", ignoreCase = true) ->
                "The AI model wasn't found. Please check your settings."
            else -> message.ifBlank { "Sorry, I encountered an error." }
        }
    }

    private fun toolProgressText(toolName: String): String = when (toolName) {
        "play" -> "Playing..."
        "control" -> "Controlling playback..."
        "search" -> "Searching..."
        "queue_add" -> "Adding to queue..."
        "queue_clear" -> "Clearing queue..."
        "create_playlist" -> "Creating playlist..."
        "shuffle" -> "Toggling shuffle..."
        "block_recommendation" -> "Updating blocklist..."
        else -> "Working..."
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add AI chat service with conversation and tool-call loop
```

---

### Task 9: Settings Store & ViewModel for AI Providers

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/store/SettingsStore.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/settings/SettingsViewModel.kt`

**Step 1: Add AI provider preference keys and methods to SettingsStore**

Add to `companion object`:
```kotlin
val CHATGPT_API_KEY = stringPreferencesKey("chatgpt_api_key")
val CHATGPT_MODEL = stringPreferencesKey("chatgpt_model")
val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
val CLAUDE_MODEL = stringPreferencesKey("claude_model")
val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
val GEMINI_MODEL = stringPreferencesKey("gemini_model")
```

Add methods:
```kotlin
// --- AI Providers ---

suspend fun getAiProviderApiKey(providerId: String): String? {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_API_KEY
        "claude" -> CLAUDE_API_KEY
        "gemini" -> GEMINI_API_KEY
        else -> return null
    }
    return dataStore.data.first()[key]?.ifBlank { null }
}

fun getAiProviderApiKeyFlow(providerId: String): Flow<String?> {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_API_KEY
        "claude" -> CLAUDE_API_KEY
        "gemini" -> GEMINI_API_KEY
        else -> return kotlinx.coroutines.flow.flowOf(null)
    }
    return dataStore.data.map { it[key]?.ifBlank { null } }
}

suspend fun setAiProviderApiKey(providerId: String, apiKey: String) {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_API_KEY
        "claude" -> CLAUDE_API_KEY
        "gemini" -> GEMINI_API_KEY
        else -> return
    }
    dataStore.edit { it[key] = apiKey }
}

suspend fun clearAiProviderApiKey(providerId: String) {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_API_KEY
        "claude" -> CLAUDE_API_KEY
        "gemini" -> GEMINI_API_KEY
        else -> return
    }
    dataStore.edit { it.remove(key) }
}

suspend fun getAiProviderModel(providerId: String): String {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_MODEL
        "claude" -> CLAUDE_MODEL
        "gemini" -> GEMINI_MODEL
        else -> return ""
    }
    return dataStore.data.first()[key] ?: ""
}

suspend fun setAiProviderModel(providerId: String, model: String) {
    val key = when (providerId) {
        "chatgpt" -> CHATGPT_MODEL
        "claude" -> CLAUDE_MODEL
        "gemini" -> GEMINI_MODEL
        else -> return
    }
    dataStore.edit { it[key] = model }
}
```

**Step 2: Add AI provider state to SettingsViewModel**

Add flows and functions:
```kotlin
val chatGptConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("chatgpt")
    .map { it != null }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

val claudeConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("claude")
    .map { it != null }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

val geminiConnected: StateFlow<Boolean> = settingsStore.getAiProviderApiKeyFlow("gemini")
    .map { it != null }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

fun saveAiProviderConfig(providerId: String, apiKey: String, model: String) {
    viewModelScope.launch {
        settingsStore.setAiProviderApiKey(providerId, apiKey)
        if (model.isNotBlank()) settingsStore.setAiProviderModel(providerId, model)
    }
}

fun clearAiProvider(providerId: String) {
    viewModelScope.launch { settingsStore.clearAiProviderApiKey(providerId) }
}
```

**Step 3: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat: add AI provider settings to SettingsStore and SettingsViewModel
```

---

### Task 10: Settings Screen — AI Plugin Config UI

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/settings/SettingsScreen.kt`

**Step 1: Add 3 AI plugins to builtInPlugins list**

Add after the existing META_SERVICE plugins (Wikipedia):
```kotlin
PluginInfo(
    id = "chatgpt",
    name = "ChatGPT",
    resolverId = "chatgpt",
    bgColor = Color(0xFF10A37F),
    category = PluginCategory.META_SERVICE,
    capabilities = listOf("AI DJ", "Chat"),
    description = "Generate playlists and chat with AI DJ using ChatGPT.",
),
PluginInfo(
    id = "claude",
    name = "Claude",
    resolverId = "claude",
    bgColor = Color(0xFFD97757),
    category = PluginCategory.META_SERVICE,
    capabilities = listOf("AI DJ", "Chat"),
    description = "Anthropic's Claude — thoughtful and capable AI assistant.",
),
PluginInfo(
    id = "gemini",
    name = "Google Gemini",
    resolverId = "gemini",
    bgColor = Color(0xFF4285F4),
    category = PluginCategory.META_SERVICE,
    capabilities = listOf("AI DJ", "Chat"),
    description = "Generate playlists and chat with AI DJ using Google Gemini.",
),
```

**Step 2: Wire connected state for AI providers in PlugInsTab**

Add parameters and state collection for `chatGptConnected`, `claudeConnected`, `geminiConnected` alongside the existing `isConnected()` function. Add `onSaveAiConfig: (String, String, String) -> Unit` and `onClearAiProvider: (String) -> Unit` callbacks.

Update `isConnected()`:
```kotlin
"chatgpt" -> chatGptConnected
"claude" -> claudeConnected
"gemini" -> geminiConnected
```

**Step 3: Create AiProviderConfig composable for the config sheet**

Each AI provider config sheet should contain:
- Status indicator (connected/not configured)
- Clickable link to get API key:
  - ChatGPT: "platform.openai.com →" → `https://platform.openai.com/api-keys`
  - Claude: "console.anthropic.com →" → `https://console.anthropic.com/settings/keys`
  - Gemini: "ai.google.dev →" → `https://aistudio.google.com/apikey`
- Password-style API key input
- Model dropdown:
  - ChatGPT: gpt-4o-mini (default), gpt-4o, gpt-4-turbo, gpt-3.5-turbo
  - Claude: claude-sonnet-4-20250514 (default), claude-3-5-sonnet, claude-3-5-haiku
  - Gemini: gemini-2.0-flash (default), gemini-1.5-pro, gemini-1.5-flash
- Save / Clear buttons

**Step 4: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
feat: add AI provider plugin config UI in Settings
```

---

### Task 11: Chat ViewModel

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/chat/ChatViewModel.kt`

**Step 1: Create the chat ViewModel**

```kotlin
package com.parachord.android.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatService
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.AiProviderInfo
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.providers.ChatGptProvider
import com.parachord.android.ai.providers.ClaudeProvider
import com.parachord.android.ai.providers.GeminiProvider
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatService: AiChatService,
    private val settingsStore: SettingsStore,
    private val chatGptProvider: ChatGptProvider,
    private val claudeProvider: ClaudeProvider,
    private val geminiProvider: GeminiProvider,
) : ViewModel() {

    private val providers = listOf(chatGptProvider, claudeProvider, geminiProvider)

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progressText = MutableStateFlow<String?>(null)
    val progressText: StateFlow<String?> = _progressText.asStateFlow()

    /** Available providers with their configured status. */
    val availableProviders: StateFlow<List<AiProviderInfo>> = combine(
        settingsStore.getAiProviderApiKeyFlow("chatgpt"),
        settingsStore.getAiProviderApiKeyFlow("claude"),
        settingsStore.getAiProviderApiKeyFlow("gemini"),
    ) { chatGptKey, claudeKey, geminiKey ->
        listOf(
            AiProviderInfo("chatgpt", "ChatGPT", chatGptKey != null, ""),
            AiProviderInfo("claude", "Claude", claudeKey != null, ""),
            AiProviderInfo("gemini", "Google Gemini", geminiKey != null, ""),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Auto-select first configured provider
        viewModelScope.launch {
            availableProviders.collect { providers ->
                if (_selectedProviderId.value == null) {
                    val first = providers.firstOrNull { it.isConfigured }
                    first?.let { selectProvider(it.id) }
                }
            }
        }
    }

    fun selectProvider(providerId: String) {
        _selectedProviderId.value = providerId
        _messages.value = chatService.getDisplayMessages(providerId)
    }

    fun sendMessage(text: String) {
        val providerId = _selectedProviderId.value ?: return
        val provider = providers.find { it.id == providerId } ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _progressText.value = null

            // Add user message to display immediately
            _messages.value = _messages.value + ChatMessage(role = ChatRole.USER, content = text)

            val config = AiProviderConfig(
                apiKey = settingsStore.getAiProviderApiKey(providerId) ?: "",
                model = settingsStore.getAiProviderModel(providerId),
            )

            chatService.sendMessage(
                provider = provider,
                config = config,
                userMessage = text,
                onProgress = { _progressText.value = it },
            )

            // Refresh display messages from service (includes the assistant response)
            _messages.value = chatService.getDisplayMessages(providerId)
            _isLoading.value = false
            _progressText.value = null
        }
    }

    fun clearChat() {
        val providerId = _selectedProviderId.value ?: return
        chatService.clearHistory(providerId)
        _messages.value = emptyList()
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add ChatViewModel with provider selection and message handling
```

---

### Task 12: Chat Screen UI

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/chat/ChatScreen.kt`

**Step 1: Create the full-screen chat composable**

Build the ChatScreen with:
- Top bar: back arrow, "Shuffleupagus" title with mammoth icon, provider dropdown (right side)
- Empty state: mammoth icon + welcome text when no messages
- Message list (LazyColumn): user bubbles right-aligned purple, assistant bubbles left-aligned surface color
- Loading indicator: three bouncing dots animation + progress text
- Input area: OutlinedTextField with "Ask your DJ..." placeholder, circular purple send button
- "No providers configured" state with prompt to go to Settings

Colors: user messages use `MaterialTheme.colorScheme.primary` background, assistant messages use `MaterialTheme.colorScheme.surfaceVariant`.

Auto-scroll to bottom on new messages using `LaunchedEffect(messages.size)`.

**Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
feat: add Shuffleupagus chat screen UI
```

---

### Task 13: Navigation & Wiring

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/navigation/Navigation.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/MainActivity.kt`

**Step 1: Add chat route to Navigation.kt**

In `Routes` object:
```kotlin
const val CHAT = "chat"
```

In `ParachordNavHost`, add composable:
```kotlin
composable(
    Routes.CHAT,
    enterTransition = { slideInVertically(initialOffsetY = { it }) + fadeIn() },
    exitTransition = { slideOutVertically(targetOffsetY = { it }) + fadeOut() },
) {
    ChatScreen(onBack = { navController.popBackStack() })
}
```

Add `Routes.CHAT` to `fullScreenRoutes` in `MainActivity.kt`.

**Step 2: Wire the ActionOverlay callback in MainActivity.kt**

Change `onChatWithShuffleupagus`:
```kotlin
onChatWithShuffleupagus = {
    showActionOverlay = false
    navController.navigate(Routes.CHAT) {
        launchSingleTop = true
    }
},
```

**Step 3: Verify it compiles**

Run: `./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
feat: wire Shuffleupagus chat to navigation and action overlay
```

---

### Task 14: Build & Smoke Test

**Step 1: Full build**

Run: `./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

**Step 2: Verify no lint issues**

Run: `./gradlew lintDebug 2>&1 | tail -10`
Expected: No errors (warnings OK)

**Step 3: Final commit with all fixes if needed**

```
chore: fix any build issues from AI chat integration
```

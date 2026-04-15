package com.parachord.shared.ai.tools

import com.parachord.shared.ai.DjToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Definitions for the 8 DJ tools that the AI can invoke.
 * Mirrors the desktop app's dj-tools.js tool schemas.
 */
object DjToolDefinitions {

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
            putJsonArray("required") {
                add(JsonPrimitive("artist"))
                add(JsonPrimitive("title"))
            }
        }
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
            putJsonArray("required") {
                add(JsonPrimitive("action"))
            }
        }
    )

    val search = DjToolDefinition(
        name = "search",
        description = "Search for tracks across all music sources. Returns a list of matching tracks. IMPORTANT: Always search for specific 'artist name song title' queries — never search for genre names like 'indie rock' or 'jazz' as this returns irrelevant compilation tracks.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query — use specific artist and track names (e.g. 'Radiohead Creep'), NOT genre names")
                }
                putJsonObject("limit") {
                    put("type", "number")
                    put("description", "Maximum number of results to return (default 10)")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
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
                            putJsonObject("artist") {
                                put("type", "string")
                            }
                            putJsonObject("title") {
                                put("type", "string")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("artist"))
                            add(JsonPrimitive("title"))
                        }
                    }
                    put("description", "Tracks to add to the queue")
                }
                putJsonObject("position") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("next"))
                        add(JsonPrimitive("last"))
                    }
                    put("description", "Add after current track (next) or at end of queue (last). Default: last")
                }
                putJsonObject("playFirst") {
                    put("type", "boolean")
                    put("description", "If true (default), clears the queue, plays the first track immediately, and queues the rest. If false, adds tracks to existing queue without clearing or starting playback.")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("tracks"))
            }
        }
    )

    val queueClear = DjToolDefinition(
        name = "queue_clear",
        description = "Clear all tracks from the playback queue",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            putJsonArray("required") {}
        }
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
                            putJsonObject("artist") {
                                put("type", "string")
                            }
                            putJsonObject("title") {
                                put("type", "string")
                            }
                        }
                        putJsonArray("required") {
                            add(JsonPrimitive("artist"))
                            add(JsonPrimitive("title"))
                        }
                    }
                    put("description", "Tracks to include in the playlist")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("name"))
                add(JsonPrimitive("tracks"))
            }
        }
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
            putJsonArray("required") {
                add(JsonPrimitive("enabled"))
            }
        }
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
            putJsonArray("required") {
                add(JsonPrimitive("type"))
            }
        }
    )

    /** Returns all 8 DJ tool definitions. */
    fun all(): List<DjToolDefinition> = listOf(
        play,
        control,
        search,
        queueAdd,
        queueClear,
        createPlaylist,
        shuffle,
        blockRecommendation,
    )
}

package com.parachord.shared.ai.tools

/**
 * Dispatches DJ tool calls (`play`, `control`, `search`, `queue_add`,
 * `queue_clear`, `create_playlist`, `shuffle`, `block_recommendation`)
 * coming back from an AI provider's tool-call response.
 *
 * Implementations are platform-specific because tool dispatch reaches into
 * the playback stack (`PlaybackController` / `PlaybackStateHolder` on
 * Android, `ApplicationMusicPlayer` / `SPTAppRemote` / AVPlayer on iOS),
 * the resolver pipeline, and the library/settings stores. The shared
 * [com.parachord.shared.ai.AiChatService] holds a reference to this
 * interface so the chat orchestration loop is platform-independent — only
 * the concrete dispatch is per-platform.
 *
 * Tool schemas (which arguments each tool accepts and what fields the
 * result map carries) are defined in
 * [com.parachord.shared.ai.tools.DjToolDefinitions].
 */
interface DjToolExecutor {
    /**
     * Execute a DJ tool by [name] with JSON-decoded [args]. Returns a map
     * representing the tool's result; the caller encodes it as JSON for
     * the AI provider's tool-result message.
     *
     * Implementations should NEVER throw — bubble errors back through the
     * result map (e.g. `mapOf("error" to "...")`) so the AI can see the
     * failure and recover or apologize. A thrown exception aborts the
     * entire tool loop and returns a generic error to the user.
     */
    suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?>
}

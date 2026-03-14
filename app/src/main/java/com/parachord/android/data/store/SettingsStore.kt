package com.parachord.android.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences store replacing electron-store from the desktop app.
 * Uses Jetpack DataStore for type-safe, async key-value storage.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SCROBBLING_ENABLED = booleanPreferencesKey("scrobbling_enabled")
        val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        val SPOTIFY_ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        val SPOTIFY_REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        val SOUNDCLOUD_ACCESS_TOKEN = stringPreferencesKey("soundcloud_access_token")
        val SOUNDCLOUD_REFRESH_TOKEN = stringPreferencesKey("soundcloud_refresh_token")
        val SOUNDCLOUD_CLIENT_ID = stringPreferencesKey("soundcloud_client_id")
        val SOUNDCLOUD_CLIENT_SECRET = stringPreferencesKey("soundcloud_client_secret")
        val RESOLVER_ORDER = stringPreferencesKey("resolver_order")
        val ACTIVE_RESOLVERS = stringPreferencesKey("active_resolvers")
        val RESOLVER_VOLUME_OFFSETS = stringPreferencesKey("resolver_volume_offsets")
        val PERSIST_QUEUE = booleanPreferencesKey("persist_queue")
        val PERSISTED_QUEUE_STATE = stringPreferencesKey("persisted_queue_state")
        val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        val LIBREFM_SESSION_KEY = stringPreferencesKey("librefm_session_key")
        val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")
        val LISTENBRAINZ_USERNAME = stringPreferencesKey("listenbrainz_username")
        val DISCOGS_TOKEN = stringPreferencesKey("discogs_personal_token")
        val DISABLED_META_PROVIDERS = stringPreferencesKey("disabled_meta_providers")
        val BLOCKED_RECOMMENDATIONS = stringPreferencesKey("blocked_recommendations")
        val CHATGPT_API_KEY = stringPreferencesKey("chatgpt_api_key")
        val CHATGPT_MODEL = stringPreferencesKey("chatgpt_model")
        val CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_PROVIDER = stringPreferencesKey("sync_provider")
        val SYNC_TRACKS = booleanPreferencesKey("sync_tracks")
        val SYNC_ALBUMS = booleanPreferencesKey("sync_albums")
        val SYNC_ARTISTS = booleanPreferencesKey("sync_artists")
        val SYNC_PLAYLISTS = booleanPreferencesKey("sync_playlists")
        val SYNC_SELECTED_PLAYLIST_IDS = stringPreferencesKey("sync_selected_playlist_ids")
        val SYNC_LAST_COMPLETED_AT = stringPreferencesKey("sync_last_completed_at")
        val SYNC_PUSH_LOCAL_PLAYLISTS = booleanPreferencesKey("sync_push_local_playlists")
        val SYNC_DATA_VERSION = stringPreferencesKey("sync_data_version")
        val DELETED_FRIEND_KEYS = stringSetPreferencesKey("deleted_friend_keys")
        val SORT_ARTISTS = stringPreferencesKey("sort_artists")
        val SORT_ALBUMS = stringPreferencesKey("sort_albums")
        val SORT_TRACKS = stringPreferencesKey("sort_tracks")
        val SORT_FRIENDS = stringPreferencesKey("sort_friends")
        val SORT_PLAYLISTS = stringPreferencesKey("sort_playlists")

        /** Default canonical order matching the desktop app. */
        private const val DEFAULT_RESOLVER_ORDER = "spotify,applemusic,bandcamp,soundcloud,localfiles,youtube"
    }

    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val scrobblingEnabled: Flow<Boolean> = dataStore.data.map { it[SCROBBLING_ENABLED] ?: false }
    val persistQueue: Flow<Boolean> = dataStore.data.map { it[PERSIST_QUEUE] ?: false }

    // ── Sort preferences ─────────────────────────────────────────

    suspend fun getSortArtists(): String? = dataStore.data.first()[SORT_ARTISTS]
    suspend fun getSortAlbums(): String? = dataStore.data.first()[SORT_ALBUMS]
    suspend fun getSortTracks(): String? = dataStore.data.first()[SORT_TRACKS]
    suspend fun getSortFriends(): String? = dataStore.data.first()[SORT_FRIENDS]

    suspend fun setSortArtists(sort: String) { dataStore.edit { it[SORT_ARTISTS] = sort } }
    suspend fun setSortAlbums(sort: String) { dataStore.edit { it[SORT_ALBUMS] = sort } }
    suspend fun setSortTracks(sort: String) { dataStore.edit { it[SORT_TRACKS] = sort } }
    suspend fun setSortFriends(sort: String) { dataStore.edit { it[SORT_FRIENDS] = sort } }
    suspend fun getSortPlaylists(): String? = dataStore.data.first()[SORT_PLAYLISTS]
    suspend fun setSortPlaylists(sort: String) { dataStore.edit { it[SORT_PLAYLISTS] = sort } }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setScrobblingEnabled(enabled: Boolean) {
        dataStore.edit { it[SCROBBLING_ENABLED] = enabled }
    }

    suspend fun setSpotifyTokens(accessToken: String, refreshToken: String) {
        dataStore.edit {
            it[SPOTIFY_ACCESS_TOKEN] = accessToken
            it[SPOTIFY_REFRESH_TOKEN] = refreshToken
        }
    }

    fun getSpotifyAccessTokenFlow(): Flow<String?> =
        dataStore.data.map { it[SPOTIFY_ACCESS_TOKEN] }

    fun getLastFmSessionKeyFlow(): Flow<String?> =
        dataStore.data.map { it[LASTFM_SESSION_KEY] }

    suspend fun getLastFmSessionKey(): String? =
        dataStore.data.first()[LASTFM_SESSION_KEY]

    suspend fun clearSpotifyTokens() {
        dataStore.edit {
            it.remove(SPOTIFY_ACCESS_TOKEN)
            it.remove(SPOTIFY_REFRESH_TOKEN)
        }
    }

    suspend fun setLastFmSession(sessionKey: String) {
        dataStore.edit { it[LASTFM_SESSION_KEY] = sessionKey }
    }

    suspend fun clearLastFmSession() {
        dataStore.edit {
            it.remove(LASTFM_SESSION_KEY)
            it.remove(LASTFM_USERNAME)
        }
    }

    // --- Last.fm username ---

    suspend fun getLastFmUsername(): String? =
        dataStore.data.first()[LASTFM_USERNAME]

    fun getLastFmUsernameFlow(): Flow<String?> =
        dataStore.data.map { it[LASTFM_USERNAME] }

    suspend fun setLastFmUsername(username: String) {
        dataStore.edit { it[LASTFM_USERNAME] = username }
    }

    // --- ListenBrainz username ---

    suspend fun getListenBrainzUsername(): String? =
        dataStore.data.first()[LISTENBRAINZ_USERNAME]?.ifBlank { null }

    fun getListenBrainzUsernameFlow(): Flow<String?> =
        dataStore.data.map { it[LISTENBRAINZ_USERNAME]?.ifBlank { null } }

    suspend fun setListenBrainzUsername(username: String) {
        dataStore.edit { it[LISTENBRAINZ_USERNAME] = username }
    }

    suspend fun clearListenBrainzUsername() {
        dataStore.edit { it.remove(LISTENBRAINZ_USERNAME) }
    }

    suspend fun setSoundCloudToken(token: String) {
        dataStore.edit { it[SOUNDCLOUD_ACCESS_TOKEN] = token }
    }

    suspend fun setSoundCloudTokens(accessToken: String, refreshToken: String) {
        dataStore.edit {
            it[SOUNDCLOUD_ACCESS_TOKEN] = accessToken
            it[SOUNDCLOUD_REFRESH_TOKEN] = refreshToken
        }
    }

    fun getSoundCloudTokenFlow(): Flow<String?> =
        dataStore.data.map { it[SOUNDCLOUD_ACCESS_TOKEN] }

    suspend fun getSoundCloudToken(): String? =
        dataStore.data.first()[SOUNDCLOUD_ACCESS_TOKEN]

    suspend fun getSoundCloudRefreshToken(): String? =
        dataStore.data.first()[SOUNDCLOUD_REFRESH_TOKEN]

    suspend fun clearSoundCloudToken() {
        dataStore.edit {
            it.remove(SOUNDCLOUD_ACCESS_TOKEN)
            it.remove(SOUNDCLOUD_REFRESH_TOKEN)
        }
    }

    // --- SoundCloud client credentials (user-provided BYOK) ---

    suspend fun setSoundCloudCredentials(clientId: String, clientSecret: String) {
        dataStore.edit {
            it[SOUNDCLOUD_CLIENT_ID] = clientId
            it[SOUNDCLOUD_CLIENT_SECRET] = clientSecret
        }
    }

    suspend fun getSoundCloudClientId(): String? =
        dataStore.data.first()[SOUNDCLOUD_CLIENT_ID]

    suspend fun getSoundCloudClientSecret(): String? =
        dataStore.data.first()[SOUNDCLOUD_CLIENT_SECRET]

    fun getSoundCloudClientIdFlow(): Flow<String?> =
        dataStore.data.map { it[SOUNDCLOUD_CLIENT_ID] }

    suspend fun clearSoundCloudCredentials() {
        dataStore.edit {
            it.remove(SOUNDCLOUD_CLIENT_ID)
            it.remove(SOUNDCLOUD_CLIENT_SECRET)
            it.remove(SOUNDCLOUD_ACCESS_TOKEN)
            it.remove(SOUNDCLOUD_REFRESH_TOKEN)
        }
    }

    suspend fun getSpotifyAccessToken(): String? =
        dataStore.data.first()[SPOTIFY_ACCESS_TOKEN]

    suspend fun getSpotifyRefreshToken(): String? =
        dataStore.data.first()[SPOTIFY_REFRESH_TOKEN]

    // --- Resolver order and active resolvers ---

    /**
     * Get the user's resolver priority order.
     * Stored as comma-separated IDs, matching the desktop's resolverOrder array.
     */
    suspend fun getResolverOrder(): List<String> {
        val raw = dataStore.data.first()[RESOLVER_ORDER] ?: DEFAULT_RESOLVER_ORDER
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getResolverOrderFlow(): Flow<List<String>> =
        dataStore.data.map { prefs ->
            (prefs[RESOLVER_ORDER] ?: DEFAULT_RESOLVER_ORDER)
                .split(",").filter { it.isNotBlank() }
        }

    suspend fun setResolverOrder(order: List<String>) {
        dataStore.edit { it[RESOLVER_ORDER] = order.joinToString(",") }
    }

    /**
     * Get the list of active (enabled) resolver IDs.
     * Empty list means all resolvers are active (no filtering).
     */
    suspend fun getActiveResolvers(): List<String> {
        val raw = dataStore.data.first()[ACTIVE_RESOLVERS] ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getActiveResolversFlow(): Flow<List<String>> =
        dataStore.data.map { prefs ->
            val raw = prefs[ACTIVE_RESOLVERS] ?: return@map emptyList()
            raw.split(",").filter { it.isNotBlank() }
        }

    suspend fun setActiveResolvers(resolvers: List<String>) {
        dataStore.edit { it[ACTIVE_RESOLVERS] = resolvers.joinToString(",") }
    }

    // --- Per-resolver volume offsets (dB) ---

    /**
     * Per-resolver volume offsets matching the desktop's resolverVolumeOffsets.
     * Stored as "resolverId:offset,resolverId:offset" pairs.
     * Default offsets: bandcamp=-3, youtube=-6, others=0.
     */
    suspend fun getResolverVolumeOffsets(): Map<String, Int> {
        val raw = dataStore.data.first()[RESOLVER_VOLUME_OFFSETS] ?: return defaultVolumeOffsets()
        return raw.split(",").associate { entry ->
            val parts = entry.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }

    suspend fun setResolverVolumeOffsets(offsets: Map<String, Int>) {
        dataStore.edit {
            it[RESOLVER_VOLUME_OFFSETS] = offsets.entries.joinToString(",") { (k, v) -> "$k:$v" }
        }
    }

    // --- Queue persistence ---

    suspend fun setPersistQueue(enabled: Boolean) {
        dataStore.edit { it[PERSIST_QUEUE] = enabled }
    }

    suspend fun isPersistQueueEnabled(): Boolean =
        dataStore.data.first()[PERSIST_QUEUE] ?: false

    suspend fun getPersistedQueueState(): String? =
        dataStore.data.first()[PERSISTED_QUEUE_STATE]

    suspend fun setPersistedQueueState(json: String) {
        dataStore.edit { it[PERSISTED_QUEUE_STATE] = json }
    }

    suspend fun clearPersistedQueueState() {
        dataStore.edit { it.remove(PERSISTED_QUEUE_STATE) }
    }

    // --- ListenBrainz ---

    suspend fun getListenBrainzToken(): String? =
        dataStore.data.first()[LISTENBRAINZ_TOKEN]

    fun getListenBrainzTokenFlow(): Flow<String?> =
        dataStore.data.map { it[LISTENBRAINZ_TOKEN] }

    suspend fun setListenBrainzToken(token: String) {
        dataStore.edit { it[LISTENBRAINZ_TOKEN] = token }
    }

    suspend fun clearListenBrainzToken() {
        dataStore.edit { it.remove(LISTENBRAINZ_TOKEN) }
    }

    // --- Libre.fm ---

    suspend fun getLibreFmSessionKey(): String? =
        dataStore.data.first()[LIBREFM_SESSION_KEY]

    fun getLibreFmSessionKeyFlow(): Flow<String?> =
        dataStore.data.map { it[LIBREFM_SESSION_KEY] }

    suspend fun setLibreFmSession(sessionKey: String) {
        dataStore.edit { it[LIBREFM_SESSION_KEY] = sessionKey }
    }

    suspend fun clearLibreFmSession() {
        dataStore.edit { it.remove(LIBREFM_SESSION_KEY) }
    }

    // --- Discogs ---

    suspend fun getDiscogsToken(): String? =
        dataStore.data.first()[DISCOGS_TOKEN]?.ifBlank { null }

    suspend fun setDiscogsToken(token: String) {
        dataStore.edit { it[DISCOGS_TOKEN] = token }
    }

    suspend fun clearDiscogsToken() {
        dataStore.edit { it.remove(DISCOGS_TOKEN) }
    }

    // --- Metadata provider enable/disable ---

    /**
     * Get the set of disabled metadata provider names (e.g. "discogs", "wikipedia").
     * Empty set means all providers are enabled.
     */
    suspend fun getDisabledMetaProviders(): Set<String> {
        val raw = dataStore.data.first()[DISABLED_META_PROVIDERS] ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun getDisabledMetaProvidersFlow(): Flow<Set<String>> =
        dataStore.data.map { prefs ->
            val raw = prefs[DISABLED_META_PROVIDERS] ?: return@map emptySet()
            raw.split(",").filter { it.isNotBlank() }.toSet()
        }

    suspend fun setDisabledMetaProviders(disabled: Set<String>) {
        dataStore.edit { it[DISABLED_META_PROVIDERS] = disabled.joinToString(",") }
    }

    suspend fun setMetaProviderEnabled(providerName: String, enabled: Boolean) {
        val current = getDisabledMetaProviders().toMutableSet()
        if (enabled) current.remove(providerName) else current.add(providerName)
        setDisabledMetaProviders(current)
    }

    // --- Recommendation Blocklist ---

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

    fun getAiProviderModelFlow(providerId: String): Flow<String> {
        val key = when (providerId) {
            "chatgpt" -> CHATGPT_MODEL
            "claude" -> CLAUDE_MODEL
            "gemini" -> GEMINI_MODEL
            else -> return kotlinx.coroutines.flow.flowOf("")
        }
        return dataStore.data.map { it[key] ?: "" }
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

    // --- Sync Settings ---

    data class SyncSettings(
        val enabled: Boolean = false,
        val provider: String = "spotify",
        val syncTracks: Boolean = true,
        val syncAlbums: Boolean = true,
        val syncArtists: Boolean = true,
        val syncPlaylists: Boolean = true,
        val selectedPlaylistIds: Set<String> = emptySet(),
        val pushLocalPlaylists: Boolean = true,
    )

    val syncEnabledFlow: Flow<Boolean> = dataStore.data.map { it[SYNC_ENABLED] ?: false }

    val lastSyncAtFlow: Flow<Long> = dataStore.data.map {
        it[SYNC_LAST_COMPLETED_AT]?.toLongOrNull() ?: 0L
    }

    fun getSyncSettingsFlow(): Flow<SyncSettings> = dataStore.data.map { prefs ->
        SyncSettings(
            enabled = prefs[SYNC_ENABLED] ?: false,
            provider = prefs[SYNC_PROVIDER] ?: "spotify",
            syncTracks = prefs[SYNC_TRACKS] ?: true,
            syncAlbums = prefs[SYNC_ALBUMS] ?: true,
            syncArtists = prefs[SYNC_ARTISTS] ?: true,
            syncPlaylists = prefs[SYNC_PLAYLISTS] ?: true,
            selectedPlaylistIds = (prefs[SYNC_SELECTED_PLAYLIST_IDS] ?: "")
                .split(",").filter { it.isNotBlank() }.toSet(),
            pushLocalPlaylists = prefs[SYNC_PUSH_LOCAL_PLAYLISTS] ?: true,
        )
    }

    suspend fun getSyncSettings(): SyncSettings {
        val prefs = dataStore.data.first()
        return SyncSettings(
            enabled = prefs[SYNC_ENABLED] ?: false,
            provider = prefs[SYNC_PROVIDER] ?: "spotify",
            syncTracks = prefs[SYNC_TRACKS] ?: true,
            syncAlbums = prefs[SYNC_ALBUMS] ?: true,
            syncArtists = prefs[SYNC_ARTISTS] ?: true,
            syncPlaylists = prefs[SYNC_PLAYLISTS] ?: true,
            selectedPlaylistIds = (prefs[SYNC_SELECTED_PLAYLIST_IDS] ?: "")
                .split(",").filter { it.isNotBlank() }.toSet(),
            pushLocalPlaylists = prefs[SYNC_PUSH_LOCAL_PLAYLISTS] ?: true,
        )
    }

    suspend fun saveSyncSettings(settings: SyncSettings) {
        dataStore.edit { prefs ->
            prefs[SYNC_ENABLED] = settings.enabled
            prefs[SYNC_PROVIDER] = settings.provider
            prefs[SYNC_TRACKS] = settings.syncTracks
            prefs[SYNC_ALBUMS] = settings.syncAlbums
            prefs[SYNC_ARTISTS] = settings.syncArtists
            prefs[SYNC_PLAYLISTS] = settings.syncPlaylists
            prefs[SYNC_SELECTED_PLAYLIST_IDS] = settings.selectedPlaylistIds.joinToString(",")
            prefs[SYNC_PUSH_LOCAL_PLAYLISTS] = settings.pushLocalPlaylists
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[SYNC_ENABLED] = enabled }
    }

    suspend fun setLastSyncAt(timestamp: Long) {
        dataStore.edit { it[SYNC_LAST_COMPLETED_AT] = timestamp.toString() }
    }

    suspend fun getSyncDataVersion(): Int =
        dataStore.data.first()[SYNC_DATA_VERSION]?.toIntOrNull() ?: 0

    suspend fun setSyncDataVersion(version: Int) {
        dataStore.edit { it[SYNC_DATA_VERSION] = version.toString() }
    }

    suspend fun clearSyncSettings() {
        dataStore.edit { prefs ->
            prefs.remove(SYNC_ENABLED)
            prefs.remove(SYNC_PROVIDER)
            prefs.remove(SYNC_TRACKS)
            prefs.remove(SYNC_ALBUMS)
            prefs.remove(SYNC_ARTISTS)
            prefs.remove(SYNC_PLAYLISTS)
            prefs.remove(SYNC_SELECTED_PLAYLIST_IDS)
            prefs.remove(SYNC_LAST_COMPLETED_AT)
            prefs.remove(SYNC_PUSH_LOCAL_PLAYLISTS)
        }
    }

    // --- Deleted Friends (prevent re-sync) ---

    /** Get the set of friend keys (service:username) the user has explicitly deleted. */
    suspend fun getDeletedFriendKeys(): Set<String> =
        dataStore.data.first()[DELETED_FRIEND_KEYS] ?: emptySet()

    /** Mark a friend as deleted so sync won't re-add them. */
    suspend fun addDeletedFriendKey(key: String) {
        dataStore.edit {
            val current = it[DELETED_FRIEND_KEYS] ?: emptySet()
            it[DELETED_FRIEND_KEYS] = current + key
        }
    }

    /** Remove a friend from the deleted list (e.g. when re-adding them). */
    suspend fun removeDeletedFriendKey(key: String) {
        dataStore.edit {
            val current = it[DELETED_FRIEND_KEYS] ?: emptySet()
            it[DELETED_FRIEND_KEYS] = current - key
        }
    }

    private fun defaultVolumeOffsets(): Map<String, Int> = mapOf(
        "spotify" to 0,
        "applemusic" to 0,
        "localfiles" to 0,
        "soundcloud" to 0,
        "bandcamp" to -3,
        "youtube" to -6,
    )
}

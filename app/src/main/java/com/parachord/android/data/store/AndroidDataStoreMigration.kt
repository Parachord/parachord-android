package com.parachord.android.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.parachord.shared.settings.SettingsStore as SharedSettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SettingsMigration
import kotlinx.coroutines.flow.first

/**
 * One-shot Phase 9B Stage 3 migration: copy every recognized preference
 * out of the legacy Jetpack DataStore (`parachord_settings`) into
 * [KvStore] (`parachord_kmp_prefs`) on first launch under the new
 * shared [SharedSettingsStore]. Idempotent — `SettingsStore` only invokes
 * this when the `_migration_v1` marker key is missing from KvStore.
 *
 * Stage 2 already moved the sync key family with a narrower
 * `_migration_sync_v1` marker. Stage 3 supersedes that with the unified
 * marker; sync keys are re-copied here for installs that haven't run
 * Stage 2 yet (so any one of: fresh install, Stage 2 install, or fully
 * migrated install converges on the same KvStore-resident state). Re-
 * copying keys that are already in KvStore is a no-op write — same
 * value in, same value out.
 *
 * Per-provider `sync_collections_<id>` keys and any
 * `ai_<provider>_api_key` keys are walked from the DataStore key set
 * rather than enumerated, since the provider-id universe is open-ended.
 *
 * Note: `ai_<provider>_api_key` values were already in EncryptedSharedPreferences
 * (SecureTokenStore), not DataStore — they don't need migration.
 */
class AndroidDataStoreMigration(
    private val dataStore: DataStore<Preferences>,
) : SettingsMigration {

    override suspend fun migrate(kv: KvStore) {
        val prefs = dataStore.data.first()

        // Booleans
        prefs[booleanPreferencesKey(SharedSettingsStore.SCROBBLING_ENABLED)]?.let {
            kv.setBoolean(SharedSettingsStore.SCROBBLING_ENABLED, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.PERSIST_QUEUE)]?.let {
            kv.setBoolean(SharedSettingsStore.PERSIST_QUEUE, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SEND_LISTENING_HISTORY)]?.let {
            kv.setBoolean(SharedSettingsStore.SEND_LISTENING_HISTORY, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_ENABLED)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_ENABLED, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_TRACKS)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_TRACKS, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_ALBUMS)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_ALBUMS, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_ARTISTS)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_ARTISTS, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_PLAYLISTS)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_PLAYLISTS, it)
        }
        prefs[booleanPreferencesKey(SharedSettingsStore.SYNC_PUSH_LOCAL_PLAYLISTS)]?.let {
            kv.setBoolean(SharedSettingsStore.SYNC_PUSH_LOCAL_PLAYLISTS, it)
        }

        // Strings
        for (keyName in STRING_KEYS) {
            prefs[stringPreferencesKey(keyName)]?.let { kv.setString(keyName, it) }
        }

        // Long
        prefs[longPreferencesKey(SharedSettingsStore.LAST_PLUGIN_SYNC)]?.let {
            kv.setLong(SharedSettingsStore.LAST_PLUGIN_SYNC, it)
        }

        // String set: deleted_friend_keys was stringSetPreferencesKey in DataStore;
        // KvStore stores via CSV.
        prefs[stringSetPreferencesKey(SharedSettingsStore.DELETED_FRIEND_KEYS)]?.let { set ->
            if (set.isNotEmpty()) {
                kv.setStringSetCsv(SharedSettingsStore.DELETED_FRIEND_KEYS, set)
            }
        }

        // Open-ended per-provider sync_collections_* keys
        for ((key, value) in prefs.asMap()) {
            if (key.name.startsWith("sync_collections_") && value is String) {
                kv.setString(key.name, value)
            }
        }
    }

    private companion object {
        /** All known string-typed DataStore keys to migrate. */
        val STRING_KEYS: List<String> = listOf(
            SharedSettingsStore.THEME_MODE,
            SharedSettingsStore.PERSISTED_QUEUE_STATE,
            SharedSettingsStore.LASTFM_USERNAME,
            SharedSettingsStore.LISTENBRAINZ_USERNAME,
            SharedSettingsStore.RESOLVER_ORDER,
            SharedSettingsStore.ACTIVE_RESOLVERS,
            SharedSettingsStore.RESOLVER_VOLUME_OFFSETS,
            SharedSettingsStore.DISABLED_META_PROVIDERS,
            SharedSettingsStore.BLOCKED_RECOMMENDATIONS,
            SharedSettingsStore.CHATGPT_MODEL,
            SharedSettingsStore.CLAUDE_MODEL,
            SharedSettingsStore.GEMINI_MODEL,
            SharedSettingsStore.SELECTED_CHAT_PROVIDER,
            SharedSettingsStore.APPLE_MUSIC_STOREFRONT,
            SharedSettingsStore.PREFERRED_SPOTIFY_DEVICE_ID,
            SharedSettingsStore.SORT_ARTISTS,
            SharedSettingsStore.SORT_ALBUMS,
            SharedSettingsStore.SORT_TRACKS,
            SharedSettingsStore.SORT_FRIENDS,
            SharedSettingsStore.SORT_PLAYLISTS,
            SharedSettingsStore.CONCERT_LATITUDE,
            SharedSettingsStore.CONCERT_LONGITUDE,
            SharedSettingsStore.CONCERT_CITY,
            SharedSettingsStore.CONCERT_RADIUS,
            SharedSettingsStore.DISABLED_PLUGINS,
            SharedSettingsStore.SYNC_PROVIDER,
            SharedSettingsStore.SYNC_SELECTED_PLAYLIST_IDS,
            SharedSettingsStore.SYNC_LAST_COMPLETED_AT,
            SharedSettingsStore.SYNC_DATA_VERSION,
            SharedSettingsStore.ENABLED_SYNC_PROVIDERS,
        )
    }
}

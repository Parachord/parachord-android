package com.parachord.android.plugin

import android.content.Context
import android.util.Log
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginSync"

/**
 * Syncs .axe plugins from the parachord-plugins GitHub repository.
 *
 * Mirrors the desktop's `plugins-sync-marketplace` flow:
 * 1. Fetch manifest.json from GitHub (list of available plugins + versions)
 * 2. Compare each plugin's remote version against locally loaded version
 * 3. Download new/updated .axe files to filesDir/plugins/
 * 4. Call [PluginManager.reloadPlugins] to hot-load new versions
 *
 * Runs on app start (debounced to once per 24h) and on manual trigger.
 */
@Singleton
class PluginSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val pluginManager: PluginManager,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/Parachord/parachord-plugins/main/manifest.json"
        private const val PLUGINS_BASE_URL =
            "https://raw.githubusercontent.com/Parachord/parachord-plugins/main/"
        /** Minimum interval between automatic syncs (24 hours). */
        private const val SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    data class SyncResult(
        val added: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
        val unchanged: Int = 0,
    ) {
        val hasChanges get() = added.isNotEmpty() || updated.isNotEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sync plugins from the marketplace if enough time has passed since last sync.
     * Returns null if sync was skipped (too recent).
     */
    suspend fun syncIfNeeded(): SyncResult? {
        val lastSync = settingsStore.getLastPluginSyncTimestamp()
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL_MS) {
            Log.d(TAG, "Skipping sync — last sync was ${(now - lastSync) / 1000 / 60}min ago")
            return null
        }
        return sync()
    }

    /** Force sync regardless of last sync time. */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting plugin sync from marketplace...")

        val manifest = try {
            fetchManifest()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch plugin manifest: ${e.message}")
            return@withContext SyncResult(failed = listOf("manifest"))
        }

        val localPlugins = pluginManager.plugins.value.associateBy { it.id }
        val cacheDir = pluginManager.pluginsCacheDir

        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var unchanged = 0

        for (remote in manifest.plugins) {
            val local = localPlugins[remote.id]
            val localVersion = local?.version ?: "0.0.0"
            val remoteVersion = remote.version

            if (compareSemver(remoteVersion, localVersion) <= 0) {
                unchanged++
                continue
            }

            // Download updated plugin
            try {
                val axeContent = fetchPlugin(remote.id)
                val file = File(cacheDir, "${remote.id}.axe")
                file.writeText(axeContent)

                if (local == null) {
                    added.add(remote.id)
                    Log.d(TAG, "Downloaded new plugin: ${remote.id} v${remoteVersion}")
                } else {
                    updated.add(remote.id)
                    Log.d(TAG, "Updated plugin: ${remote.id} v${localVersion} → v${remoteVersion}")
                }
            } catch (e: Exception) {
                failed.add(remote.id)
                Log.w(TAG, "Failed to download ${remote.id}: ${e.message}")
            }
        }

        val result = SyncResult(added, updated, failed, unchanged)

        if (result.hasChanges) {
            Log.d(TAG, "Sync complete: ${added.size} added, ${updated.size} updated, ${failed.size} failed, $unchanged unchanged")
            // Hot-reload plugins to pick up new versions
            pluginManager.reloadPlugins()
        } else {
            Log.d(TAG, "Sync complete: all plugins up to date ($unchanged unchanged)")
        }

        // Record sync timestamp
        settingsStore.setLastPluginSyncTimestamp(System.currentTimeMillis())

        result
    }

    // ── Private helpers ──────────────────────────────────────────────

    private fun fetchManifest(): PluginManifest {
        val request = Request.Builder().url(MANIFEST_URL).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Manifest fetch failed: ${response.code}")
        }
        val body = response.body?.string() ?: throw Exception("Empty manifest response")
        return json.decodeFromString(body)
    }

    private fun fetchPlugin(pluginId: String): String {
        val url = "${PLUGINS_BASE_URL}${pluginId}.axe"
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Plugin fetch failed ($pluginId): ${response.code}")
        }
        return response.body?.string() ?: throw Exception("Empty plugin response ($pluginId)")
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }

    // ── Manifest model ───────────────────────────────────────────────

    @Serializable
    private data class PluginManifest(
        val version: String = "1.0.0",
        val plugins: List<PluginEntry> = emptyList(),
    )

    @Serializable
    private data class PluginEntry(
        val id: String,
        val name: String = "",
        val version: String = "0.0.0",
        val type: String = "",
        val description: String = "",
    )
}

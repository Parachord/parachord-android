package com.parachord.android.plugin

import android.content.Context
import android.util.Log
import com.parachord.android.bridge.JsBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginManager"

/**
 * Central wrapper for .axe plugin loading and execution.
 *
 * Reads .axe plugins from:
 * 1. `assets/plugins/` — bundled with the APK (baseline set)
 * 2. `filesDir/plugins/` — downloaded updates from marketplace
 *
 * Deduplicates by semver (higher version wins), loads into the JS
 * ResolverLoader instance, and provides type-safe Kotlin methods for
 * search/resolve/AI/scrobble calls.
 *
 * Depends on [JsRuntime] (not [JsBridge] directly) for KMP portability.
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jsRuntime: JsBridge, // TODO: change to JsRuntime when DI module is updated
) {
    @Serializable
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val type: String = "",
        val icon: String = "",
        val color: String = "",
        val capabilities: Map<String, Boolean> = emptyMap(),
    )

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val initMutex = Mutex()
    private var initialized = false

    private val json = Json { ignoreUnknownKeys = true }

    /** Cached plugins dir for marketplace downloads. */
    val pluginsCacheDir: File by lazy {
        File(context.filesDir, "plugins").also { it.mkdirs() }
    }

    // ── Initialization ───────────────────────────────────────────────

    /** Initialize the plugin system. Idempotent — safe to call multiple times. */
    suspend fun ensureInitialized() {
        initMutex.withLock {
            if (initialized) return@withLock

            // Initialize JS runtime (waits for bootstrap + resolver-loader.js to load)
            jsRuntime.initialize()
            jsRuntime.ready.first { it }

            loadPlugins()
            initialized = true
            Log.d(TAG, "Plugin system initialized with ${_plugins.value.size} plugins")
        }
    }

    /**
     * Hot-reload all plugins. Called after marketplace sync downloads new versions.
     * Unloads all plugins from resolver-loader.js and re-loads from disk.
     */
    suspend fun reloadPlugins() {
        initMutex.withLock {
            if (!initialized) return@withLock
            // Unload all existing plugins
            jsRuntime.evaluate("(async () => { const ids = window.__resolverLoader.getAllResolvers().map(r => r.id); for (const id of ids) await window.__resolverLoader.unloadResolver(id); })()")
            loadPlugins()
            Log.d(TAG, "Plugins reloaded: ${_plugins.value.size} plugins")
        }
    }

    // ── Plugin Loading ───────────────────────────────────────────────

    private suspend fun loadPlugins() {
        val bundled = readBundledPlugins()
        val cached = readCachedPlugins()

        // Deduplicate by semver — higher version wins
        val all = mutableMapOf<String, Pair<String, String>>() // id → (version, axeJson)
        for ((id, version, axeJson) in bundled + cached) {
            val existing = all[id]
            if (existing == null || compareSemver(version, existing.first) > 0) {
                all[id] = version to axeJson
            }
        }

        Log.d(TAG, "Loading ${all.size} plugins (${bundled.size} bundled, ${cached.size} cached)")

        // Load into resolver-loader.js
        val pluginInfos = mutableListOf<PluginInfo>()
        for ((id, pair) in all) {
            val (version, axeJson) = pair
            try {
                // Base64-encode the .axe JSON to avoid escaping issues with
                // backticks, $, quotes, and newlines in the embedded JS code.
                val b64 = android.util.Base64.encodeToString(
                    axeJson.toByteArray(), android.util.Base64.NO_WRAP
                )
                // evaluateJavascript returns the synchronous result of the expression.
                // Async IIFEs return a Promise object ({}) not the resolved value.
                // Use a callback-based approach: store the result in a global var,
                // then read it in a second evaluate() call.
                jsRuntime.evaluate("""
                    window.__lastPluginResult = 'pending';
                    window.__resolverLoader.loadResolver(JSON.parse(atob('$b64')))
                        .then(function() { window.__lastPluginResult = 'ok'; })
                        .catch(function(e) { window.__lastPluginResult = 'error: ' + e.message; });
                """.trimIndent())

                // Give the promise a moment to resolve (loadResolver is fast — just JSON parsing)
                kotlinx.coroutines.delay(50)

                val result = jsRuntime.evaluate("window.__lastPluginResult")
                val cleanResult = result?.removeSurrounding("\"")
                if (cleanResult == "ok") {
                    val info = parsePluginInfo(axeJson)
                    if (info != null) pluginInfos.add(info)
                } else {
                    Log.w(TAG, "Failed to load plugin '$id': $cleanResult")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading plugin '$id': ${e.message}")
            }
        }

        // Filter out plugins that declare mobile: false in capabilities.
        // Omitted mobile field = works on all platforms (backward compatible).
        val platformFiltered = pluginInfos.filter { it.capabilities["mobile"] != false }
        _plugins.value = platformFiltered.sortedBy { it.name }
        if (pluginInfos.size != platformFiltered.size) {
            val hidden = pluginInfos.filter { it.capabilities["mobile"] == false }.map { it.id }
            Log.d(TAG, "Hidden ${hidden.size} plugins not supported on mobile: $hidden")
        }
    }

    private data class PluginEntry(val id: String, val version: String, val axeJson: String)

    private suspend fun readBundledPlugins(): List<PluginEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<PluginEntry>()
        try {
            val files = context.assets.list("plugins") ?: emptyArray()
            for (filename in files) {
                if (!filename.endsWith(".axe")) continue
                try {
                    val axeJson = context.assets.open("plugins/$filename").bufferedReader().readText()
                    val info = parsePluginInfo(axeJson)
                    if (info != null) {
                        entries.add(PluginEntry(info.id, info.version, axeJson))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read bundled plugin '$filename': ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list bundled plugins: ${e.message}")
        }
        entries
    }

    private suspend fun readCachedPlugins(): List<PluginEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<PluginEntry>()
        val dir = pluginsCacheDir
        if (!dir.exists()) return@withContext entries
        for (file in dir.listFiles().orEmpty()) {
            if (!file.name.endsWith(".axe")) continue
            try {
                val axeJson = file.readText()
                val info = parsePluginInfo(axeJson)
                if (info != null) {
                    entries.add(PluginEntry(info.id, info.version, axeJson))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cached plugin '${file.name}': ${e.message}")
            }
        }
        entries
    }

    // ── Raw JS Evaluation ──────────────────────────────────────────────

    /**
     * Evaluate a raw JS script on the plugin runtime.
     * Used by AxeScrobbler and other components that need direct JS access.
     */
    suspend fun evaluateJs(script: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate(script)?.unquote()
    }

    // ── Resolver Calls ───────────────────────────────────────────────

    /**
     * Search for tracks via an .axe resolver plugin.
     * Returns raw JSON string of results array.
     */
    suspend fun search(resolverId: String, query: String): String? {
        ensureInitialized()
        val escaped = escapeForJs(query)
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$resolverId');
                if (!r || !r.search) return JSON.stringify([]);
                const results = await r.search('$escaped', r.config || {});
                return JSON.stringify(results || []);
            })()
        """.trimIndent())?.unquote()
    }

    /**
     * Resolve a specific track via an .axe resolver plugin.
     * Returns raw JSON string of the result object, or null if not found.
     */
    suspend fun resolve(resolverId: String, artist: String, title: String, album: String?): String? {
        ensureInitialized()
        val artistEsc = escapeForJs(artist)
        val titleEsc = escapeForJs(title)
        val albumEsc = if (album != null) "'${escapeForJs(album)}'" else "null"
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$resolverId');
                if (!r || !r.resolve) return null;
                const result = await r.resolve('$artistEsc', '$titleEsc', $albumEsc, r.config || {});
                return result ? JSON.stringify(result) : null;
            })()
        """.trimIndent())?.unquote()
    }

    /**
     * Look up a URL via .axe resolver URL pattern matching.
     * Returns raw JSON string of the result, or null if no resolver handles it.
     */
    suspend fun lookupUrl(url: String): String? {
        ensureInitialized()
        val escaped = escapeForJs(url)
        return jsRuntime.evaluate("""
            (async () => {
                const result = await window.__resolverLoader.lookupUrl('$escaped');
                return result ? JSON.stringify(result) : null;
            })()
        """.trimIndent())?.unquote()
    }

    // ── AI Provider Calls ────────────────────────────────────────────

    /**
     * Call an AI plugin's generate function (playlist generation).
     * Returns raw JSON string of the generated tracks.
     */
    suspend fun aiGenerate(pluginId: String, prompt: String, configJson: String, contextJson: String?): String? {
        ensureInitialized()
        val promptEsc = escapeForJs(prompt)
        val ctxArg = if (contextJson != null) "JSON.parse(`${escapeForJs(contextJson)}`)" else "null"
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.generate) return null;
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.generate('$promptEsc', config, $ctxArg);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    /**
     * Call an AI plugin's chat function (conversational DJ).
     * Returns raw JSON string of the AI response.
     */
    suspend fun aiChat(pluginId: String, messagesJson: String, toolsJson: String, configJson: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.chat) return null;
                const messages = JSON.parse(`${escapeForJs(messagesJson)}`);
                const tools = JSON.parse(`${escapeForJs(toolsJson)}`);
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.chat(messages, tools, config);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    /**
     * Get available models from an AI plugin.
     * Returns raw JSON string of model list.
     */
    suspend fun listModels(pluginId: String, configJson: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.listModels) return null;
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.listModels(config);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun parsePluginInfo(axeJson: String): PluginInfo? {
        return try {
            val axe = json.decodeFromString<AxeFile>(axeJson)
            val m = axe.manifest
            PluginInfo(
                id = m.id,
                name = m.name ?: m.id,
                version = m.version ?: "0.0.0",
                type = m.type ?: "",
                icon = m.icon ?: "",
                color = m.color ?: "",
                capabilities = axe.capabilities ?: emptyMap(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse .axe manifest: ${e.message}")
            null
        }
    }

    /** Compare two semver strings. Returns >0 if a > b, <0 if a < b, 0 if equal. */
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

    /** Escape a string for safe embedding in JS template literals (backtick strings). */
    private fun escapeForJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("'", "\\'")

    /** Strip outer quotes from evaluateJavascript results. */
    private fun String.unquote(): String {
        if (startsWith("\"") && endsWith("\"") && length >= 2) {
            return substring(1, length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return this
    }

    // ── .axe file parsing models ─────────────────────────────────────

    @Serializable
    private data class AxeFile(
        val manifest: AxeManifest,
        val capabilities: Map<String, Boolean>? = null,
    )

    @Serializable
    private data class AxeManifest(
        val id: String,
        val name: String? = null,
        val version: String? = null,
        val type: String? = null,
        val icon: String? = null,
        val color: String? = null,
    )
}

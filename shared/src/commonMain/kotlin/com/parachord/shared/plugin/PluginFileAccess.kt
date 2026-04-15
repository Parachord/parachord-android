package com.parachord.shared.plugin

/**
 * Platform-agnostic file access for the plugin system.
 *
 * Abstracts Android's `context.assets` (bundled) and `context.filesDir` (cached)
 * so PluginManager can run in commonMain.
 *
 * - **Android**: reads from APK assets + app internal storage
 * - **iOS**: reads from app bundle + app support directory
 */
expect class PluginFileAccess {
    /** List filenames of bundled .axe plugins (e.g., from assets/plugins/). */
    fun listBundledPlugins(): List<String>

    /** Read the content of a bundled .axe plugin by filename. */
    fun readBundledPlugin(filename: String): String

    /** List filenames of cached (downloaded) .axe plugins. */
    fun listCachedPlugins(): List<String>

    /** Read the content of a cached .axe plugin by filename. */
    fun readCachedPlugin(filename: String): String

    /** Write a cached .axe plugin (downloaded from marketplace). */
    fun writeCachedPlugin(filename: String, content: String)

    /** Path to the plugin cache directory (for PluginSyncService). */
    val cacheDir: String
}

package com.parachord.shared.plugin

import android.content.Context
import java.io.File

actual class PluginFileAccess(private val context: Context) {

    private val pluginsCacheDir: File by lazy {
        File(context.filesDir, "plugins").also { it.mkdirs() }
    }

    /**
     * Allowed filename pattern for cached plugins.
     * Rejects path separators, parent refs, null bytes, and anything that isn't
     * a plain identifier. Must end in `.axe`.
     *
     * security: H2 — prevents path traversal via attacker-controlled manifest.id
     */
    private val safeFilenameRegex = Regex("^[A-Za-z0-9_.-]+\\.axe$")

    private fun validateSafeFilename(filename: String) {
        if (!safeFilenameRegex.matches(filename)) {
            throw SecurityException("Unsafe plugin filename: '$filename'")
        }
        if (filename.contains("..") || filename.startsWith(".")) {
            throw SecurityException("Unsafe plugin filename: '$filename'")
        }
    }

    /**
     * Resolve a child file under [parent] and verify the canonical path is
     * still within [parent]. Blocks symlink traversal and any unexpected
     * `..` handling that the regex might miss.
     */
    private fun safeChildFile(parent: File, filename: String): File {
        validateSafeFilename(filename)
        val target = File(parent, filename)
        val parentCanonical = parent.canonicalPath + File.separator
        val targetCanonical = target.canonicalPath
        if (!targetCanonical.startsWith(parentCanonical)) {
            throw SecurityException("Plugin path escapes cache dir: '$filename'")
        }
        return target
    }

    actual fun listBundledPlugins(): List<String> {
        return context.assets.list("plugins")?.toList() ?: emptyList()
    }

    actual fun readBundledPlugin(filename: String): String {
        // Bundled plugins are packaged by us; still validate as defense in depth.
        validateSafeFilename(filename)
        return context.assets.open("plugins/$filename").bufferedReader().readText()
    }

    actual fun listCachedPlugins(): List<String> {
        // Filter out any leftover files that don't match the safe pattern so
        // we don't try to load something we wouldn't allow to be written.
        return pluginsCacheDir.listFiles()
            ?.map { it.name }
            ?.filter { safeFilenameRegex.matches(it) }
            ?: emptyList()
    }

    actual fun readCachedPlugin(filename: String): String {
        return safeChildFile(pluginsCacheDir, filename).readText()
    }

    actual fun writeCachedPlugin(filename: String, content: String) {
        safeChildFile(pluginsCacheDir, filename).writeText(content)
    }

    actual val cacheDir: String
        get() = pluginsCacheDir.absolutePath
}

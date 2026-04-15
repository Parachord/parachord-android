package com.parachord.shared.plugin

import android.content.Context
import java.io.File

actual class PluginFileAccess(private val context: Context) {

    private val pluginsCacheDir: File by lazy {
        File(context.filesDir, "plugins").also { it.mkdirs() }
    }

    actual fun listBundledPlugins(): List<String> {
        return context.assets.list("plugins")?.toList() ?: emptyList()
    }

    actual fun readBundledPlugin(filename: String): String {
        return context.assets.open("plugins/$filename").bufferedReader().readText()
    }

    actual fun listCachedPlugins(): List<String> {
        return pluginsCacheDir.listFiles()?.map { it.name } ?: emptyList()
    }

    actual fun readCachedPlugin(filename: String): String {
        return File(pluginsCacheDir, filename).readText()
    }

    actual fun writeCachedPlugin(filename: String, content: String) {
        File(pluginsCacheDir, filename).writeText(content)
    }

    actual val cacheDir: String
        get() = pluginsCacheDir.absolutePath
}

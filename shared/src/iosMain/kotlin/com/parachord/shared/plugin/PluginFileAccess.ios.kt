package com.parachord.shared.plugin

import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathDomainMask
import platform.Foundation.NSString
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

actual class PluginFileAccess {

    private val pluginsCacheDir: String by lazy {
        val paths = NSFileManager.defaultManager.URLsForDirectory(
            NSSearchPathDirectory.NSApplicationSupportDirectory,
            NSSearchPathDomainMask.NSUserDomainMask
        )
        val appSupport = (paths.firstOrNull() as? platform.Foundation.NSURL)?.path ?: ""
        val dir = "$appSupport/plugins"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        dir
    }

    actual fun listBundledPlugins(): List<String> {
        val bundlePath = NSBundle.mainBundle.pathForResource("plugins", ofType = null) ?: return emptyList()
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(bundlePath, null)
        @Suppress("UNCHECKED_CAST")
        return (contents as? List<String>)?.filter { it.endsWith(".axe") } ?: emptyList()
    }

    actual fun readBundledPlugin(filename: String): String {
        val bundlePath = NSBundle.mainBundle.pathForResource("plugins", ofType = null) ?: return ""
        val filePath = "$bundlePath/$filename"
        return NSString.stringWithContentsOfFile(filePath) ?: ""
    }

    actual fun listCachedPlugins(): List<String> {
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(pluginsCacheDir, null)
        @Suppress("UNCHECKED_CAST")
        return (contents as? List<String>)?.filter { it.endsWith(".axe") } ?: emptyList()
    }

    actual fun readCachedPlugin(filename: String): String {
        val filePath = "$pluginsCacheDir/$filename"
        return NSString.stringWithContentsOfFile(filePath) ?: ""
    }

    actual fun writeCachedPlugin(filename: String, content: String) {
        val filePath = "$pluginsCacheDir/$filename"
        (content as NSString).writeToFile(filePath, true)
    }

    actual val cacheDir: String
        get() = pluginsCacheDir
}

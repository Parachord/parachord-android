package com.parachord.shared.ios

import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Tiny JSON-string file cache in the iOS Caches directory, backing the
 * repositories' `cacheRead`/`cacheWrite` lambdas (stale-while-revalidate +
 * TTL short-circuit). Without this every Discover-screen visit re-fetches and
 * re-enriches from scratch (~40s for Critical Darlings). NSData round-trip
 * matches the proven PluginFileAccess.ios pattern (avoids the ambiguous
 * NSString.writeToFile / stringWithContentsOfFile overloads).
 */
internal object IosFileCache {
    private val dir: String by lazy {
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String) ?: NSTemporaryDirectory()
    }

    fun read(name: String): String? {
        val data: NSData = NSData.dataWithContentsOfFile("$dir/$name") ?: return null
        @Suppress("CAST_NEVER_SUCCEEDS")
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as? String
    }

    fun write(name: String, content: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        data?.writeToFile("$dir/$name", atomically = true)
    }
}

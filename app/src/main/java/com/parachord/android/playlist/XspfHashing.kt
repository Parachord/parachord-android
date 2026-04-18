package com.parachord.android.playlist

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * SHA-256 of the raw XSPF body, hex-encoded. Used as a cheap change-detection
 * token for hosted-playlist polling: if the hash matches `sourceContentHash`
 * on the local row, the content is unchanged and no reimport is needed.
 */
internal fun sha256Hex(content: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (b in digest) append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1))
    }
}

/**
 * Reject non-HTTPS schemes and private / loopback / link-local host targets.
 * Used on every hosted-playlist fetch (import time and every poll tick) so
 * a later DNS record change can't redirect the fetch to an internal address.
 *
 * We don't resolve DNS here (that would introduce a TOCTOU race); callers
 * with a resolvable attacker-controlled hostname can still reach internal
 * IPs via DNS rebinding. Revisit with a socket-level post-resolution check
 * if that threat becomes relevant. security: H10
 */
internal fun validateXspfUrl(url: String) {
    val parsed = try {
        url.toHttpUrlOrNull()
    } catch (_: Exception) {
        null
    } ?: throw IllegalArgumentException("Invalid playlist URL")

    if (!parsed.isHttps) {
        throw IllegalArgumentException("Playlist URL must use https:// (got ${parsed.scheme})")
    }
    val host = parsed.host.lowercase()
    if (host.isBlank()) {
        throw IllegalArgumentException("Playlist URL is missing a host")
    }
    if (isPrivateOrLocalHost(host)) {
        throw IllegalArgumentException("Playlist URL targets a private / loopback address: $host")
    }
}

private fun isPrivateOrLocalHost(host: String): Boolean {
    if (host == "localhost") return true
    val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
    if (ipv4 != null) {
        val octets = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: return true }
        val (a, b) = octets[0] to octets[1]
        return a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            a == 0
    }
    if (host == "::1" || host == "[::1]") return true
    if (host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80")) return true
    if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) return true
    if (host.endsWith(".local")) return true
    return false
}

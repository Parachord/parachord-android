package com.parachord.shared.api.auth

import org.kotlincrypto.hash.md.MD5

/**
 * Last.fm API signature helper. See https://www.last.fm/api/desktopauth — Signing Calls.
 *
 * Algorithm:
 *  1. Filter out api_sig (circular — can't sign yourself) and format (per docs, not signed).
 *  2. Sort the remaining params alphabetically by name.
 *  3. Concatenate: name1 + value1 + name2 + value2 + ... + sharedSecret. No separators.
 *  4. UTF-8 encode and MD5.
 *  5. Return lowercase hex.
 *
 * Uses kotlincrypto-hash-md5 (KMP-native MD5) so this works on both JVM and
 * Kotlin/Native targets without expect/actual.
 */
fun lastFmSignature(params: Map<String, String>, sharedSecret: String): String {
    val concat = buildString {
        params
            .filterKeys { it != "api_sig" && it != "format" }
            .toSortedMap()
            .forEach { (k, v) -> append(k).append(v) }
        append(sharedSecret)
    }
    val digest = MD5().digest(concat.encodeToByteArray())
    return digest.joinToString("") { byte ->
        ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1)
    }
}

@file:Suppress("unused")
package com.parachord.android.resolver

/**
 * Re-exports from shared module for backward compatibility.
 * Existing app code can continue importing from com.parachord.android.resolver.
 */
typealias ResolverInfo = com.parachord.shared.resolver.ResolverInfo
typealias ResolvedSource = com.parachord.shared.resolver.ResolvedSource

// Re-export functions
fun normalizeStr(s: String?): String = com.parachord.shared.resolver.normalizeStr(s)

fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
): Double = com.parachord.shared.resolver.scoreConfidence(targetTitle, targetArtist, matchedTitle, matchedArtist)

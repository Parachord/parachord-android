package com.parachord.shared.resolver

import kotlinx.serialization.Serializable

@Serializable
data class ResolverInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ResolvedSource(
    val url: String,
    val sourceType: String,
    val resolver: String,
    val quality: Int? = null,
    val headers: Map<String, String>? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    /** Match confidence from the resolver (0.0–1.0). Desktop defaults to 0.9 for successful resolves. */
    val confidence: Double? = null,
    /** Whether the resolver explicitly couldn't match this track. */
    val noMatch: Boolean = false,
    /** The title returned by the resolver's search result (for confidence scoring). */
    val matchedTitle: String? = null,
    /** The artist returned by the resolver's search result (for confidence scoring). */
    val matchedArtist: String? = null,
    /** Duration in ms from the resolver's result (for confidence scoring). */
    val matchedDurationMs: Long? = null,
)

// ── Confidence Scoring ──────────────────────────────────────────────

/**
 * Normalize a string for comparison: lowercase, strip everything except
 * alphanumeric characters. Matches the desktop's normalizeStr().
 */
fun normalizeStr(s: String?): String =
    s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""

/**
 * Check if two normalized strings match via containment (either direction).
 * Matches the desktop's validateResolvedTrack() logic.
 */
private fun stringsMatch(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Calculate match confidence by comparing the resolver's result against the
 * target track. Matches the desktop's calculateConfidence():
 *
 * - Title + artist match → 0.95
 * - Title match only     → 0.85
 * - Artist match only    → 0.70
 * - No match             → 0.50
 *
 * Direct ID matches (spotifyId, appleMusicId, soundcloudId) bypass this and
 * keep their 0.95 confidence since the ID is authoritative.
 */
fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
): Double {
    val normTarget = normalizeStr(targetTitle)
    val normArtist = normalizeStr(targetArtist)
    val normMatchTitle = normalizeStr(matchedTitle)
    val normMatchArtist = normalizeStr(matchedArtist)

    val titleMatch = stringsMatch(normTarget, normMatchTitle)
    val artistMatch = stringsMatch(normArtist, normMatchArtist)

    return when {
        titleMatch && artistMatch -> 0.95
        titleMatch -> 0.85
        artistMatch -> 0.70
        else -> 0.50
    }
}

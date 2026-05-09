package com.parachord.shared.deeplink

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Inline tracks cap (per protocol-schema.md). 500 is desktop's value;
 * exceeding it throws — caller surfaces "playlist too large" toast.
 */
const val MAX_INLINE_TRACKS: Int = 500

/**
 * Parsed shape returned by [parseProtocolTracklist].
 *
 * - [title] / [creator] are display hints; never required by the resolver.
 * - [tracks] is non-empty (we throw if zero parseable tracks were found).
 */
data class ParsedProtocolTracklist(
    val title: String?,
    val creator: String?,
    val tracks: List<ProtocolTrack>,
)

/**
 * Looser 36-char hex+dash regex used for JSPF identifier MBID extraction.
 *
 * Mirrors desktop exactly. Looser than [MBID_REGEX] (which enforces the
 * 8-4-4-4-12 canonical form) because some JSPF producers emit MBIDs in
 * non-canonical UUID formats. We accept "looks like a UUID" here and let
 * [isValidMbid] gate at the resolver if strict canonical form is needed.
 */
internal val JSPF_MBID_REGEX: Regex = Regex("^[a-f0-9-]{36}$", RegexOption.IGNORE_CASE)

/**
 * Auto-detects XSPF (XML) / JSPF (JSON, LB-style) / generic JSON
 * tracklists and parses to [ParsedProtocolTracklist].
 *
 * Format detection (mirrors desktop):
 * - Starts with `<` → XSPF — delegates to [parseXspfPlatform] (the
 *   existing platform-specific XML parser; Android uses `XmlPullParser`,
 *   iOS would use `NSXMLParser`).
 * - Starts with `{` or `[` → JSON. Inspect for `playlist.track` (JSPF)
 *   vs. `tracks` array (generic JSON).
 *
 * @param parseXspfPlatform lambda the platform module supplies to do
 *   the actual XML parse. Wired through Koin in `AndroidModule.kt`.
 *   Returns the playlist's tracks plus the playlist title/creator
 *   (matching desktop's parser's return shape).
 *
 * @throws IllegalArgumentException on unrecognized format, empty
 *   tracklist, or > [MAX_INLINE_TRACKS] tracks.
 */
fun parseProtocolTracklist(
    content: String,
    parseXspfPlatform: (String) -> ParsedProtocolTracklist,
): ParsedProtocolTracklist {
    val trimmed = content.trimStart()
    val parsed = when {
        trimmed.startsWith("<") -> parseXspfPlatform(content)
        trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJson(trimmed)
        else -> throw IllegalArgumentException(
            "Unrecognized tracklist format (expected XSPF / JSPF / JSON, got: ${trimmed.take(40)})"
        )
    }
    if (parsed.tracks.isEmpty()) {
        throw IllegalArgumentException("Tracklist contains no tracks")
    }
    if (parsed.tracks.size > MAX_INLINE_TRACKS) {
        throw IllegalArgumentException("Tracklist exceeds $MAX_INLINE_TRACKS tracks")
    }
    return parsed
}

// ── JSON / JSPF parsing ───────────────────────────────────────────────

private fun parseJson(content: String): ParsedProtocolTracklist {
    val root: JsonElement = ProtocolJson.parseToJsonElement(content)

    // LB lb-radio wrapper: unwrap parsed.payload?.jspf || parsed before
    // looking for `playlist.track` (per desktop CLAUDE.md).
    val unwrapped = (root as? JsonObject)
        ?.get("payload")?.asJsonObjectOrNull()
        ?.get("jspf")?.asJsonObjectOrNull()
        ?: root

    if (unwrapped is JsonObject) {
        // JSPF form: { playlist: { title?, creator?, track: [...] } }
        val playlist = unwrapped["playlist"]?.asJsonObjectOrNull()
        if (playlist != null && playlist["track"] is JsonArray) {
            return parseJspf(playlist)
        }
        // Generic JSON form: { title?, tracks: [{artist, title, album?, mbid?, isrc?}] }
        val tracksArr = unwrapped["tracks"]?.asJsonArrayOrNull()
        if (tracksArr != null) {
            return ParsedProtocolTracklist(
                title = unwrapped["title"]?.asStringOrNull(),
                creator = unwrapped["creator"]?.asStringOrNull(),
                tracks = tracksArr.mapNotNull { it.toGenericProtocolTrack() },
            )
        }
    }
    if (unwrapped is JsonArray) {
        // Bare array form: [{artist, title, ...}, ...]
        return ParsedProtocolTracklist(
            title = null,
            creator = null,
            tracks = unwrapped.mapNotNull { it.toGenericProtocolTrack() },
        )
    }

    throw IllegalArgumentException("JSON tracklist has no recognized shape (expected `playlist.track` (JSPF), `tracks` array, or bare array)")
}

private fun parseJspf(playlist: JsonObject): ParsedProtocolTracklist {
    val title = playlist["title"]?.asStringOrNull()
    val creator = playlist["creator"]?.asStringOrNull()
    val tracks = playlist["track"]!!.jsonArray.mapNotNull { it.toJspfProtocolTrack() }
    return ParsedProtocolTracklist(title, creator, tracks)
}

// ── Per-track decoders ────────────────────────────────────────────────

private fun JsonElement.toGenericProtocolTrack(): ProtocolTrack? {
    val obj = this as? JsonObject ?: return null
    val artist = obj["artist"]?.asStringOrNull() ?: return null
    val title = obj["title"]?.asStringOrNull() ?: return null
    return ProtocolTrack(
        artist = artist,
        title = title,
        album = obj["album"]?.asStringOrNull(),
        mbid = obj["mbid"]?.asStringOrNull()?.takeIf { isValidMbid(it.lowercase()) },
        isrc = obj["isrc"]?.asStringOrNull(),
    )
}

private fun JsonElement.toJspfProtocolTrack(): ProtocolTrack? {
    val obj = this as? JsonObject ?: return null

    // Title: required.
    val title = obj["title"]?.asStringOrNull() ?: return null

    // Creator can be a string or an array (some producers emit arrays);
    // join arrays with ", " per desktop.
    val creator: String = when (val c = obj["creator"]) {
        is JsonPrimitive -> c.contentOrNull
        is JsonArray -> c.mapNotNull { it.asStringOrNull() }.joinToString(", ").ifEmpty { null }
        else -> null
    } ?: return null

    val album = obj["album"]?.asStringOrNull()

    // MBID: identifier may be a string or an array of strings. Look for
    // either bare 36-char UUIDs or `musicbrainz.org/(recording|track)/<uuid>`.
    val mbidCandidates: List<String> = when (val ident = obj["identifier"]) {
        is JsonPrimitive -> listOfNotNull(ident.contentOrNull)
        is JsonArray -> ident.mapNotNull { it.asStringOrNull() }
        else -> emptyList()
    }
    val mbid = mbidCandidates.firstNotNullOfOrNull { extractMbid(it) }

    // ISRC: some JSPFs put it under `extension.isrc` or top-level `isrc`.
    val isrc = obj["isrc"]?.asStringOrNull()
        ?: obj["extension"]?.asJsonObjectOrNull()?.get("isrc")?.asStringOrNull()

    return ProtocolTrack(
        artist = creator,
        title = title,
        album = album,
        mbid = mbid,
        isrc = isrc,
    )
}

/**
 * Extract a 36-char UUID-shaped MBID from a JSPF identifier.
 *
 * Accepts:
 * - Bare UUID: `c70a2d8f-c1b7-4f10-9d10-95158a08b528`
 * - URL forms: `https://musicbrainz.org/recording/<uuid>`,
 *              `https://musicbrainz.org/track/<uuid>`
 *
 * Returns the lowercase UUID if matched, else null.
 */
private fun extractMbid(identifier: String): String? {
    val trimmed = identifier.trim()
    if (JSPF_MBID_REGEX.matches(trimmed)) return trimmed.lowercase()
    val mbUrl = Regex(
        "musicbrainz\\.org/(?:recording|track)/([a-f0-9-]{36})",
        RegexOption.IGNORE_CASE,
    )
    return mbUrl.find(trimmed)?.groupValues?.get(1)?.lowercase()
}

// ── Tiny JsonElement helpers ──────────────────────────────────────────

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.asJsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

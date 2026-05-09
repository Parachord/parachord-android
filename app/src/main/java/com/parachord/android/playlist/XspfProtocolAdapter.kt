package com.parachord.android.playlist

import com.parachord.shared.deeplink.ParsedProtocolTracklist
import com.parachord.shared.deeplink.ProtocolTrack

/**
 * Adapter that bridges the existing Android-side [XspfParser] (built on
 * `android.util.Xml`) into the shared [com.parachord.shared.deeplink.parseProtocolTracklist]
 * via the `parseXspfPlatform` lambda parameter.
 *
 * Lives here (in `app/playlist`) instead of in `shared` because Android's
 * `XmlPullParser` is not available in commonMain. The shared parser
 * dispatches by content prefix; for XSPF input it calls into this
 * adapter (wired via Koin in `AndroidModule`) to get back a
 * [ParsedProtocolTracklist].
 *
 * Mapping:
 * - `XspfPlaylist.title` → [ParsedProtocolTracklist.title]
 * - `XspfPlaylist.creator` → [ParsedProtocolTracklist.creator]
 * - `TrackEntity.{title,artist,album}` → [ProtocolTrack.{title,artist,album}]
 *
 * The XSPF parser doesn't currently extract MBID / ISRC (they're not in
 * the existing TrackEntity output), so [ProtocolTrack.mbid] /
 * [ProtocolTrack.isrc] are always null on the XSPF path. Non-XSPF
 * formats (JSPF / generic JSON) still surface them via shared parser.
 */
fun parseXspfForProtocol(xspfContent: String): ParsedProtocolTracklist {
    val parsed = XspfParser.parse(xspfContent)
    return ParsedProtocolTracklist(
        title = parsed.title,
        creator = parsed.creator,
        tracks = parsed.tracks.map {
            ProtocolTrack(
                artist = it.artist,
                title = it.title,
                album = it.album,
            )
        },
    )
}

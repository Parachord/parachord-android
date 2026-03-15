package com.parachord.android.playlist

import android.util.Xml
import com.parachord.android.data.db.entity.TrackEntity
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class XspfPlaylist(
    val title: String,
    val creator: String?,
    val tracks: List<TrackEntity>,
)

/**
 * Parse XSPF (XML Shareable Playlist Format) into a playlist.
 * Desktop equivalent: app.js parseXSPF() lines 23917-23950
 */
object XspfParser {

    fun parse(xspfContent: String): XspfPlaylist {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xspfContent))

        var playlistTitle = "Imported Playlist"
        var playlistCreator: String? = null
        val tracks = mutableListOf<TrackEntity>()

        var inTrack = false
        var trackTitle: String? = null
        var trackArtist: String? = null
        var trackAlbum: String? = null
        var trackDuration: Long? = null
        var trackLocation: String? = null
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "track") {
                        inTrack = true
                        trackTitle = null
                        trackArtist = null
                        trackAlbum = null
                        trackDuration = null
                        trackLocation = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        if (inTrack) {
                            when (currentTag) {
                                "title" -> trackTitle = text
                                "creator" -> trackArtist = text
                                "album" -> trackAlbum = text
                                "duration" -> trackDuration = text.toLongOrNull()
                                "location" -> trackLocation = text
                            }
                        } else {
                            when (currentTag) {
                                "title" -> playlistTitle = text
                                "creator" -> playlistCreator = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "track" && inTrack) {
                        val title = trackTitle ?: "Unknown"
                        val artist = trackArtist ?: "Unknown"
                        tracks.add(
                            TrackEntity(
                                id = "xspf:${artist}:${title}:${tracks.size}",
                                title = title,
                                artist = artist,
                                album = trackAlbum,
                                duration = trackDuration,
                                sourceUrl = trackLocation,
                                sourceType = if (trackLocation != null) "stream" else null,
                            )
                        )
                        inTrack = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return XspfPlaylist(
            title = playlistTitle,
            creator = playlistCreator,
            tracks = tracks,
        )
    }
}

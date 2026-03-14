package com.parachord.android.playback.scrobbler

import com.parachord.android.data.db.entity.TrackEntity

/**
 * Interface for scrobbling services, mirroring the desktop app's base-scrobbler.js.
 *
 * Each implementation handles a specific service (Last.fm, ListenBrainz, Libre.fm)
 * and is responsible for its own authentication and API protocol.
 */
interface Scrobbler {
    /** Unique identifier for this scrobbler (e.g. "lastfm", "listenbrainz", "librefm"). */
    val id: String

    /** Human-readable name shown in settings. */
    val displayName: String

    /** Whether this scrobbler is currently authenticated and enabled. */
    suspend fun isEnabled(): Boolean

    /** Send a "now playing" notification to the service. */
    suspend fun sendNowPlaying(track: TrackEntity)

    /** Submit a scrobble (track was listened to sufficiently). */
    suspend fun submitScrobble(track: TrackEntity, timestamp: Long)
}

package com.parachord.android.sync

/**
 * Thrown when an Apple Music API call returns 401 on an endpoint that
 * is NOT in the documented-unsupported list. Documented-unsupported
 * endpoints (PATCH/PUT/DELETE on `/me/library/playlists`) return their
 * respective sentinel results instead — they don't raise this.
 *
 * Per desktop CLAUDE.md "Do NOT retry-on-401 for any of the
 * documented-unsupported endpoints": defensively retrying with a
 * fresh token here would walk the user through a System Settings
 * revoke flow for an authorization that was never broken (since the
 * 401 is structural, not token-related). Go straight to this
 * exception on the first 401 from a SHOULD-WORK endpoint
 * (`listPlaylists`, `listPlaylistTracks`, `getStorefront`,
 * `createPlaylist`).
 *
 * SyncEngine catches this and surfaces a "Reconnect Apple Music"
 * toast / settings deep link rather than retrying.
 */
class AppleMusicReauthRequiredException(
    message: String = "Apple Music user token rejected; user must re-authorize",
) : Exception(message)

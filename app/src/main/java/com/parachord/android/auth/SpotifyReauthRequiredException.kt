package com.parachord.android.auth

/**
 * Thrown when Spotify's token endpoint rejects the stored refresh token
 * with `400 invalid_grant` (typically "Refresh token revoked" — user
 * removed Parachord from accounts.spotify.com/account/apps).
 *
 * Mirrors the session-scoped kill-switch pattern used by
 * [com.parachord.shared.sync.AppleMusicReauthRequiredException] and
 * `ListenBrainzSyncProvider.authFailedForSession`: once tripped,
 * subsequent refresh attempts short-circuit (throw without hitting the
 * network) until either the process restarts or the user completes a
 * fresh OAuth flow.
 *
 * Without this gate, every handler that independently calls
 * `refreshSpotifyToken` on 401 (the device poller, the scrobbler, the
 * sync engine) would re-fire the refresh — observed ~100 token-endpoint
 * POSTs in 30 seconds across 8 workers before the auth failure
 * surfaced as a device-polling timeout.
 */
class SpotifyReauthRequiredException(
    message: String = "Spotify refresh token revoked; user must reconnect",
) : Exception(message)

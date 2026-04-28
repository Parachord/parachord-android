package com.parachord.shared.api.auth

/**
 * Logical authentication realm — used by AuthTokenProvider.tokenFor() to
 * disambiguate which credentials to return.
 *
 * Adding a new external API that requires authentication = add a new entry here.
 * Compile errors at consumer call sites surface every place that needs an
 * auth update.
 */
enum class AuthRealm {
    Spotify,
    AppleMusicLibrary,
    ListenBrainz,
    LastFm,
    Ticketmaster,
    SeatGeek,
    Discogs,
}

/**
 * Per-API credential shapes. Each Ktor client knows which subtype its realm
 * returns and casts accordingly.
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md "Per-API auth strategy"
 */
sealed class AuthCredential {
    /** Spotify, Apple Music Catalog. */
    data class BearerToken(val accessToken: String) : AuthCredential()

    /** Apple Music Library — dev token + Music-User-Token. */
    data class BearerWithMUT(val devToken: String, val mut: String) : AuthCredential()

    /** ListenBrainz uses "Authorization: Token <key>", not "Bearer <key>". Discogs uses "Discogs token=<key>". */
    data class TokenPrefixed(val prefix: String, val token: String) : AuthCredential()

    /** Ticketmaster, SeatGeek — query param key. */
    data class ApiKeyParam(val paramName: String, val value: String) : AuthCredential()

    /** Last.fm — per-request MD5 signing. sessionKey is null during auth flow,
     *  populated after token exchange. */
    data class LastFmSigned(val sharedSecret: String, val sessionKey: String?) : AuthCredential()
}

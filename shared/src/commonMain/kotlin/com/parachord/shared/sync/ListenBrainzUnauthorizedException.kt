package com.parachord.shared.sync

/**
 * Thrown by [com.parachord.shared.api.ListenBrainzClient] mutation
 * endpoints when LB returns 401. [ListenBrainzSyncProvider] catches
 * this and trips its session-scoped auth-failed kill-switch, mirroring
 * [AppleMusicReauthRequiredException]'s contract.
 */
class ListenBrainzUnauthorizedException(
    message: String = "ListenBrainz returned 401 — token rejected",
) : Exception(message)

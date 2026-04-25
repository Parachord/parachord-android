package com.parachord.shared.sync

/**
 * Cross-platform sync provider interface. Spotify, Apple Music, Tidal,
 * and any future provider implements this. SyncEngine never branches on
 * `provider.id`; it dispatches on `provider.features` and lets each
 * provider declare its own capability surface.
 *
 * See parent plan `docs/plans/2026-04-22-multi-provider-sync-correctness.md`
 * for the design rationale, propagation invariants, and per-provider
 * idiosyncrasies this interface abstracts.
 */
interface SyncProvider {
    /** Stable identifier ("spotify", "applemusic", future "tidal"). */
    val id: String

    /** Human-readable label shown in settings + wizard ("Spotify", "Apple Music"). */
    val displayName: String

    /** Capability flags. SyncEngine routes on these, never on `id`. */
    val features: ProviderFeatures
}

/**
 * Per-provider capability declarations. Adding a new provider means
 * implementing SyncProvider with its own ProviderFeatures — no changes
 * to SyncEngine required.
 */
data class ProviderFeatures(
    /** What kind of snapshot/change-token this provider's playlists carry. */
    val snapshots: SnapshotKind,
    /** Whether the provider supports a follow/unfollow API for artists. Apple Music = false. */
    val supportsFollow: Boolean,
    /** Whether the provider supports playlist deletion via API. Apple Music = false. */
    val supportsPlaylistDelete: Boolean,
    /** Whether the provider supports playlist rename via API. Apple Music = false. */
    val supportsPlaylistRename: Boolean,
    /** Whether full-replace PUT on tracks is reliable; if false, push degrades to append-only after first failure. */
    val supportsTrackReplace: Boolean,
)

enum class SnapshotKind {
    /** Provider returns a stable opaque token (Spotify `snapshot_id`). String-equality compare. */
    Opaque,
    /** Provider returns a date/version string (Apple `lastModifiedDate`). String-equality compare; refetch on mismatch. */
    DateString,
    /** Provider has no snapshot. SyncEngine falls back to always-pull. Costlier — 1 extra API call per playlist per sync. */
    None,
}

/**
 * Result of a [SyncProvider.deletePlaylist] call. Provider implementations
 * must NEVER throw on documented-unsupported responses (e.g. Apple's 401
 * on DELETE) — return [Unsupported] instead so the caller can surface
 * "remove manually in the {provider}" UX.
 */
sealed class DeleteResult {
    object Success : DeleteResult()
    data class Unsupported(val status: Int) : DeleteResult()
    data class Failed(val error: Throwable) : DeleteResult()
}

/**
 * Returned by [SyncProvider.createPlaylist] — the newly-created remote
 * playlist's external ID and (if the provider supports snapshots) the
 * initial snapshot token.
 */
data class RemoteCreated(
    val externalId: String,
    val snapshotId: String?,
)

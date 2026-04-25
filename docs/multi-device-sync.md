# Multi-Device Sync Behavior

This document catalogs how Parachord handles playlist + library sync when a
user runs both the desktop and mobile apps against the same accounts. It is
intended as a reference for support, QA, and anyone reasoning about edge
cases in the sync engine.

For implementation details (the `SyncProvider` interface, the four-piece
propagation rules, Apple Music endpoint degradation, etc.) see the
**Multi-Provider Sync** section in [`CLAUDE.md`](../CLAUDE.md).

## Mental model

Two devices, both running Parachord, each connected to Spotify and/or
Apple Music. **The provider is always the source of truth for cross-device
convergence — there is no device-to-device link.** "Sync mobile → Spotify
← desktop" really means "mobile pushes to Spotify, then Spotify is the
merge point, then desktop pulls."

A playlist can carry:

- **One pull source** (`syncedFrom`) — at most one provider that we treat
  as canonical for incoming changes.
- **N push mirrors** (`syncedTo[providerId]`) — every provider we mirror
  outgoing changes to.

These are stored in `sync_playlist_source` (PK = `localPlaylistId`) and
`sync_playlist_link` (composite key `(localPlaylistId, providerId)`).

## Use case table

| # | Scenario | Source of truth | Push mirrors | What each device sees | Gotchas |
|---|---|---|---|---|---|
| 1 | **Hosted XSPF, imported on mobile only**, AM sync off | XSPF URL | Spotify | Mobile polls XSPF every 5min (foreground) / 15min (WorkManager) → mutates local row → pushes to Spotify. Desktop pulls from Spotify on next sync. | Desktop's row lands as `spotify-<id>` with `sourceUrl=null` → no `🌐 Hosted` chip on desktop, no polling on desktop. Functionally fine. |
| 2 | **Hosted XSPF, imported on mobile only**, AM enabled on both | XSPF URL | Spotify + Apple Music | Mobile pushes to both. Desktop pulls from Spotify (preferred), AM mirror updates as side-effect via desktop's own next push when its `locallyModified` flag fires. | XSPF → AM track-removal does NOT propagate (AM PUT 401 → POST-append degrade). Adds work; deletes don't. |
| 3 | **Hosted XSPF, imported on desktop only** | XSPF URL | Spotify (+ AM if desktop has it on) | Desktop is the polling agent; pushes to Spotify. Mobile pulls from Spotify, sees `spotify-<id>`. | Desktop sleep = no polls. Phone-as-polling-agent is strictly better (always-on). |
| 4 | **Hosted XSPF, imported on BOTH devices** ⚠️ | XSPF URL (each device polls independently) | Spotify | Two distinct local rows — different `hosted-xspf-<uuid>` IDs. Each device polls + pushes. Three-layer dedup name-matches on push so only one Spotify playlist exists, but each device retains its own local-row duplicate. | **Don't do this.** Import on one device only. Phone preferred. |
| 5 | **Local-only playlist** (`localOnly = 1` or no sync intent) | Local | None | Stays on the device it was created on. No push. Editing doesn't flag `locallyModified`. | Genuinely device-local. Won't appear on the other device until you flip a sync target on. |
| 6 | **`local-*` playlist, Spotify push enabled** | Local first push wins; then Spotify | Spotify | Mobile creates → push to Spotify → Spotify-side ID is now in `sync_playlist_link`. Desktop pulls from Spotify. After first sync, behaves like scenario 8. | First-push race: if both devices create same-named local playlists, three-layer name-match dedup links them on the second device's push. |
| 7 | **`local-*` playlist, Spotify + AM push enabled** | Local first; then both providers | Spotify + Apple Music | Both providers receive the create. Desktop pulls primarily from Spotify (preferred); AM stays in lockstep as a push mirror. | Tracks Apple can't catalog-resolve (currently) get silently skipped — Apple link's tracklist may be a subset of Spotify's. Catalog-search resolution is deferred (D1). |
| 8 | **Spotify-originated playlist** (`spotify-*`), edits made on mobile | Spotify | Spotify (and AM if AM sync is on) | Mobile edit → `locallyModified=true` → push to Spotify. Per Phase 4.5, also pushed to AM (AM gets `spotify-*` push candidates). Desktop pulls from Spotify next sync. | Phase 3 rule 3 (provider-scoped `syncedFrom` guard): don't blanket-skip — AM mirror still updates. |
| 9 | **Spotify-originated, AM mirror enabled on mobile, AM disabled on desktop** | Spotify | Spotify + Apple Music (mobile-side only) | Desktop pushes only to Spotify. Mobile pushes to both. AM mirror is "owned" by mobile's sync cycle. | If desktop edits → AM mirror only updates on next mobile sync (mobile sees Spotify change → flags `locallyModified` per Phase 3 rule 1 → pushes to AM). |
| 10 | **Apple Music-originated playlist** (`applemusic-*`), AM sync only | Apple Music | Apple Music | Mobile imports from AM library. Edits propagate back via PUT (or POST-append degrade after first 401). Desktop with AM enabled also pulls. | AM track removals don't propagate (POST-append only). Rename is silent no-op. Delete returns Unsupported → toast tells user to remove in the Music app. |
| 11 | **AM-originated, Spotify push enabled** | Apple Music | Spotify | Mobile pulls from AM. Mobile's push loop creates a Spotify mirror. `sync_playlist_source` records AM as the pull source; `sync_playlist_link` records Spotify as the push mirror. Desktop pulls from Spotify (sees it as `spotify-*`) — but if desktop also has AM enabled, the cross-provider `isOwnPullSource` guard prevents Spotify from clobbering AM as the pull source on desktop. | Cross-provider preservation invariant: desktop sees the Spotify version first via Spotify-pull, then AM-pull updates `syncedAt` on the link without flipping `syncedFrom`. No duplicate gets created. |
| 12 | **Same playlist edited on both devices in the same sync window** | Whichever provider write lands second | depends on scenario | Last-write-wins per provider. Both devices converge on next pull. No cross-device locking. | Not "merge" — overwrite. The slower device's edits are lost on that provider. |
| 13 | **Playlist renamed on mobile**, mirrored to AM | Local rename → push | Spotify (rename works) + Apple Music (rename silently no-ops) | Spotify name updates. AM name doesn't (PATCH 401 kill-switch). Desktop sees Spotify rename on next pull. | AM name diverges silently. No banner — `updatePlaylistDetails` is load-bearing (runs before track push), so it must not throw or warn loudly. Document this. |
| 14 | **Playlist deleted on mobile** | n/a | Spotify (deleted) + AM (Unsupported) | Spotify mirror is deleted. AM mirror returns `DeleteResult.Unsupported(401)` → toast: *"Removed from Parachord. Apple Music doesn't allow deletion via the API — remove manually in the Apple Music app."* Local row is gone regardless. Desktop pulls — Spotify version disappears, AM version remains until manually removed in Music app. | Desktop's local row for the AM-only mirror remains until AM-side is manually removed and desktop next pulls. |
| 15 | **Followed Spotify playlist** (`isOwned = false`) | Spotify | None — read-only | Both devices pull. Edits not allowed. | Push candidate filter excludes unowned playlists. |
| 16 | **Library tracks/albums/artists** (Phase 7 collection sync) | Each provider for its own library | n/a (per-provider library) | Mobile pulls AM library + Spotify library. Desktop does the same. Local removal on either device fires `removeTracks`/`removeAlbums` on every linked provider. Artist follows are Spotify-only (AM has no follow API). | AM removal is per-item DELETE (no bulk endpoint). 404 on race is silently swallowed. Add-to-library uses `?ids[songs]=a,b,c` query string, NOT a JSON body. |

## Key invariants behind the table

- **`syncedFrom` is at most one provider per playlist.** The pull source.
  Cross-provider import preserves the existing `syncedFrom` (Phase 3
  follow-on `isOwnPullSource` guard).
- **`syncedTo` is N providers per playlist.** Every push mirror.
- **Push-loop guard is provider-scoped, not blanket** — a Spotify-imported
  playlist still pushes to AM (Phase 3 rule 3).
- **Pull paths flag `locallyModified` when other mirrors exist** (Phase 3
  rule 1) — without this, an edit pulled through one provider never
  reaches another.
- **Three-layer dedup runs on every push** — id-link → playlist-field →
  name-match (case-insensitive, owned-only). Prevents duplicates when two
  devices push the same content.
- **No cross-device locking.** The provider is the merge oracle. Both
  devices skip-if-held their local sync mutex; cross-device races resolve
  through the provider.
- **AM endpoint kill-switches are session-local**, not persisted — first
  401 on PUT or PATCH flips a per-session boolean. Restarting the app
  re-probes once.

## "Which device should I…" cheat sheet

| Action | Recommended device | Why |
|---|---|---|
| Import a hosted XSPF | Phone | Always-on background polling via WorkManager; laptop sleeps |
| Create local playlists you want everywhere | Either; first sync converges them | Three-layer dedup catches name conflicts |
| Edit a playlist mirrored to AM | Either, but be patient | Track adds propagate, removes don't (PUT 401 degrade) |
| Rename a playlist mirrored to AM | Desktop or via Apple Music app directly | Mobile rename silently no-ops on AM side |
| Delete an AM-mirrored playlist | Apple Music app, then Parachord on either device | API doesn't allow programmatic AM playlist delete |
| Manage library follows (artists) | Either, for Spotify; AM is pull-only | AM has no follow API |

## Apple Music endpoint quick reference

| Endpoint | Status | Behavior on 401 |
|---|---|---|
| `GET /me/library/playlists` | Works | Throws `AppleMusicReauthRequiredException` |
| `GET /me/library/playlists/{id}/tracks` | Works | Throws `AppleMusicReauthRequiredException` |
| `POST /me/library/playlists` (create) | Works | Throws `AppleMusicReauthRequiredException` |
| `POST /me/library/playlists/{id}/tracks` (append) | Works | Throws `AppleMusicReauthRequiredException` |
| `PUT /me/library/playlists/{id}/tracks` (replace) | 401 in practice | Flip `amPutUnsupportedForSession`, degrade to POST-append. **Removals stop propagating.** |
| `PATCH /me/library/playlists/{id}` (rename) | 401 in practice | Flip `amPatchUnsupportedForSession`, silent no-op. Load-bearing — must not throw. |
| `DELETE /me/library/playlists/{id}` | 401 in practice | Return `DeleteResult.Unsupported(401)`. Caller surfaces toast. |
| `GET /me/library/{songs,albums,artists}` | Works | Throws `AppleMusicReauthRequiredException` |
| `POST /me/library?ids[songs|albums]=...` (add to library) | Works | Throws `AppleMusicReauthRequiredException` |
| `DELETE /me/library/{songs,albums}/{id}` | Works | Per-item, log + continue on non-404 errors |
| Artist follow/unfollow | No API | No-op (AM has no follow surface) |

**Do NOT retry-on-401 on the structurally-unsupported endpoints.** A
defensive retry would walk the user through a System Settings revoke flow
for an authorization that was never broken.

# iOS Spotify (Auth + Resolution + Playback) Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or implement directly task-by-task. Design: `docs/plans/2026-06-06-ios-spotify-auth-design.md`.

**Goal:** Connect Spotify on iOS (OAuth) → Spotify search appears in resolution
(shared `SpotifyClient.searchTrack`, both platforms) → Spotify tracks play via
Connect.

**Architecture:** Lift Android's `searchSpotifyTrack` into the shared
`SpotifyClient`; both platforms call it (no `spotify.axe` on iOS). The shared
auth layer (token exchange → `SettingsStore` → `SpotifyAuthTokenProvider` +
`SpotifyTokenRefresher` → `HttpClient` 401-refresh) serves resolution + playback.

**Tech stack:** Kotlin Multiplatform, Ktor, kotlinx.serialization, SwiftUI,
ASWebAuthenticationSession, Spotify Web API.

**Exact APIs (verified):**
- `SpotifyClient.search(query, type, limit=20, market="from_token"): SpSearchResponse`
- `SpTrack(id, name, artistName, durationMs, isPlayable, album?.images[].url)`
- `getDevices(): SpDevicesResponse`; `SpDevice(id, name, isActive, isRestricted, type, volumePercent)`
- `startPlayback(SpPlaybackRequest(uris=[…]), deviceId): HttpResponse`
- `SettingsStore`: `setSpotifyTokens(access, refresh)`, `setSpotifyAccessTokenExpiresAt(epochMs)`,
  `getSpotifyAccessToken()`, `getSpotifyRefreshToken()`, `getSpotifyAccessTokenExpiresAt()`,
  `getSpotifyAccessTokenFlow()`, `clearSpotifyTokens()`
- `AuthRealm.Spotify`; `AuthCredential.BearerToken(accessToken)`;
  `AuthTokenProvider.tokenFor(realm)/invalidate(realm)`; `OAuthTokenRefresher.refresh(realm): BearerToken?`
- `createHttpClient(json, appConfig, tokenProvider, refresher, lbTokenProvider)` (IosContainer:80)
- iOS OAuth: `IosOAuthManager.authorize(OAuthConfig.spotify(clientId:)) async throws -> OAuthResult(code, codeVerifier, state)`

**Build/verify:**
- Shared tests: `./gradlew :shared:testDebugUnitTest --tests "*SpotifyClient*"`
- iOS build: `xcodebuild -project iosApp/Parachord.xcodeproj -scheme Parachord -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' build`
- Android build: `./gradlew :app:assembleDebug`
- `@iosApp/AGENTS.md` for pbxproj (4 entries/file) + bridging gotchas.

**Prerequisite (user, for end-to-end test only):** real Parachord Spotify
client_id in `iosApp/Parachord/Info.plist` `SpotifyClientID` + redirect URI
`parachord://auth/callback/spotify` registered in the Spotify dashboard.

---

## Task 1: Shared `SpotifyClient.searchTrack()` (TDD)

Move Android's `searchSpotifyTrack` body into shared, with a MockEngine test.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/SpotifyClient.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/api/SpotifyClientSearchTest.kt` (create)

**Step 1 — failing test** (ktor MockEngine, already a commonTest dep): mock a
`/v1/search?...&type=track` response with one playable track; assert
`searchTrack("…")` returns a `ResolvedSource(resolver="spotify",
spotifyUri="spotify:track:ID", spotifyId="ID", matchedTitle, matchedArtist,
matchedDurationMs, artworkUrl)`. A second test: all `isPlayable=false` → null.

**Step 2** — run, expect FAIL (no `searchTrack`).

**Step 3 — implement** (port verbatim from ResolverManager:searchSpotifyTrack):
```kotlin
suspend fun searchTrack(query: String): ResolvedSource? {
    val response = search(query = query, type = "track", limit = 5)
    val track = response.tracks?.items?.firstOrNull { it.isPlayable != false } ?: return null
    val albumArt = track.album?.images?.firstOrNull()?.url
    return ResolvedSource(
        url = "spotify:track:${track.id}", sourceType = "spotify", resolver = "spotify",
        spotifyUri = "spotify:track:${track.id}", spotifyId = track.id,
        confidence = 0.9, matchedTitle = track.name, matchedArtist = track.artistName,
        matchedDurationMs = track.durationMs, artworkUrl = albumArt,
    )
}
```
**Step 4** — tests pass. **Step 5** — commit `shared: SpotifyClient.searchTrack (shared Spotify resolution)`.

---

## Task 2: Android `resolveSpotify` → shared `searchTrack` (regression-critical)

**Files:** Modify `app/src/main/java/com/parachord/android/resolver/ResolverManager.kt`
(`searchSpotifyTrack` ~line 280 → delete; `resolveSpotify` calls `spotifyClient.searchTrack(query)`).

- Replace the `searchSpotifyTrack(query)` call in `resolveSpotify` with
  `spotifyClient.searchTrack(query)`; delete the now-dead local `searchSpotifyTrack`.
- Keep the surrounding token-guard + exception handling in `resolveSpotify`.

**Verify:** `./gradlew :app:assembleDebug` green. **Android regression check
(manual):** install, play a track with Spotify connected, confirm Spotify badge
still appears + resolves. **Commit** `android: resolveSpotify uses shared SpotifyClient.searchTrack`.

---

## Task 3: iOS token exchange + auth providers + IosContainer wiring

**Files (iosMain):** `IosSpotifyAuth.kt` (new), `SpotifySharedAuth.kt` (new —
providers), `IosContainer.kt` (wire).

- **`IosSpotifyAuth.exchangeCode(result: OAuthResult, clientId, redirectUri)`** —
  POST `https://accounts.spotify.com/api/token` (form: `grant_type=authorization_code,
  code, redirect_uri, client_id, code_verifier=result.codeVerifier`) via the
  shared `httpClient` (no auth realm — it's the token endpoint). Parse
  `{access_token, refresh_token, expires_in}`; `settingsStore.setSpotifyTokens(access, refresh)`
  + `setSpotifyAccessTokenExpiresAt(now + expires_in*1000)`.
- **`SpotifyAuthTokenProvider(settingsStore): AuthTokenProvider`** —
  `tokenFor(realm)`: if `realm == AuthRealm.Spotify` return
  `BearerToken(getSpotifyAccessToken() ?: return null)` (refresh first if
  `now >= expiresAt - 60_000` via the refresher); else null. `invalidate(.Spotify)`
  → `clearSpotifyTokens()`.
- **`SpotifyTokenRefresher(settingsStore, httpClient, clientId): OAuthTokenRefresher`** —
  `refresh(.Spotify)`: POST `/api/token` (`grant_type=refresh_token,
  refresh_token, client_id`); save new access token + expiry (+ rotated refresh
  token if returned); return `BearerToken`. Other realms → null.
- **`IosContainer`**: read `spotifyClientId` from
  `NSBundle.mainBundle.objectForInfoDictionaryKey("SpotifyClientID") as? String ?: ""`
  into `AppConfig`. Replace `NoAuthTokenProvider`/`NoOpTokenRefresher` in
  `createHttpClient(...)` with the Spotify ones (keep `lbTokenProvider`). Add
  `val spotifyClient by lazy { SpotifyClient(httpClient, spotifyAuthProvider) }`.
  Expose `suspend fun connectSpotify(code, codeVerifier)` (calls IosSpotifyAuth)
  and `suspend fun disconnectSpotify()` + `getSpotifyConnectedFlow()`.

**Verify:** `./gradlew :shared:compileKotlinIosSimulatorArm64` green. **Commit**
`ios: Spotify token exchange + auth provider/refresher + container wiring`.

---

## Task 4: iOS resolver native Spotify branch

**Files (iosMain):** `IosResolverCoordinator.kt`, `IosContainer.kt` (pass `spotifyClient`).

- Drop `"spotify"` from `STREAMING_RESOLVERS` (the `.axe` set).
- After the `.axe` fan-out, if Spotify is active + connected, add a native source:
  `spotifyClient.searchTrack("$artist $title")` → its `ResolvedSource` (re-scored
  via `scoreConfidence(title, artist, matched...)` like the others) into the list
  before `selectRanked`.
- Gate on token presence (skip if not connected) to avoid 401 spam.

**Verify:** compile green; on sim, `resolveSources` for a known track returns a
`spotify@…` entry once a token is present (Dev card or log). **Commit**
`ios: IosResolverCoordinator native Spotify branch (shared searchTrack)`.

---

## Task 5: Settings "Connect Spotify" UI

**Files (Swift):** `SettingsView.swift` (+ VM), maybe `ContentView.swift`
(`IosOAuthManager` already there).

- VM: observe `container.getSpotifyConnectedFlow()` → `spotifyConnected: Bool`.
- Disconnected → "Connect Spotify" button: `Task` → `IosOAuthManager().authorize(.spotify(clientId:))`
  → `container.connectSpotify(code:codeVerifier:)`. Surface `OAuthError` inline.
- Connected → "Spotify · Connected" + Disconnect → `container.disconnectSpotify()`.
- clientId from the same Info.plist value (expose via container or read in Swift).

**Verify:** build + launch sim; the row renders connected/disconnected. (Full
OAuth round-trip needs the real client_id + registered redirect — device test.)
**Commit** `ios: Settings Connect Spotify (OAuth) row`.

---

## Task 6: `IosSpotifyConnect.play(uri:)` — Connect playback

**Files (Swift):** `ContentView.swift` (`IosSpotifyConnect`).

- Inject `container.spotifyClient`; `canPlay` → `container` token present
  (observe or check). `play(uri:) async -> Bool`:
  1. `devices = try await spotifyClient.getDevices()`. usable =
     `devices.devices.filter { !$0.isRestricted }`.
  2. if usable empty → `wakeSpotify()`; poll `getDevices()` at 300ms ×~33 (10s)
     until a non-restricted device appears (or give up → false).
  3. `pickDevice` = `usable.first { $0.isActive } ?? usable.first`.
  4. `try await spotifyClient.startPlayback(SpPlaybackRequest(uris: [uri]), deviceId: dev.id)`
     — NO transferPlayback. On 502, one retry after 1s.
  5. return true on 2xx; false on no-device / 403 / error (set `lastAction`).

**Verify:** build green. Playback is the **device test** (Premium + Spotify app).
**Commit** `ios: IosSpotifyConnect.play via Spotify Connect (getDevices→wake→startPlayback)`.

---

## Task 7: End-to-end + Info.plist + regression

- Add `SpotifyClientID` key to `Info.plist` (via `INFOPLIST_KEY_SpotifyClientID`
  build setting or a plist value — placeholder if user hasn't supplied it).
- Full iOS build green; Android `:app:assembleDebug` green.
- `./gradlew :shared:testDebugUnitTest --tests "*SpotifyClient*"` green.
- superpowers:requesting-code-review → superpowers:finishing-a-development-branch.
- **Commit** `ios: Phase 5.x — Spotify auth + resolution + playback`.

**Device validation (user):** Settings → Connect Spotify → authorize → Spotify
badges appear on tracks; tap a Spotify-resolved track (Premium + app) → plays.

---

## Out of scope (follow-ups)

- Synthetic "this device" / `Build.MODEL`-style local matching.
- Sharing the device-pick logic to commonMain.
- Spotify library/playlist sync on iOS (separate workstream).

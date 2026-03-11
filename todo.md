# Parachord Android - TODO

## Build & Infrastructure
- [ ] Verify clean build passes on CI and local machines
- [ ] Set up signing config for release builds
- [ ] Add ProGuard/R8 rules for Retrofit, Kotlinx Serialization, and Room

## ViewModels & State Management
- [ ] Create `HomeViewModel` — surface recent/recommended tracks from library
- [ ] Create `LibraryViewModel` — expose tracks, albums, playlists from `LibraryRepository`
- [ ] Create `SearchViewModel` — run queries against local DB and resolvers
- [ ] Create `NowPlayingViewModel` — observe `PlaybackService` state, expose controls
- [ ] Create `SettingsViewModel` — read/write settings via `SettingsStore`

## Screen Implementations
- [ ] **HomeScreen** — replace placeholder with real content (recent plays, quick actions)
- [ ] **LibraryScreen** — bind tabs to `LibraryViewModel`, render track/album/playlist lists
- [ ] **SearchScreen** — wire search bar to `SearchViewModel`, display results
- [ ] **NowPlayingScreen** — connect to `NowPlayingViewModel`, show real artwork/track info, working controls
- [ ] **SettingsScreen** — connect account rows to OAuth flows, persist scrobbling toggle to `SettingsStore`

## Playback
- [ ] Implement playback controls in `PlaybackService` (play, pause, skip, seek, repeat, shuffle)
- [ ] Connect `NowPlayingScreen` and `MiniPlayer` to live playback state
- [ ] Integrate `MiniPlayer` into `MainActivity` Scaffold (show above bottom nav during playback)
- [ ] Handle audio focus, becoming noisy (headphone unplug), and notification actions
- [ ] Add queue management (up next, add to queue, reorder)
- [ ] Implement additional `SourceHandler` types (resolver-backed streams)

## Authentication & Accounts
- [ ] Implement Spotify token exchange in `OAuthManager.handleSpotifyCallback()`
- [ ] Implement Last.fm session key exchange in `OAuthManager.handleLastFmCallback()`
- [ ] Store tokens securely via `SettingsStore` (consider EncryptedSharedPreferences)
- [ ] Show connected/disconnected account status in Settings
- [ ] Handle token refresh and expiry

## JS Bridge & Resolvers
- [ ] Wire `NativeBridge.storageGet()` and `storageSet()` to DataStore
- [ ] Parse and apply custom headers in `NativeBridge.fetch()`
- [ ] Create or port `resolver-loader.js` module
- [ ] Create or port `sync-engine.js` module
- [ ] Create or port `scrobble-manager.js` module
- [ ] Bundle at least one .axe resolver plugin for testing
- [ ] Add error handling for JS evaluation failures in `JsBridge`

## Library Sync
- [ ] Implement `LibrarySyncWorker.doWork()` — call JS sync-engine via JsBridge
- [ ] Schedule periodic sync via WorkManager in app startup
- [ ] Show sync status/progress in UI
- [ ] Handle sync conflicts and partial failures

## Scrobbling
- [ ] Implement scrobble submission to Last.fm API
- [ ] Track "now playing" notifications
- [ ] Respect scrobbling toggle from settings
- [ ] Queue failed scrobbles for retry

## Polish & UX
- [ ] Add loading states and error states to all screens
- [ ] Add pull-to-refresh on Library screen
- [ ] Implement swipe-to-dismiss on queue items
- [ ] Add animations/transitions between screens
- [ ] Expand `strings.xml` with all user-facing strings (for future localization)
- [ ] Design and implement empty states with actionable prompts

## Testing
- [ ] Unit tests for `LibraryRepository`
- [ ] Unit tests for `OAuthManager` token exchange
- [ ] Unit tests for `NativeBridge` DataStore integration
- [ ] Unit tests for ViewModels
- [ ] Integration tests for Room DAOs
- [ ] UI tests for navigation flow
- [ ] UI tests for playback controls

## Future / Nice-to-Have
- [ ] Android Auto support via MediaSession
- [ ] Bluetooth metadata (AVRCP)
- [ ] Chromecast / media route support
- [ ] Widget for home screen playback controls
- [ ] Import/export playlists
- [ ] Offline caching of streamed tracks
- [ ] Dark/light theme toggle in Settings (currently auto via system)

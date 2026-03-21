# Parachord Android — Development Guide

## Core Principle

**Always match the desktop app's approach.** The desktop Parachord app (Electron + React 18 + Tailwind) at https://github.com/Parachord/parachord is the source of truth. Before implementing any feature, check how the desktop app does it first. We spent a lot of time refining those approaches and should not reinvent anything.

**When told a feature should "work like desktop":** Do not make assumptions or do a partial implementation. Study the desktop implementation in detail first — read the relevant code in `app.js`, understand the data flow, the UI, the settings, and the edge cases. Ask follow-up questions if anything is unclear. The goal is feature parity, not a rough approximation. Half-implementations that miss settings, toggles, or data sources the desktop includes are worse than no implementation.

Desktop app structure: single `app.js` (~57,700 lines), `.axe` resolver plugins, `index.html` with CSS design tokens.

## Architecture Patterns (from Desktop)

### Metadata Providers — Cascading Lookup

The desktop uses a cascading provider pattern. Each provider has a specialty:

| Provider | Priority | Specialty | Auth Required |
|----------|----------|-----------|---------------|
| MusicBrainz | 0 (first) | MBIDs, structured data, **discography, tracklists** | No |
| Last.fm | 10 | Images, bios, similar artists, tags | API key only |
| Spotify | 20 (last) | Album art, preview URLs, Spotify IDs | OAuth token |

**MusicBrainz is the primary source for discography and album tracklists.** Last.fm and Spotify supplement with images and additional metadata. Results from all providers are merged — later providers fill in gaps from earlier ones.

### Resolver Scoring — Two-Tier System

Source selection uses priority-first, confidence-second sorting:

1. **Resolver priority** — user-configurable ordering (lower index = higher priority)
2. **Confidence score** — tiebreaker within same priority (0.0–1.0, default 0.9)

A Spotify result at 50% confidence beats a SoundCloud result at 95% when Spotify is ranked higher.

**Canonical resolver order (default):**
```
spotify > applemusic > bandcamp > soundcloud > localfiles > youtube
```

### Per-Resolver Volume Offsets (dB)

```
spotify:     0    applemusic:  0    localfiles:  0
soundcloud:  0    bandcamp:   -3    youtube:    -6
```

### Playback Routing

Each resolver type routes to a specific playback mechanism:

- **Spotify** → App Remote SDK (external playback, controls Spotify app on-device)
- **SoundCloud** → Fetch stream URL from API, play inline via ExoPlayer (no CORS issues on Android unlike desktop)
- **Local files** → ExoPlayer with content:// URI
- **Direct streams** → ExoPlayer with HTTP URL

The desktop plays SoundCloud natively via HTML5 Audio (not externally). The Android equivalent is ExoPlayer.

### .axe Resolver Format

Desktop resolvers are JSON-based plugin manifests with embedded JS. The Android app has a JS bridge architecture for running these, but native Kotlin resolvers are preferred for performance. The `ResolverManager` currently uses native API calls (Spotify Web API search) rather than the JS bridge.

### On-the-fly Track Resolution

Tracks from external sources (ListenBrainz weekly playlists, AI recommendations, DJ chat) arrive as metadata-only `TrackEntity` objects — they have title/artist/album but no `resolver`, `sourceUrl`, `spotifyUri`, or streaming IDs. These tracks must be resolved through the resolver pipeline before playback.

**Two-layer resolution strategy:**
1. **Background pre-resolution:** Call `TrackResolverCache.resolveInBackground(tracks)` when tracks are loaded into a list. This populates the cache with resolver results so (a) resolver badges appear in the UI and (b) playback starts instantly when the user taps a track.
2. **On-the-fly fallback:** `PlaybackController.playTrackInternal` detects tracks with no source info and calls `resolveOnTheFly()` before routing. This catches any tracks that weren't pre-resolved (e.g. user tapped before background resolution finished).

Results are cached in `TrackResolverCache.putSources()` so subsequent plays of the same track (or queue advancement) reuse the cached sources without re-resolving.

### ListenBrainz Weekly Playlists

The desktop fetches `GET /1/user/{username}/playlists/createdfor?count=100` (public, no auth token needed), filters by title containing "weekly jams" or "weekly exploration", sorts by date descending, and takes the most recent 4 of each type. Tracks are loaded lazily per playlist via `GET /1/playlist/{playlistId}`.

On Android, `WeeklyPlaylistsRepository` mirrors this pattern. The home screen shows both sections as horizontal carousels (`LazyRow`). Tapping a card navigates to `WeeklyPlaylistScreen` (ephemeral view with a Save button). The play button on each card triggers immediate playback via `HomeViewModel.playWeeklyPlaylist()`.

Ephemeral playlists use the ID format `listenbrainz-{playlistMbid}` when saved to Room.

## Design System & Theming

### Brand Colors

The desktop uses **purple as the primary accent color**, not blue.

**Light theme:**
- Background: `#ffffff` (primary), `#f9fafb` (secondary), `#f3f4f6` (inset)
- Text: `#111827` (primary), `#6b7280` (secondary), `#9ca3af` (tertiary)
- Borders: `#e5e7eb` (default), `#f3f4f6` (light)
- Accent: `#7c3aed` (purple, primary), with alpha variants
- Semantic: success `#10b981`, warning `#f59e0b`, error `#ef4444`

**Dark theme:**
- Background: `#161616` (primary), `#1e1e1e` (secondary), `#252525` (elevated)
- Text: `#f3f4f6` (primary), `#9ca3af` (secondary), `#6b7280` (tertiary)
- Accent: `#a78bfa` (lighter purple for contrast)

**Brand color (icon background):** `#273441`

### Resolver-Specific Colors

Used for badges and source indicators:
- Spotify: green (`bg-green-600/20`, `text-green-400`)
- YouTube: red (`bg-red-600/20`, `text-red-400`)
- Bandcamp: cyan (`bg-cyan-600/20`, `text-cyan-400`)

### Typography

System font stack: `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue'`

On Android, use the system default (Roboto) — this naturally matches.

### Component Styling Conventions

- **Cards/containers:** `rounded-lg` equivalent (8dp), subtle hover/press states
- **Buttons:** Primary = purple fill + white text. Secondary = gray fill. Icon buttons = rounded-full
- **Inputs:** Border with purple focus ring
- **Spacing:** 4dp increment system (8, 12, 16dp common)
- **Shadows:** Subtle — small, medium, large levels. Don't over-shadow
- **Transitions:** Smooth color/opacity transitions on interactive elements
- **Text colors:** Primary for titles, secondary (gray) for artist names/metadata, purple for interactive/active states
- **Play button:** Rounded-full, purple background, white icon

### Dark Mode

The desktop supports dark/light mode toggle. Android should follow system theme by default, matching Material 3 conventions but using the desktop's color palette — not Material You dynamic colors.

## Tech Stack (Android)

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Playback:** ExoPlayer (Media3) for local/stream, Spotify App Remote SDK for Spotify
- **Database:** Room
- **Preferences:** Jetpack DataStore
- **DI:** Hilt
- **Networking:** OkHttp + Retrofit
- **Image loading:** Coil

## Tech Stack (iOS) — Port Reference

| Android | iOS Equivalent | Notes |
|---------|---------------|-------|
| Kotlin | Swift | Use Swift concurrency (async/await, actors) instead of coroutines |
| Jetpack Compose | SwiftUI | Declarative UI; use `@Observable` (iOS 17+) or `ObservableObject` |
| Material 3 | Custom design system | No Material 3 on iOS — build custom components matching the desktop palette |
| ExoPlayer (Media3) | AVFoundation (AVPlayer) | AVPlayer handles both local files and HTTP streams |
| Spotify App Remote SDK | Spotify iOS SDK (SpotifyiOS) | Same concept — controls Spotify app externally. Uses `SPTAppRemote` |
| Room | SwiftData (iOS 17+) or Core Data | SwiftData is simpler; Core Data if supporting iOS 16 |
| Jetpack DataStore | UserDefaults / `@AppStorage` | For simple prefs use `@AppStorage`; for complex data use a JSON file in App Support |
| Hilt (DI) | Swift `Environment` / manual DI | SwiftUI `@Environment` for view-layer DI; protocol-based DI for services |
| OkHttp + Retrofit | URLSession + async/await | Use `URLSession.shared.data(from:)` with Swift concurrency. Consider Alamofire only if needed |
| Coil | AsyncImage (SwiftUI) / Kingfisher | SwiftUI has built-in `AsyncImage`; use Kingfisher or SDWebImage for caching/placeholders |
| Navigation Compose | NavigationStack (iOS 16+) | Type-safe navigation with `NavigationPath` |
| ViewModel | `@Observable` class | In SwiftUI, observable classes replace ViewModels. No `viewModelScope` — use Swift `Task` |

### iOS Playback Routing

- **Spotify** → Spotify iOS SDK (`SPTAppRemote`). Requires Spotify app installed. Auth via `SPTConfiguration` with redirect URI.
- **SoundCloud** → Fetch stream URL from API, play via `AVPlayer` with `AVPlayerItem(url:)`
- **Local files** → `AVPlayer` with local file URL from Files app or imported media
- **Direct streams** → `AVPlayer` with HTTP(S) URL
- **Apple Music** → Use MusicKit framework (`ApplicationMusicPlayer.shared`). Requires MusicKit entitlement + user authorization.

**Key AVPlayer considerations:**
- Use `AVAudioSession.sharedInstance()` — configure category `.playback` for background audio
- Register for `AVPlayerItem.Status` observation via Combine or KVO to detect playback readiness
- Handle interruptions (phone calls) via `AVAudioSession.interruptionNotification`
- For gapless playback, use `AVQueuePlayer` with preloaded `AVPlayerItem` instances
- Set `nowPlayingInfo` on `MPNowPlayingInfoCenter.default()` for lock screen controls
- Register `MPRemoteCommandCenter` handlers for play/pause/next/previous

### iOS Design & Theming Notes

SwiftUI color definitions to match the desktop palette:

```swift
// Light theme
static let backgroundPrimary = Color(hex: "#ffffff")
static let backgroundSecondary = Color(hex: "#f9fafb")
static let backgroundInset = Color(hex: "#f3f4f6")
static let textPrimary = Color(hex: "#111827")
static let textSecondary = Color(hex: "#6b7280")
static let accentPurple = Color(hex: "#7c3aed")

// Dark theme
static let darkBackgroundPrimary = Color(hex: "#161616")
static let darkBackgroundSecondary = Color(hex: "#1e1e1e")
static let darkBackgroundElevated = Color(hex: "#252525")
static let darkTextPrimary = Color(hex: "#f3f4f6")
static let darkAccentPurple = Color(hex: "#a78bfa")
```

- Use `@Environment(\.colorScheme)` to switch between light/dark palettes
- **Do NOT use system accent color or tintColor.** Always use the explicit purple values above.
- Corner radius: 8pt for cards (same as Android 8dp), `clipShape(Circle())` for circular elements
- iOS has no `dp` — use `pt` which is equivalent (1dp ≈ 1pt on standard screens)
- For the play button, use `.clipShape(Circle())` with purple background + white SF Symbol `play.fill`

### iOS-Specific Architecture Notes

- **Concurrency:** Replace Kotlin coroutines with Swift `async/await` and `Task`. Use `@MainActor` for UI updates. Use `AsyncStream` for reactive data flows (replaces Kotlin `Flow`).
- **Metadata providers:** Same cascading pattern. Each provider is a Swift protocol conformance. Use `TaskGroup` for parallel provider lookups, then merge results.
- **Resolver scoring:** Port `ResolverScoring` logic directly — it's pure math, straightforward to translate.
- **.axe resolvers:** Use `JavaScriptCore` (JSC) framework for running embedded JS. JSC is built into iOS — no WebView needed. Alternatively, port resolvers to native Swift.
- **Error handling:** Use Swift `Result` type or throwing functions. Map to domain-specific error enums.
- **Networking:** URLSession natively supports `async/await` in Swift. No need for a wrapper library in most cases.
- **Background audio:** Add `UIBackgroundModes: audio` to Info.plist. Configure `AVAudioSession` before playback begins.

## Key Files

| Area | Files |
|------|-------|
| Resolver scoring | `resolver/ResolverScoring.kt` |
| Track resolution | `resolver/ResolverManager.kt` |
| Resolver cache | `resolver/TrackResolverCache.kt` |
| Playback routing | `playback/PlaybackRouter.kt`, `PlaybackController.kt` |
| Metadata cascade | `data/metadata/MetadataService.kt`, `*Provider.kt` |
| Playback handlers | `playback/handlers/SpotifyPlaybackHandler.kt`, `SoundCloudPlaybackHandler.kt` |
| Settings/defaults | `data/store/SettingsStore.kt` |
| Theme | `ui/theme/Theme.kt` |
| Weekly playlists | `data/repository/WeeklyPlaylistsRepository.kt`, `ui/screens/playlists/WeeklyPlaylistScreen.kt`, `WeeklyPlaylistViewModel.kt` |

## Common Mistakes to Avoid

### Both Platforms

1. **Don't assume provider responsibilities.** MusicBrainz handles discography/tracklists. Last.fm handles bios/images. Check the desktop first.
2. **Don't use `sources.firstOrNull()` / `sources.first`.** Always use `ResolverScoring.selectBest()` (or its iOS equivalent) for source selection.
3. **Don't skip the resolver pipeline.** Even if you have a direct URL, route through `ResolverManager` → `ResolverScoring` → `PlaybackRouter` to maintain consistent behavior.
4. **Don't use blue as the accent color.** The brand accent is purple (`#7c3aed` light / `#a78bfa` dark).

### Android-Specific

5. **Don't double URL-decode.** Jetpack Navigation already decodes URI path segments. Use `Uri.encode()` for encoding (not `URLEncoder.encode()` which uses `+` for spaces).
6. **Don't play unresolved tracks without on-the-fly resolution.** Ephemeral tracks (weekly playlists, recommendations, DJ chat results) have no `resolver`, `sourceUrl`, or streaming IDs when first created — they're just metadata (title/artist/album). `PlaybackController.playTrackInternal` will resolve them on-the-fly if needed, but for best UX call `TrackResolverCache.resolveInBackground()` when tracks are loaded so resolver badges appear and playback starts faster.
7. **Don't use grids for horizontally-scrollable content on mobile.** Desktop's multi-column grids don't work on narrow screens — use `LazyRow` carousels with fixed-width cards instead. The home screen's Weekly Jams/Exploration sections use this pattern.
8. **Ephemeral playlists need their own screen and ViewModel.** Weekly playlists from ListenBrainz are not stored in Room — they use `WeeklyPlaylistScreen`/`WeeklyPlaylistViewModel` with a "Save" button (matching desktop's ephemeral playlist pattern). Don't try to reuse `PlaylistDetailScreen` which expects a Room-backed `PlaylistEntity`.

### iOS-Specific

6. **Don't use SwiftUI `.tint()` or `.accentColor()` globally.** These override with system blue. Set explicit purple on each interactive element.
7. **Don't forget `AVAudioSession` configuration.** Call `setCategory(.playback)` before any `AVPlayer.play()` — otherwise audio won't play when the app is backgrounded or the device is silenced.
8. **Don't use `NavigationView`.** It's deprecated. Use `NavigationStack` (iOS 16+).
9. **Don't percent-encode URLs manually.** Use `URL(string:)` which handles encoding. For query parameters, use `URLComponents` with `URLQueryItem`.
10. **Don't block the main actor.** Network calls and metadata lookups must run in a `Task` or detached context. Use `@MainActor` only for UI state updates.
11. **Don't use `Timer` for playback progress.** Use `AVPlayer.addPeriodicTimeObserver(forInterval:queue:using:)` instead — it's synchronized with the audio clock.
12. **Don't forget to handle Spotify SDK auth flow.** The iOS SDK requires handling the redirect URL in `SceneDelegate.scene(_:openURLContexts:)` or via `.onOpenURL` in SwiftUI.

## AI Provider Integration Learnings

### Timeouts & Reliability

- **AI generation needs long timeouts.** LLM responses routinely take 30–60 seconds. Create a separate `OkHttpClient` (or `URLSession` config) with 60s read timeout for AI calls. The default 10–15s timeout will fail constantly.
- **Always surface AI errors to the user.** Don't silently swallow failures — show a toast/snackbar so users know something went wrong rather than staring at an empty screen.

### JSON Parsing from AI Providers

- **AI models wrap JSON in preamble text.** Even when asked for JSON, models often add "Here's the JSON:" or markdown fences before the actual JSON. Use a brace-depth-tracking extractor (`extractJsonObject()`) to find the JSON within the response — don't assume the entire response is valid JSON.
- **Use API-level JSON mode when available.** ChatGPT supports `response_format: { "type": "json_object" }`. Gemini supports `responseMimeType: "application/json"`. These dramatically improve reliability.
- **Claude has no native JSON mode.** Use the prefill trick: set the first assistant character to `{` so Claude continues the JSON object directly. Then reconstruct: `"{" + response.content`.
- **Always use `ignoreUnknownKeys = true`** when parsing AI-generated JSON. Models may add extra fields.

### Tool Message Ordering (DJ Chat)

- **ChatGPT requires strict tool message ordering:** `[ASSISTANT(toolCalls), TOOL(result1), TOOL(result2), ...]`. Tool result messages must immediately follow the assistant message that requested them, paired by `tool_call_id`.
- **History pruning can orphan tool messages.** When trimming old messages, never split an ASSISTANT+toolCalls message from its TOOL result messages. Walk backward to keep these groups intact. Add a `sanitizeHistory()` pass to remove any orphaned TOOL messages.
- **Claude expects tool results as user messages** with `tool_result` content blocks and `tool_use_id` fields.

### AI Suggestions Caching (Stale-While-Revalidate)

- **AI suggestions must load stale cache first, then refresh.** Like the desktop, `AiRecommendationService` persists recommendations to `ai_suggestions_cache.json` so cold starts show previous results immediately instead of a 30-60s shimmer wait. The disk cache is lazy-loaded on first access to `cachedRecommendations`, and saved after each successful AI fetch.
- **No TTL — always refresh.** Unlike Fresh Drops (6h TTL) or Critical Darlings (4h TTL), AI suggestions always fetch fresh results but display stale cache during the wait. This matches desktop's pattern where cached albums/artists stay visible while `loading: true`.
- **`AiAlbumSuggestion` and `AiArtistSuggestion` are `@Serializable`.** Required for disk cache persistence.

### Prompt Engineering for Music Recommendations

- **AI models recommend genre names as track titles** if not explicitly told otherwise. Prompts must say "recommend real, specific songs by real artists — not genre names or descriptions."
- **Always use the user's configured AI provider** (from settings), not a hardcoded default. Check `settingsStore.selectedChatPlugin`.
- **Including listening history context** (recently played tracks, top artists) significantly improves recommendation quality. Make this opt-in with a user toggle for privacy.

### Key AI Files

| Area | Files |
|------|-------|
| Recommendation generation | `ai/AiRecommendationService.kt` |
| DJ chat orchestration | `ai/AiChatService.kt` |
| Provider interface | `ai/AiChatProvider.kt` |
| ChatGPT provider | `ai/providers/ChatGptProvider.kt` |
| Claude provider | `ai/providers/ClaudeProvider.kt` |
| Gemini provider | `ai/providers/GeminiProvider.kt` |
| DJ tool schemas | `ai/tools/DjToolDefinitions.kt` |
| Tool execution | `ai/tools/DjToolExecutor.kt` |
| Listening history context | `ai/ChatContextProvider.kt` |
| Recommendations UI | `ui/screens/discover/RecommendationsScreen.kt`, `RecommendationsViewModel.kt` |

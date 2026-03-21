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
| Playback routing | `playback/PlaybackRouter.kt`, `PlaybackController.kt` |
| Metadata cascade | `data/metadata/MetadataService.kt`, `*Provider.kt` |
| Playback handlers | `playback/handlers/SpotifyPlaybackHandler.kt`, `SoundCloudPlaybackHandler.kt` |
| Settings/defaults | `data/store/SettingsStore.kt` |
| Theme | `ui/theme/Theme.kt` |

## Common Mistakes to Avoid

### Both Platforms

1. **Don't assume provider responsibilities.** MusicBrainz handles discography/tracklists. Last.fm handles bios/images. Check the desktop first.
2. **Don't use `sources.firstOrNull()` / `sources.first`.** Always use `ResolverScoring.selectBest()` (or its iOS equivalent) for source selection.
3. **Don't skip the resolver pipeline.** Even if you have a direct URL, route through `ResolverManager` → `ResolverScoring` → `PlaybackRouter` to maintain consistent behavior.
4. **Don't use blue as the accent color.** The brand accent is purple (`#7c3aed` light / `#a78bfa` dark).

### Android-Specific

5. **Don't double URL-decode.** Jetpack Navigation already decodes URI path segments. Use `Uri.encode()` for encoding (not `URLEncoder.encode()` which uses `+` for spaces).

### iOS-Specific

6. **Don't use SwiftUI `.tint()` or `.accentColor()` globally.** These override with system blue. Set explicit purple on each interactive element.
7. **Don't forget `AVAudioSession` configuration.** Call `setCategory(.playback)` before any `AVPlayer.play()` — otherwise audio won't play when the app is backgrounded or the device is silenced.
8. **Don't use `NavigationView`.** It's deprecated. Use `NavigationStack` (iOS 16+).
9. **Don't percent-encode URLs manually.** Use `URL(string:)` which handles encoding. For query parameters, use `URLComponents` with `URLQueryItem`.
10. **Don't block the main actor.** Network calls and metadata lookups must run in a `Task` or detached context. Use `@MainActor` only for UI state updates.
11. **Don't use `Timer` for playback progress.** Use `AVPlayer.addPeriodicTimeObserver(forInterval:queue:using:)` instead — it's synchronized with the audio clock.
12. **Don't forget to handle Spotify SDK auth flow.** The iOS SDK requires handling the redirect URL in `SceneDelegate.scene(_:openURLContexts:)` or via `.onOpenURL` in SwiftUI.

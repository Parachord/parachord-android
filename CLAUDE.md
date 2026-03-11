# Parachord Android — Development Guide

## Core Principle

**Always match the desktop app's approach.** The desktop Parachord app (Electron + React 18 + Tailwind) at https://github.com/Parachord/parachord is the source of truth. Before implementing any feature, check how the desktop app does it first. We spent a lot of time refining those approaches and should not reinvent anything.

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

1. **Don't assume provider responsibilities.** MusicBrainz handles discography/tracklists. Last.fm handles bios/images. Check the desktop first.
2. **Don't use `sources.firstOrNull()`.** Always use `ResolverScoring.selectBest()` for source selection.
3. **Don't double URL-decode.** Jetpack Navigation already decodes URI path segments. Use `Uri.encode()` for encoding (not `URLEncoder.encode()` which uses `+` for spaces).
4. **Don't skip the resolver pipeline.** Even if you have a direct URL, route through `ResolverManager` → `ResolverScoring` → `PlaybackRouter` to maintain consistent behavior.
5. **Don't use blue as the accent color.** The brand accent is purple (`#7c3aed` light / `#a78bfa` dark).

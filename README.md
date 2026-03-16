# Parachord for Android

A multi-source music player and discovery app for Android. Parachord resolves tracks across Spotify, Apple Music, SoundCloud, Bandcamp, YouTube, and local files — then plays them through the best available source based on user-configured priority.

This is the Android companion to the [Parachord desktop app](https://github.com/Parachord/parachord).

## Features

- **Multi-source playback** — Resolves and plays music from Spotify (App Remote), Apple Music (MusicKit), SoundCloud, local files, and direct streams via ExoPlayer
- **Resolver priority system** — Two-tier scoring (priority-first, confidence-second) lets you rank sources. A Spotify result always beats YouTube when Spotify is ranked higher, regardless of match confidence
- **Music discovery** — Recommendations (Last.fm + ListenBrainz), Fresh Drops, Pop of the Tops, Critical Darlings, and upcoming concerts
- **AI DJ (Shuffleupagus)** — Conversational music assistant powered by ChatGPT, Claude, or Gemini with tool-use for queue control, recommendations, and blocking
- **Spinoff mode** — Find similar tracks to what's playing (via Last.fm) and spin off into a radio-like session
- **Scrobbling** — Last.fm and ListenBrainz scrobble support
- **Collection sync** — Import and sync playlists from Spotify and Apple Music
- **Deep linking** — Open Spotify and Apple Music links directly in Parachord
- **Friends** — See what your Last.fm/ListenBrainz friends are listening to

## Setup

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17
- Android SDK 35

### API keys

Copy the example config and fill in your keys:

```bash
cp local.properties.example local.properties
```

| Key | Required | Get it from |
|-----|----------|-------------|
| `LASTFM_API_KEY` | Yes | https://www.last.fm/api/account/create |
| `LASTFM_SHARED_SECRET` | Yes | Same as above |
| `SPOTIFY_CLIENT_ID` | Yes | https://developer.spotify.com/dashboard |
| `SOUNDCLOUD_CLIENT_ID` | No | https://soundcloud.com/you/apps |
| `SOUNDCLOUD_CLIENT_SECRET` | No | Same as above |
| `APPLE_MUSIC_DEVELOPER_TOKEN` | No | Apple Developer Account (MusicKit) |

### Build

```bash
# Debug build (for development, requires ADB install)
./gradlew assembleDebug

# Release build (sideload-safe, signed with debug key)
./gradlew assembleRelease
```

The release build is signed with the debug keystore by default so it can be distributed without a release keystore. CI uses this for artifact builds.

## Architecture

The app mirrors the [desktop Parachord app's](https://github.com/Parachord/parachord) architecture, adapted for Android idioms.

### Tech stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Playback | Media3 / ExoPlayer, Spotify App Remote SDK, MusicKit JS bridge |
| Database | Room (11 migrations) |
| DI | Hilt |
| Networking | OkHttp + Retrofit |
| Images | Coil |
| Preferences | DataStore |

### Project structure

```
app/src/main/java/com/parachord/android/
├── ai/              # AI DJ service (ChatGPT, Claude, Gemini) and tool definitions
├── auth/            # OAuth flows (Spotify, Last.fm, Apple Music)
├── bridge/          # JS bridge for .axe resolver plugins
├── data/
│   ├── api/         # Retrofit API clients (Spotify, Last.fm, MusicBrainz, ListenBrainz)
│   ├── db/          # Room database, entities, DAOs, migrations
│   ├── metadata/    # Cascading metadata providers
│   ├── repository/  # Data access layer
│   ├── scanner/     # Local media file scanner
│   └── store/       # DataStore preferences
├── deeplink/        # Deep link handling for Spotify/Apple Music URLs
├── playback/
│   ├── handlers/    # Per-source playback handlers
│   ├── PlaybackController.kt   # High-level playback API
│   ├── PlaybackService.kt      # MediaSessionService (foreground service)
│   └── QueueManager.kt         # Queue logic (mirrors desktop)
├── resolver/        # Track resolution pipeline and scoring
├── sync/            # Collection sync with external services
├── ui/
│   ├── components/  # Shared composables (MiniPlayer, TrackRow, etc.)
│   ├── navigation/  # Routes and NavHost
│   ├── screens/     # Feature screens
│   └── theme/       # Parachord color scheme and typography
└── widget/          # Home screen mini player widget
```

### Key patterns

**Metadata providers** cascade in priority order — MusicBrainz (discography, tracklists), Last.fm (images, bios, tags), Spotify (album art, IDs). Results are merged; later providers fill gaps from earlier ones.

**Resolver scoring** uses the desktop's two-tier system: resolver priority first (user-configurable), then match confidence (0.0-1.0) as a tiebreaker within the same priority level.

**Playback routing** maps each resolver to a playback mechanism:
- Spotify → App Remote SDK (external, controls Spotify on-device)
- Apple Music → MusicKit JS bridge (WebView)
- SoundCloud / local files / direct streams → ExoPlayer

### Default resolver order

```
spotify > applemusic > bandcamp > soundcloud > localfiles > youtube
```

## CI

GitHub Actions builds a release APK on every push/PR to `main`, and supports manual dispatch from the Actions tab. Artifacts are retained for 14 days.

Required repository secrets: `LASTFM_API_KEY`, `LASTFM_SHARED_SECRET`, `SPOTIFY_CLIENT_ID`, `SOUNDCLOUD_CLIENT_ID`, `SOUNDCLOUD_CLIENT_SECRET`, `APPLE_MUSIC_DEVELOPER_TOKEN`.

## Requirements

- Android 8.0+ (API 26)
- Spotify app installed for Spotify playback (App Remote SDK)

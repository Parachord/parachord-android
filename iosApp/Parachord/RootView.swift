import SwiftUI

/// App shell (phase 5.0). The real ContentView — a TabView whose first
/// tab is the first production screen (Settings), with the phase 1–4
/// platform-actual smoke tests preserved behind a "Dev" tab so they
/// stay verifiable while the rest of the screens get built.
///
/// As real screens land (Library, Now Playing, Search, Playlists…) they
/// become tabs here and the Dev tab eventually retires.
struct ContentView: View {
    /// Single app-wide playback engine, shared by the Now Playing tab
    /// and the persistent mini player.
    @State private var playback = AppPlayback()

    var body: some View {
        TabView {
            NowPlayingView(playback: playback)
                .tabItem {
                    Label("Playing", systemImage: "play.circle")
                }

            SearchView()
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }

            DevSmokeTestView()
                .tabItem {
                    Label("Dev", systemImage: "hammer")
                }
        }
        // Mini player floats above the tab bar on every tab whenever
        // something is queued.
        .safeAreaInset(edge: .bottom) {
            MiniPlayer(playback: playback)
        }
    }
}

#Preview {
    ContentView()
}

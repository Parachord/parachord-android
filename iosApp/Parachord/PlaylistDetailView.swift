import SwiftUI
import Shared

// MARK: - Playlist detail (phase 5.4)
//
// Loads + shows the tracks of a single ListenBrainz weekly playlist via
// the shared WeeklyPlaylistsRepository. Browse-only for now: these are
// metadata-only LB tracks (no resolver / streaming IDs), so playback
// waits on the iOS resolver pipeline. Establishes the NavigationStack
// push pattern (Discover row → detail) the rest of the app reuses.

@MainActor
@Observable
final class PlaylistDetailViewModel {

    private let container = IosContainer.companion.shared

    let playlistId: String
    let title: String

    var tracks: [IosPlaylistTrack] = []
    var isLoading = false
    var loaded = false

    init(playlistId: String, title: String) {
        self.playlistId = playlistId
        self.title = title
    }

    func load() async {
        guard !loaded else { return }
        isLoading = true
        tracks = (try? await container.loadWeeklyPlaylistTracks(playlistId: playlistId)) ?? []
        isLoading = false
        loaded = true
    }
}

struct PlaylistDetailView: View {
    @State private var model: PlaylistDetailViewModel

    init(playlistId: String, title: String) {
        _model = State(initialValue: PlaylistDetailViewModel(playlistId: playlistId, title: title))
    }

    var body: some View {
        Group {
            if model.isLoading && !model.loaded {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if model.tracks.isEmpty {
                ContentUnavailableView(
                    "No tracks",
                    systemImage: "music.note.list",
                    description: Text("This playlist came back empty.")
                )
            } else {
                List {
                    ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                        HStack(spacing: 12) {
                            Text("\(index + 1)")
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(width: 28, alignment: .trailing)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(track.title)
                                    .font(.body)
                                    .lineLimit(1)
                                Text(albumLine(track))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private func albumLine(_ track: IosPlaylistTrack) -> String {
        if let album = track.album, !album.isEmpty {
            return "\(track.artist) · \(album)"
        }
        return track.artist
    }
}

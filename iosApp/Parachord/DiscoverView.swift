import SwiftUI
import Shared

// MARK: - Discover ViewModel (phase 5.3)
//
// First repository-backed content screen. Surfaces the user's
// ListenBrainz Weekly Jams + Weekly Exploration via the SHARED
// WeeklyPlaylistsRepository, keyed off the LB username they set in
// Settings. No auth — the createdfor endpoint is public.

@MainActor
@Observable
final class DiscoverViewModel {

    private let container = IosContainer.companion.shared

    var jams: [IosWeeklyEntry] = []
    var exploration: [IosWeeklyEntry] = []
    var isLoading = false
    var loaded = false

    func load(forceRefresh: Bool = false) async {
        isLoading = true
        let entries = (try? await container.loadWeeklyPlaylists(forceRefresh: forceRefresh)) ?? []
        jams = entries.filter { $0.kind == "Weekly Jams" }
        exploration = entries.filter { $0.kind == "Weekly Exploration" }
        isLoading = false
        loaded = true
    }
}

// MARK: - Discover screen

struct DiscoverView: View {
    @State private var model = DiscoverViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if model.isLoading && !model.loaded {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if model.jams.isEmpty && model.exploration.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("Discover")
        }
        .task {
            if !model.loaded { await model.load(forceRefresh: true) }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No weekly playlists", systemImage: "sparkles")
        } description: {
            Text("Set your ListenBrainz username in Settings to see your Weekly Jams and Weekly Exploration.")
        }
    }

    private var list: some View {
        List {
            if !model.jams.isEmpty {
                Section("Weekly Jams") {
                    ForEach(model.jams, id: \.id) { entry in
                        weeklyRow(entry)
                    }
                }
            }
            if !model.exploration.isEmpty {
                Section("Weekly Exploration") {
                    ForEach(model.exploration, id: \.id) { entry in
                        weeklyRow(entry)
                    }
                }
            }
        }
        .refreshable { await model.load(forceRefresh: true) }
    }

    private func weeklyRow(_ entry: IosWeeklyEntry) -> some View {
        NavigationLink {
            PlaylistDetailView(
                playlistId: entry.id,
                title: "\(entry.weekLabel) · \(entry.kind)"
            )
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.weekLabel)
                    .font(.body.weight(.medium))
                if !entry.summary.isEmpty {
                    Text(entry.summary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            .padding(.vertical, 2)
        }
    }
}

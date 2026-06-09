import SwiftUI
import Shared

// MARK: - Search ViewModel (phase 5.1)
//
// Second production screen. Uses the SHARED MusicBrainz client through
// `IosContainer` — the first screen wired to the production Ktor
// HttpClient (the one with User-Agent injection + shared plugins, not
// the smoke-test's minimal client). Debounced search-as-you-type;
// results come back as flat Swift DTOs the container projects.

@MainActor
@Observable
final class SearchViewModel {

    private let container = IosContainer.companion.shared

    var query: String = ""
    var artists: [IosSearchArtist] = []
    var releases: [IosSearchRelease] = []
    var isSearching = false
    var hasSearched = false

    private var searchTask: Task<Void, Never>?

    /// Debounced search. Cancels the in-flight request when the query
    /// changes so we don't render stale results, waits 350ms after the
    /// last keystroke before hitting the network (MusicBrainz is
    /// 1 req/sec; no point firing on every character).
    func onQueryChange(_ newValue: String) {
        query = newValue
        searchTask?.cancel()
        let trimmed = newValue.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            artists = []
            releases = []
            hasSearched = false
            isSearching = false
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            if Task.isCancelled { return }
            await runSearch(trimmed)
        }
    }

    private func runSearch(_ query: String) async {
        isSearching = true
        let results = try? await container.search(query: query, limit: 8)
        if Task.isCancelled { return }
        artists = results?.artists ?? []
        releases = results?.releases ?? []
        hasSearched = true
        isSearching = false
    }
}

// MARK: - Search screen

struct SearchView: View {
    @State private var model = SearchViewModel()
    var onMenu: () -> Void = {}

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                PCTopBar(title: "Search", leading: .menu, onLeading: onMenu)
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundStyle(PC.fg3)
                    TextField("Artists & releases (MusicBrainz)", text: Binding(
                        get: { model.query }, set: { model.onQueryChange($0) }))
                        .font(.system(size: 15)).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                .padding(.horizontal, 12).padding(.vertical, 9)
                .background(PC.bgInset, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.horizontal, 16).padding(.bottom, 6)
            List {
                if !model.artists.isEmpty {
                    Section("Artists") {
                        ForEach(model.artists, id: \.id) { artist in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(artist.name)
                                    .font(.body)
                                if let d = artist.disambiguation, !d.isEmpty {
                                    Text(d)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if !model.releases.isEmpty {
                    Section("Releases") {
                        ForEach(model.releases, id: \.id) { release in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(release.title)
                                    .font(.body)
                                HStack(spacing: 4) {
                                    Text(release.artist)
                                    if let y = release.year {
                                        Text("· \(y)")
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if model.hasSearched
                    && model.artists.isEmpty
                    && model.releases.isEmpty
                    && !model.isSearching {
                    ContentUnavailableView.search(text: model.query)
                }
            }
            .overlay {
                if model.isSearching && model.artists.isEmpty && model.releases.isEmpty {
                    ProgressView()
                }
            }
            }
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

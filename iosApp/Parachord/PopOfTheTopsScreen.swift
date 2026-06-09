import SwiftUI
import Shared

// MARK: - Pop of the Tops (Phase 4 — Albums + Songs tabs, desktop parity)
//
// Apple Music (iTunes RSS) top ALBUMS (default tab) + top SONGS via the shared
// ChartsRepository. Album rows navigate straight to the AlbumScreen; song rows
// play, with a long-press "Go to Album/Artist". Per-row visibility resolution
// on songs keeps playback instant without bulk-searching Spotify (rate-safe).

@MainActor
@Observable
final class PopOfTheTopsModel {
    private let container = IosContainer.companion.shared
    var albums: [ChartAlbum] = []
    var songs: [ChartSong] = []
    var songEntities: [Track] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        albums = (try? await container.loadPopOfTheTopsAlbums(countryCode: "us")) ?? []
        let s = (try? await container.loadPopOfTheTops(countryCode: "us")) ?? []
        songs = s
        songEntities = s.map { Self.makeTrack($0) }
        isLoading = false
        loaded = true
    }

    func resolveVisible(_ song: ChartSong, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: song.artist, title: song.title, album: song.album),
            order: index
        )
    }

    private static func makeTrack(_ s: ChartSong) -> Track {
        Track(
            id: s.id, title: s.title, artist: s.artist,
            album: s.album, albumId: nil, duration: nil, artworkUrl: s.artworkUrl,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: s.spotifyId, appleMusicId: nil, recordingMbid: s.mbid,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

private enum PopTab: String, CaseIterable { case albums = "Albums", songs = "Songs" }

struct PopOfTheTopsScreen: View {
    @State private var model = PopOfTheTopsModel()
    @State private var tab: PopTab = .albums
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(PopTab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16).padding(.vertical, 8)

            if model.isLoading && !model.loaded {
                Spacer(); ProgressView(); Spacer()
            } else {
                switch tab {
                case .albums: albumsList
                case .songs:  songsList
                }
            }
        }
        .navigationTitle("Pop of the Tops")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    // ── Albums tab: each row → the album page ──────────────────────────
    private var albumsList: some View {
        List {
            ForEach(Array(model.albums.enumerated()), id: \.element.id) { index, album in
                NavigationLink {
                    AlbumScreen(title: album.title, artist: album.artist)
                } label: {
                    HStack(spacing: 12) {
                        rank(index)
                        artwork(album.artworkUrl, seed: album.title + album.artist)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(album.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                            Text(album.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .listStyle(.plain)
    }

    // ── Songs tab: tap plays; long-press → album/artist ────────────────
    private var songsList: some View {
        List {
            ForEach(Array(model.songs.enumerated()), id: \.element.id) { index, song in
                Button {
                    coordinator.setQueue(model.songEntities, startIndex: index)
                } label: {
                    HStack(spacing: 12) {
                        rank(index)
                        artwork(song.artworkUrl, seed: song.title + song.artist)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == model.songEntities[index].id ? PC.accent : PC.fg1)
                                .lineLimit(1)
                            Text(song.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(song, index: index) }
                .pcTrackContextMenu(
                    model.songEntities[index], coordinator: coordinator,
                    onGoToArtist: { navArtist = song.artist },
                    onGoToAlbum: song.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: song.artist) } }
                )
            }
        }
        .listStyle(.plain)
    }

    private func rank(_ index: Int) -> some View {
        Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
            .foregroundStyle(PC.fg3).frame(width: 24, alignment: .center)
    }

    @ViewBuilder
    private func artwork(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            AsyncImage(url: u) { img in
                img.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                PCArtwork(name: seed, size: 44, radius: 6)
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else {
            PCArtwork(name: seed, size: 44, radius: 6)
        }
    }
}

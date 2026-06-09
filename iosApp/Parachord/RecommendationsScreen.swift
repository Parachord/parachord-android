import SwiftUI
import Shared

// MARK: - For You / Recommendations (Phase 4)
//
// Personalized recs from the shared RecommendationsRepository (ListenBrainz
// history → MetadataService), wired through IosContainer. Recommended tracks
// (play + context menu) and recommended artists (→ ArtistScreen).

@MainActor @Observable
final class RecommendationsModel {
    private let container = IosContainer.companion.shared
    var tracks: [RecommendedTrack] = []
    var entities: [Track] = []
    var artists: [RecommendedArtist] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        let t = (try? await container.loadRecommendedTracks()) ?? []
        tracks = t
        entities = t.map { Self.makeTrack($0) }
        artists = (try? await container.loadRecommendedArtists()) ?? []
        isLoading = false
        loaded = true
    }

    func resolveVisible(_ t: RecommendedTrack, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: index)
    }

    private static func makeTrack(_ t: RecommendedTrack) -> Track {
        Track(
            id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
            album: t.album, albumId: nil, duration: t.duration, artworkUrl: t.artworkUrl,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: nil, appleMusicId: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

struct RecommendationsScreen: View {
    @State private var model = RecommendationsModel()
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if model.isLoading && !model.loaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                }
                if !model.artists.isEmpty {
                    Text("Artists For You").pcSectionHeader()
                        .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 8)
                    artistsRow
                }
                if !model.tracks.isEmpty {
                    Text("Songs For You").pcSectionHeader()
                        .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
                    tracksList
                }
                if model.loaded && model.tracks.isEmpty && model.artists.isEmpty {
                    Text("No recommendations yet — connect ListenBrainz and listen to a few tracks.")
                        .font(.system(size: 14)).foregroundStyle(PC.fg2)
                        .multilineTextAlignment(.center).padding(40).frame(maxWidth: .infinity)
                }
            }
            .padding(.bottom, 130)
        }
        .navigationTitle("For You")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    private var artistsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(Array(model.artists.enumerated()), id: \.offset) { _, artist in
                    NavigationLink { ArtistScreen(artistName: artist.name) } label: {
                        VStack(spacing: 8) {
                            artistArt(artist)
                            Text(artist.name).font(.system(size: 13, weight: .medium))
                                .foregroundStyle(PC.fg1).lineLimit(1).frame(width: 96)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
    }

    @ViewBuilder
    private func artistArt(_ a: RecommendedArtist) -> some View {
        if let url = a.imageUrl, let u = URL(string: url) {
            AsyncImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: a.name, size: 96, radius: 48) }
                .frame(width: 96, height: 96).clipShape(Circle())
        } else {
            PCArtwork(name: a.name, size: 96, radius: 48)
        }
    }

    private var tracksList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.tracks.enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.entities, startIndex: index) } label: {
                    HStack(spacing: 12) {
                        artworkSmall(t)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == model.entities[index].id ? PC.accent : PC.fg1)
                                .lineLimit(1)
                            Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(t, index: index) }
                .pcTrackContextMenu(
                    model.entities[index], coordinator: coordinator,
                    onGoToArtist: { navArtist = t.artist },
                    onGoToAlbum: t.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: t.artist) } }
                )
            }
        }
    }

    @ViewBuilder
    private func artworkSmall(_ t: RecommendedTrack) -> some View {
        if let url = t.artworkUrl, let u = URL(string: url) {
            AsyncImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: t.title + t.artist, size: 44, radius: 6) }
                .frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else {
            PCArtwork(name: t.title + t.artist, size: 44, radius: 6)
        }
    }
}

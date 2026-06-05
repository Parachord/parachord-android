import Foundation
import Shared

// MARK: - Track resolver cache (playback-loop phase, two-layer resolution)
//
// CLAUDE.md "On-the-fly Track Resolution" is a two-layer strategy:
//   1. Background pre-resolution — when a tracklist loads, resolve the whole
//      visible list in the background and cache the ranked sources. This makes
//      resolver badges appear AND playback start instantly from cache on tap.
//   2. On-the-fly fallback — resolve at play time only on a cache miss.
//
// This is layer 1. The coordinator (playTrack) reads `cached(...)` first and
// falls back to `container.resolveSources` on a miss.
//
// Throttled because every resolve runs through ONE JavaScriptCore context
// (all `.axe` plugins share it). A 25-track playlist must not fire 25×N
// concurrent JSC resolves — `cap` bounds in-flight work.

struct ResolveRequest: Hashable {
    let artist: String
    let title: String
    let album: String?

    var key: String {
        "\(artist.lowercased())\u{1}\(title.lowercased())\u{1}\((album ?? "").lowercased())"
    }
}

@MainActor
@Observable
final class IosTrackResolverCache {
    static let shared = IosTrackResolverCache()

    private let container = IosContainer.companion.shared

    /// key → ranked sources (best first). Observed by badge rows so they
    /// render as each track resolves.
    private(set) var cache: [String: [ResolvedSource]] = [:]
    private var inFlight: Set<String> = []

    /// Max concurrent resolves through the single JSC context.
    private let cap = 3

    func cached(artist: String, title: String, album: String?) -> [ResolvedSource]? {
        cache[ResolveRequest(artist: artist, title: title, album: album).key]
    }

    /// Resolve a list in the background with bounded concurrency, skipping
    /// already-cached / in-flight entries. Updates `cache` reactively so badge
    /// rows re-render as each track lands.
    func resolveInBackground(_ requests: [ResolveRequest]) {
        let pending = requests.filter { cache[$0.key] == nil && !inFlight.contains($0.key) }
        guard !pending.isEmpty else { return }
        pending.forEach { inFlight.insert($0.key) }

        Task { @MainActor in
            var iterator = pending.makeIterator()
            await withTaskGroup(of: Void.self) { group in
                func addNext() -> Bool {
                    guard let req = iterator.next() else { return false }
                    group.addTask { @MainActor in
                        let ranked = (try? await self.container.resolveSources(
                            artist: req.artist, title: req.title, album: req.album)) ?? []
                        self.cache[req.key] = ranked
                        self.inFlight.remove(req.key)
                    }
                    return true
                }
                var active = 0
                for _ in 0..<cap where addNext() { active += 1 }
                while active > 0 {
                    await group.next()
                    active -= 1
                    if addNext() { active += 1 }
                }
            }
        }
    }
}

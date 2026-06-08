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

    /// Pending work, drained LOWEST-`order`-first so a tracklist resolves
    /// top-down (the row at the top of the page resolves before the row below
    /// it), never bottom-up. Mirrors desktop ResolutionScheduler's
    /// visibility-index priority.
    private var queue: [(order: Int, req: ResolveRequest)] = []

    /// In-flight worker count, bounded by `cap`. This is a SHARED pool across
    /// every submission — submitting tracks one-per-row must not exceed the cap
    /// (the previous per-call task group let N rows run N concurrent resolves).
    private var activeWorkers = 0

    /// Max concurrent resolves through the single JSC context (`.axe` plugins
    /// all share it) + the shared Spotify gate. Matches desktop's 4-ish pool.
    private let cap = 3

    func cached(artist: String, title: String, album: String?) -> [ResolvedSource]? {
        cache[ResolveRequest(artist: artist, title: title, album: album).key]
    }

    /// Submit ONE track for resolution with a priority `order` (its row index).
    /// Lower `order` resolves first → top-down. Skips already-cached, in-flight,
    /// or already-queued keys. Call from a row's `.onAppear` so only visible
    /// rows resolve, in the order they sit on the page.
    func resolve(_ req: ResolveRequest, order: Int) {
        let key = req.key
        guard cache[key] == nil,
              !inFlight.contains(key),
              !queue.contains(where: { $0.req.key == key })
        else { return }
        queue.append((order, req))
        pump()
    }

    /// Batch convenience — submits each request with `order` = its array index,
    /// so a list passed top-to-bottom resolves top-down.
    func resolveInBackground(_ requests: [ResolveRequest]) {
        for (i, req) in requests.enumerated() { resolve(req, order: i) }
    }

    /// Fill free worker slots from the front of the priority queue (lowest
    /// `order` first). Re-invoked by each worker on completion.
    private func pump() {
        while activeWorkers < cap, !queue.isEmpty {
            queue.sort { $0.order < $1.order }           // top-of-page first
            let item = queue.removeFirst()
            let key = item.req.key
            if cache[key] != nil || inFlight.contains(key) { continue }
            inFlight.insert(key)
            activeWorkers += 1
            Task { @MainActor in
                let ranked = (try? await self.container.resolveSources(
                    artist: item.req.artist, title: item.req.title, album: item.req.album)) ?? []
                self.cache[key] = ranked
                self.inFlight.remove(key)
                self.activeWorkers -= 1
                self.pump()
            }
        }
    }
}

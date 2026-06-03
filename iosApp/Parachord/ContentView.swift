import SwiftUI
import Shared
import JavaScriptCore

// MARK: - JSC Polyfills (phase 4.2)
//
// Swift companion to `IosJsRuntime`. The Kotlin runtime stands up the
// JSContext and owns the lifecycle; this struct attaches the
// console / fetch / storage polyfills via the JSC subscript API that
// Kotlin/Native's bindings don't expose
// (`ctx["__nativeLog"] = { level, msg in ... }`).
//
// Polyfills route to Swift closures the call site provides, so a smoke-
// test harness can capture log lines into a SwiftUI @State buffer
// without the polyfill itself owning any UI concerns.

enum JsPolyfills {

    /// Attach a `console.log` / info / warn / error / debug polyfill
    /// that routes through `__nativeLog(level, message)` to the
    /// caller-provided `onLog` closure. Fires synchronously inside the
    /// next `evaluateScript`; if `onLog` updates SwiftUI state it MUST
    /// dispatch to the main actor itself.
    static func attachConsole(
        to context: JSContext,
        onLog: @escaping (_ level: String, _ message: String) -> Void
    ) {
        let nativeLog: @convention(block) (String, String) -> Void = { level, message in
            onLog(level, message)
        }
        context.setObject(
            nativeLog,
            forKeyedSubscript: "__nativeLog" as NSString
        )
        context.evaluateScript("""
            (function() {
                var levels = ['log', 'info', 'warn', 'error', 'debug'];
                if (typeof console === 'undefined') { console = {}; }
                levels.forEach(function(level) {
                    console[level] = function() {
                        var args = Array.prototype.slice.call(arguments);
                        __nativeLog(level, args.map(String).join(' '));
                    };
                });
            })();
        """)
    }
}

/// Phase 2 + 2.5 "Hello-shared" smoke test.
///
/// Phase 2 (the static, pure-math section): proves the
/// `Shared.framework` static binary links, ObjC interop exposes
/// Kotlin classes + companion constants, and top-level Kotlin
/// functions are reachable from Swift (`scoreConfidence` is
/// declared in `ResolverModels.kt` so it bridges as
/// `ResolverModelsKt.scoreConfidence`).
///
/// Phase 2.5 (the async section): proves Ktor's Darwin engine
/// fires real HTTP, kotlinx.serialization parses live JSON on iOS,
/// and Kotlin `suspend fun` bridges to Swift `async throws` end-to-
/// end. Calls MusicBrainz because it's the only first-party API
/// surface in the shared module that needs no auth.
struct ContentView: View {
    private let smokeTest = IosSmokeTest()
    private let threshold = ResolverScoring.companion.MIN_CONFIDENCE_THRESHOLD

    private let correctMatch = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Imagine",
        matchedArtist: "John Lennon"
    )

    private let wrongArtist = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Imagine",
        matchedArtist: "A Perfect Circle"
    )

    private let noMatch = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Smells Like Teen Spirit",
        matchedArtist: "Nirvana"
    )

    @State private var artistQuery = "Radiohead"
    @State private var artists: [SmokeTestArtist] = []
    @State private var loading = false
    @State private var error: String?

    @State private var mosaicURL: URL?
    @State private var mosaicLoading = false
    @State private var mosaicError: String?

    @State private var jsResults: [(label: String, value: String)] = []
    @State private var jsError: String?
    @State private var jsConsoleOutput: [String] = []
    @State private var jsPolyfillsAttached = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                resolverScoringCard
                jsRuntimeCard
                mosaicSmokeTestCard
                ktorSmokeTestCard
            }
            .padding()
        }
    }

    // MARK: - Phase 2 (sync)

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Parachord")
                .font(.largeTitle.weight(.semibold))
            Text("Hello, shared 👋")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
    }

    private var resolverScoringCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Resolver scoring (shared module, sync)")
                .font(.headline)

            row(label: "MIN_CONFIDENCE_THRESHOLD", value: threshold)
            Divider()
            row(label: "Imagine / Lennon → Imagine / Lennon", value: correctMatch)
            row(label: "Imagine / Lennon → Imagine / A Perfect Circle", value: wrongArtist)
            row(label: "Imagine / Lennon → Teen Spirit / Nirvana", value: noMatch)
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var expectedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Expected:")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text("• Threshold: 0.6")
                .font(.caption)
            Text("• Both-match: 0.95")
                .font(.caption)
            Text("• Single-axis match: 0.50 (filtered by threshold)")
                .font(.caption)
            Text("• Neither: 0.50 (filtered by threshold)")
                .font(.caption)
        }
        .foregroundStyle(.secondary)
    }

    // MARK: - Phase 2.5 (async / Ktor)

    private var ktorSmokeTestCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MusicBrainz search (Ktor Darwin, async)")
                .font(.headline)

            HStack(spacing: 8) {
                TextField("Artist name", text: $artistQuery)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()

                Button {
                    Task { await runSearch() }
                } label: {
                    if loading {
                        ProgressView()
                    } else {
                        Text("Search")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(loading || artistQuery.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if let error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            ForEach(artists, id: \.id) { artist in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(artist.name)
                            .font(.body.weight(.medium))
                        if let topTag = artist.topTag {
                            Text(topTag)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    if let disambig = artist.disambiguation {
                        Text(disambig)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Auto-run for the prefilled query on first appear. Subsequent
        // queries come from the Search button. This is meant as nice
        // UX *and* makes the async path observable end-to-end without
        // needing to script a synthetic tap in the screenshot harness.
        .task {
            if artists.isEmpty && error == nil && !loading {
                await runSearch()
            }
        }
    }

    private func runSearch() async {
        loading = true
        error = nil
        artists = []
        do {
            let results = try await smokeTest.searchArtists(
                query: artistQuery,
                limit: 5
            )
            artists = results
        } catch {
            self.error = "Error: \(error.localizedDescription)"
        }
        loading = false
    }

    // MARK: - Phase 4 (CoreGraphics mosaic)

    private var mosaicSmokeTestCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Playlist mosaic (CoreGraphics, async)")
                .font(.headline)

            Text(
                "Downloads 4 images via Ktor, composites them 2×2 at 600×600 " +
                "via UIGraphicsImageRenderer, writes JPEG to Application Support. " +
                "Returns the file:// URL that ImageEnrichmentService would store."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                Button {
                    Task { await runMosaic() }
                } label: {
                    if mosaicLoading {
                        ProgressView()
                    } else {
                        Text("Compose Mosaic")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(mosaicLoading)

                if let mosaicURL {
                    Text(mosaicURL.lastPathComponent)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            if let mosaicError {
                Text(mosaicError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if let mosaicURL,
               let uiImage = UIImage(contentsOfFile: mosaicURL.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Auto-run on first appear so the screenshot harness can
        // observe the full pipeline (download + composite + write +
        // render) without a synthetic tap. Subsequent runs come from
        // the button.
        .task {
            if mosaicURL == nil && mosaicError == nil && !mosaicLoading {
                await runMosaic()
            }
        }
    }

    // MARK: - Phase 4.1 (JavaScriptCore)

    private var jsRuntimeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("JavaScriptCore runtime (Phase 4.1)")
                .font(.headline)

            Text(
                "Stands up a JSContext via shared `IosJsRuntime`, then runs " +
                "synchronous + async JS through `evaluate(script)`. Polyfills " +
                "for fetch/console/storage land in a follow-up — the binding " +
                "gap for JS↔Kotlin callbacks is documented in IosJsRuntime.kt."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            ForEach(jsResults, id: \.label) { result in
                HStack(alignment: .top) {
                    Text(result.label)
                        .font(.callout.monospaced())
                    Spacer()
                    Text(result.value)
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.green)
                }
            }

            if let jsError {
                Text(jsError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if !jsConsoleOutput.isEmpty {
                Divider()
                Text("console.log polyfill (phase 4.2)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(jsConsoleOutput.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.caption.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(8)
                .background(Color.black.opacity(0.06))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task {
            if jsResults.isEmpty && jsError == nil {
                await runJsEvaluations()
            }
        }
    }

    private func runJsEvaluations() async {
        do {
            // Phase 4.2: stand up the runtime so `nativeContext` is
            // populated, then attach Swift-side polyfills BEFORE
            // running any script that uses `console.log`.
            try await smokeTest.ensureJsRuntimeReady()
            if !jsPolyfillsAttached,
               let ctx = smokeTest.jsRuntime.nativeContext {
                JsPolyfills.attachConsole(to: ctx) { level, message in
                    // JS callback fires synchronously during
                    // `evaluateScript`, on whatever thread Kotlin's
                    // coroutine ended up on — bounce to main before
                    // mutating SwiftUI state.
                    Task { @MainActor in
                        jsConsoleOutput.append("[\(level)] \(message)")
                    }
                }
                jsPolyfillsAttached = true
            }

            // Drive four cases:
            //   1. Synchronous arithmetic — runtime alive
            //   2. JSON.stringify — built-in globals work
            //   3. Synchronous IIFE returning a string — the pattern
            //      .axe plugins use when they're not awaiting native
            //      callbacks
            //   4. console.log via the polyfill — JS calls
            //      `__nativeLog(level, message)`, which the Swift
            //      block fires, which appends a line to the
            //      `jsConsoleOutput` panel below. This is the JS→Swift
            //      callback path the rest of phase 4 (fetch, storage,
            //      MBID resolution) all sit on top of.
            let cases: [(label: String, script: String)] = [
                ("2 + 40", "2 + 40"),
                ("JSON.stringify({a:1,b:[2,3]})", "JSON.stringify({a:1,b:[2,3]})"),
                ("IIFE returning string", "(function() { var x = 21; return JSON.stringify({status: 'ok', value: x * 2}); })()"),
                (
                    "console.log → polyfill",
                    """
                    (function() {
                        console.log('hello from JS', 42, {plug: 'in'});
                        console.warn('warn-level message');
                        console.error('error-level message');
                        return 'logged ' + 3 + ' lines';
                    })()
                    """
                ),
            ]
            var collected: [(label: String, value: String)] = []
            for (label, script) in cases {
                let result = try await smokeTest.evaluateJs(script: script)
                collected.append((label: label, value: result ?? "<null>"))
            }
            jsResults = collected
        } catch {
            jsError = "Error: \(error.localizedDescription)"
        }
    }

    private func runMosaic() async {
        mosaicLoading = true
        mosaicError = nil
        // picsum.photos has stable, deterministic 300×300 endpoints that
        // serve real photo content — perfect for proving the composite
        // pipeline without depending on MB/CAA's release-group MBIDs being
        // exactly right.
        let urls = [
            "https://picsum.photos/id/1/300/300",
            "https://picsum.photos/id/100/300/300",
            "https://picsum.photos/id/200/300/300",
            "https://picsum.photos/id/300/300/300",
        ]
        // Unique ID per tap so the SwiftUI image cache doesn't show a
        // stale result when re-running.
        let id = "smoke-\(Int(Date().timeIntervalSince1970))"
        do {
            if let pathString = try await smokeTest.composeMosaic(
                playlistId: id,
                urls: urls
            ) {
                // Kotlin returns a "file://<absolute path>" string; parse it.
                mosaicURL = URL(string: pathString)
                if mosaicURL == nil {
                    mosaicError = "Couldn't parse returned path: \(pathString)"
                }
            } else {
                mosaicError = "Mosaic returned null — likely a download or encode failure."
            }
        } catch {
            mosaicError = "Error: \(error.localizedDescription)"
        }
        mosaicLoading = false
    }

    // MARK: - Helpers

    private func row(label: String, value: Double) -> some View {
        HStack {
            Text(label)
                .font(.callout)
            Spacer()
            Text(String(format: "%.2f", value))
                .font(.callout.monospacedDigit())
                .foregroundStyle(value >= threshold ? .green : .red)
        }
    }
}

#Preview {
    ContentView()
}

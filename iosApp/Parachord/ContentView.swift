import SwiftUI
import Shared

/// Phase 2 "Hello-shared" smoke test.
///
/// Exercises the three things that have to work before any other iOS UI
/// can be wired up to the KMP module:
///   1. The `Shared.framework` static binary links
///   2. ObjC interop exposes Kotlin classes and companion constants
///   3. Top-level Kotlin functions (`scoreConfidence` lives in
///      `ResolverModels.kt`, so it becomes `ResolverModelsKt.scoreConfidence`
///      in Swift)
///
/// All three calls below are pure math — no HTTP, no auth, no async
/// bridging. The async-bridging proof (calling a `suspend` Ktor API) is
/// the next milestone after this view renders correctly.
struct ContentView: View {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            header

            VStack(alignment: .leading, spacing: 12) {
                Text("Resolver scoring (from shared module)")
                    .font(.headline)

                row(label: "MIN_CONFIDENCE_THRESHOLD", value: threshold)
                Divider()
                row(label: "Imagine / Lennon → Imagine / Lennon", value: correctMatch.doubleValue)
                row(label: "Imagine / Lennon → Imagine / A Perfect Circle", value: wrongArtist.doubleValue)
                row(label: "Imagine / Lennon → Teen Spirit / Nirvana", value: noMatch.doubleValue)
            }
            .padding()
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 12))

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

            Spacer()
        }
        .padding()
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Parachord")
                .font(.largeTitle.weight(.semibold))
            Text("Hello, shared 👋")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
    }

    private func row(label: String, value: Double) -> some View {
        HStack {
            Text(label)
                .font(.callout)
            Spacer()
            Text(String(format: "%.2f", value))
                .font(.callout.monospacedDigit())
                .foregroundStyle(value >= threshold.doubleValue ? .green : .red)
        }
    }
}

#Preview {
    ContentView()
}

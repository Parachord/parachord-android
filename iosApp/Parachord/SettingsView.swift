import SwiftUI
import Shared

// MARK: - Settings ViewModel (phase 5.0)
//
// First real screen wired to the shared module. Observes the shared
// `SettingsStore`'s Kotlin Flows via `FlowWatcher` and republishes them
// as @Observable state; writes go straight back to the store's suspend
// setters. The store is the SAME class Android uses (KMP commonMain) —
// backed on iOS by NSUserDefaults (KvStore) + Keychain (SecureTokenStore).
//
// This establishes the ViewModel-shim pattern every subsequent screen
// follows: hold a shared service, bridge its Flows to @Observable, call
// its suspend functions from button taps.

@MainActor
@Observable
final class SettingsViewModel {

    private let store = IosContainer.companion.shared.settingsStore
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subscriptions: [Cancellable] = []

    // Mirrored settings state.
    var themeMode: String = "system"
    var scrobblingEnabled: Bool = false
    var lastFmUsername: String = ""
    var listenBrainzUsername: String = ""

    func start() {
        guard subscriptions.isEmpty else { return }
        // Each `watch` collects a shared Flow; the closure receives the
        // emission as `Any?` (generics erase across the bridge) and casts.
        subscriptions.append(
            watcher.watch(flow: store.themeMode) { [weak self] value in
                if let v = value as? String { self?.themeMode = v }
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.scrobblingEnabled) { [weak self] value in
                if let v = value as? Bool { self?.scrobblingEnabled = v }
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.getLastFmUsernameFlow()) { [weak self] value in
                self?.lastFmUsername = (value as? String) ?? ""
            }
        )
        subscriptions.append(
            watcher.watch(flow: store.getListenBrainzUsernameFlow()) { [weak self] value in
                self?.listenBrainzUsername = (value as? String) ?? ""
            }
        )
    }

    func stop() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
    }

    // Writes — fire-and-forget into the shared store's suspend setters.

    func setTheme(_ mode: String) {
        themeMode = mode  // optimistic; the flow will confirm
        Task { try? await store.setThemeMode(mode: mode) }
    }

    func setScrobbling(_ enabled: Bool) {
        scrobblingEnabled = enabled
        Task { try? await store.setScrobblingEnabled(enabled: enabled) }
    }

    func setLastFmUsername(_ username: String) {
        Task { try? await store.setLastFmUsername(username: username) }
    }

    func setListenBrainzUsername(_ username: String) {
        Task { try? await store.setListenBrainzUsername(username: username) }
    }
}

// MARK: - Settings screen

struct SettingsView: View {
    @State private var model = SettingsViewModel()

    private static let themeOptions = ["system", "light", "dark"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Appearance") {
                    Picker("Theme", selection: themeBinding) {
                        ForEach(Self.themeOptions, id: \.self) { option in
                            Text(option.capitalized).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Scrobbling") {
                    Toggle("Enable scrobbling", isOn: scrobblingBinding)
                }

                Section("Last.fm") {
                    TextField("Username", text: lastFmBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("ListenBrainz") {
                    TextField("Username", text: listenBrainzBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section {
                    Text(
                        "These read/write the SHARED SettingsStore (KMP " +
                        "commonMain) — the same class Android uses. Backed " +
                        "on iOS by NSUserDefaults + Keychain. Changes persist " +
                        "across launches."
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }

    // Bindings translate SwiftUI control changes into shared-store writes.

    private var themeBinding: Binding<String> {
        Binding(get: { model.themeMode }, set: { model.setTheme($0) })
    }
    private var scrobblingBinding: Binding<Bool> {
        Binding(get: { model.scrobblingEnabled }, set: { model.setScrobbling($0) })
    }
    private var lastFmBinding: Binding<String> {
        Binding(get: { model.lastFmUsername }, set: { model.setLastFmUsername($0) })
    }
    private var listenBrainzBinding: Binding<String> {
        Binding(get: { model.listenBrainzUsername }, set: { model.setListenBrainzUsername($0) })
    }
}

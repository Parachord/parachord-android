package com.parachord.shared.ios

import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.IosSecureTokenStore
import com.parachord.shared.store.KvStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container for the iOS app shell (phase 5.0).
 *
 * The full Koin graph (sharedModule + a platform module supplying
 * AuthTokenProvider / OAuthTokenRefresher / AppConfig) is deferred —
 * the first real SwiftUI screens (Settings) only need [SettingsStore],
 * which depends on nothing but the two iOS storage actuals that already
 * exist (KvStore via NSUserDefaults, SecureTokenStore via Keychain).
 *
 * Constructing it here rather than reaching for Koin keeps the Swift
 * side a single `IosContainer.shared` lookup and avoids dragging in the
 * auth-token plumbing before any screen needs it. When network-backed
 * repositories come online, this grows to host the Ktor client + DAOs
 * (or gets replaced by an `initKoin()` once the auth module lands).
 */
class IosContainer private constructor() {

    companion object {
        /** Single app-wide instance — Swift reads `IosContainer.companion.shared`. */
        val shared: IosContainer by lazy { IosContainer() }
    }

    /** App-wide coroutine scope for fire-and-forget settings writes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val settingsStore: SettingsStore by lazy {
        SettingsStore(
            secureStore = IosSecureTokenStore(),
            kv = KvStoreFactory.create(),
        )
    }
}

/**
 * Bridges a Kotlin [Flow] to a Swift callback. Swift can't `collect` a
 * suspend Flow directly, so this launches the collection on a coroutine
 * scope and forwards each emission to `onEach`. Returns a [Cancellable]
 * the Swift side stores and calls in `onDisappear` / deinit to stop the
 * collection.
 *
 * The flow's element type erases to `Any?` across the ObjC bridge
 * (generics aren't preserved), so the Swift `onEach` closure receives
 * `Any?` and casts to the concrete type. Flow is covariant, so any
 * `Flow<T>` is assignable to the `Flow<Any?>` parameter.
 */
class FlowWatcher(private val scope: CoroutineScope) {

    fun watch(flow: Flow<Any?>, onEach: (Any?) -> Unit): Cancellable {
        val job = scope.launch {
            flow.collect { value -> onEach(value) }
        }
        return Cancellable { job.cancel() }
    }
}

/** Opaque cancellation handle for a [FlowWatcher.watch] subscription. */
class Cancellable(private val onCancel: () -> Unit) {
    fun cancel() = onCancel()
}

package com.parachord.shared.store

import com.russhwolf.settings.KeychainSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * iOS implementation of [SecureTokenStore] backed by the Keychain via
 * `multiplatform-settings`' [KeychainSettings].
 *
 * The Keychain entries are scoped under the service name
 * `"com.parachord.tokens"` — distinct from the Android
 * `parachord_secure_tokens` SharedPreferences file (Keychain has its own
 * isolation by service+account). Items are protected by the system passcode
 * and inherit the standard Keychain accessibility (default
 * `kSecAttrAccessibleAfterFirstUnlock` via the wrapper).
 *
 * The Keychain has no built-in change observer (unlike
 * `SharedPreferences.OnSharedPreferenceChangeListener` on Android), so
 * [observe] re-emits via a [MutableSharedFlow] that [set] / [remove] write
 * to. Subscribers see the current value immediately (via `onStart`), then
 * any subsequent updates filtered to their key.
 *
 * security: C4 — encrypt tokens at rest.
 */
class IosSecureTokenStore(
    serviceName: String = "com.parachord.tokens",
) : SecureTokenStore {

    private val settings = KeychainSettings(service = serviceName)

    /**
     * Per-key change signal. Replay = 0 — `observe` uses [onStart] to
     * deliver the current value on subscription, separate from the change
     * stream. Buffer = 64 to absorb bursts (e.g. token refresh writing
     * access + refresh + expiry simultaneously).
     */
    private val changes = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun get(key: String): String? = settings.getStringOrNull(key)

    override fun set(key: String, value: String) {
        settings.putString(key, value)
        changes.tryEmit(key)
    }

    override fun remove(key: String) {
        settings.remove(key)
        changes.tryEmit(key)
    }

    override fun contains(key: String): Boolean = settings.hasKey(key)

    override fun observe(key: String): Flow<String?> =
        changes
            .filter { it == key }
            .map { get(key) }
            .onStart { emit(get(key)) }
            .distinctUntilChanged()
}

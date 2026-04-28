package com.parachord.shared.store

import kotlinx.coroutines.flow.Flow

/**
 * Encrypted key-value store for OAuth tokens and BYO API keys.
 *
 * Phase 9B Stage 3 promoted this to a KMP interface so [SettingsStore]
 * (now in `shared/commonMain`) doesn't bind to the Android-only
 * EncryptedSharedPreferences implementation. Each platform provides its
 * own backing:
 *
 * - **Android** ([com.parachord.shared.store.AndroidSecureTokenStore]) —
 *   `EncryptedSharedPreferences` with a master key from Android Keystore
 *   (AES-256-GCM). Falls back to plain `SharedPreferences` if Keystore
 *   init fails (some rooted / unlocked-bootloader devices).
 * - **iOS** (future) — `Security.framework` Keychain, scoped to the app
 *   bundle.
 *
 * security: C4 — encrypt tokens at rest.
 */
interface SecureTokenStore {

    fun get(key: String): String?

    fun set(key: String, value: String)

    fun remove(key: String)

    fun contains(key: String): Boolean

    /**
     * Observe a key reactively. Emits the current value immediately, then
     * re-emits whenever the key is updated via [set] or [remove].
     */
    fun observe(key: String): Flow<String?>
}

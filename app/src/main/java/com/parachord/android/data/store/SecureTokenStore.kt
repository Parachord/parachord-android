package com.parachord.android.data.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val TAG = "SecureTokenStore"
private const val FILENAME = "parachord_secure_tokens"

/**
 * Encrypted key-value store for OAuth tokens and BYO API keys.
 *
 * Uses [EncryptedSharedPreferences] backed by Android Keystore AES-256-GCM.
 * If Keystore initialization fails (some devices, rooted, unlocked bootloaders),
 * falls back to an unencrypted SharedPreferences with a logged warning — better
 * to have functioning auth than to block the user entirely.
 *
 * security: C4 — encrypt tokens at rest
 */
class SecureTokenStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            FILENAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences init failed — falling back to unencrypted", e)
        context.getSharedPreferences("${FILENAME}_fallback", Context.MODE_PRIVATE)
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * Observe a key reactively. Emits the current value immediately, then
     * re-emits whenever the key is updated via [set] or [remove].
     * Uses SharedPreferences' native change listener.
     */
    fun observe(key: String): Flow<String?> = callbackFlow {
        // Emit current value
        trySend(get(key))

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) {
                trySend(get(key))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()
}

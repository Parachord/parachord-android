package com.parachord.shared.store

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
 * Android implementation of [SecureTokenStore] backed by Jetpack
 * `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore).
 *
 * If Keystore initialization fails (some devices, rooted, unlocked
 * bootloaders), falls back to an unencrypted `SharedPreferences` with a
 * logged warning — better to have functioning auth than to block the
 * user entirely.
 *
 * security: C4 — encrypt tokens at rest.
 */
class AndroidSecureTokenStore(context: Context) : SecureTokenStore {

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

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun observe(key: String): Flow<String?> = callbackFlow {
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

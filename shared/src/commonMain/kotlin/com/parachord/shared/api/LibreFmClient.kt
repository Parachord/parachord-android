package com.parachord.shared.api

import com.parachord.shared.api.auth.lastFmSignature
import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Libre.fm authentication — same Last.fm-compatible 2.0 protocol against
 * libre.fm/2.0/ with the conventional all-zeros api_key + shared secret.
 * Mirrors the Android LibreFmScrobbler.authenticate + desktop librefm-scrobbler.js.
 *
 * Only the connect (auth.getMobileSession) flow lives here; actual scrobbling
 * is handled by the scrobble path when it lands on a given platform. KMP-shared
 * so both Android and iOS connect through the same code (signing via the shared
 * [lastFmSignature], which uses KMP-native MD5).
 */
class LibreFmClient(private val httpClient: HttpClient) {
    private companion object {
        const val API_URL = "https://libre.fm/2.0/"
        const val API_KEY = "00000000000000000000000000000000"
        const val SHARED_SECRET = "00000000000000000000000000000000"
        const val TAG = "LibreFmClient"
    }

    /** Returns the session key on success, or null on bad credentials / error. */
    suspend fun authenticate(username: String, password: String): String? {
        return try {
            val params = mapOf(
                "method" to "auth.getMobileSession",
                "username" to username,
                "password" to password,
                "api_key" to API_KEY,
            )
            // api_sig is computed over the unsigned params (lastFmSignature drops
            // format + api_sig itself); format/api_sig are added to the body after.
            val sig = lastFmSignature(params, SHARED_SECRET)
            val body = (params + mapOf("api_sig" to sig, "format" to "json"))
                .entries.joinToString("&") { (k, v) -> "${k.encodeURLParameter()}=${v.encodeURLParameter()}" }
            val text = httpClient.post(API_URL) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }.bodyAsText()
            val json = Json.parseToJsonElement(text).jsonObject
            if (json.containsKey("error")) {
                Log.e(TAG, "Libre.fm auth error: $text")
                return null
            }
            json["session"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            Log.e(TAG, "Libre.fm auth failed: ${e.message}")
            null
        }
    }
}

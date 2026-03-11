package com.parachord.android.resolver

import com.parachord.android.bridge.JsBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages .axe resolver plugins by delegating to the JavaScript resolver-loader
 * running inside the JS bridge. Resolvers are loaded unchanged from the desktop app.
 */
@Singleton
class ResolverManager @Inject constructor(
    private val jsBridge: JsBridge,
    private val json: Json,
) {
    private val _resolvers = MutableStateFlow<List<ResolverInfo>>(emptyList())
    val resolvers: StateFlow<List<ResolverInfo>> = _resolvers.asStateFlow()

    /** Load all .axe resolver files from assets and register them in the JS runtime. */
    suspend fun loadResolvers() {
        // The JS resolver-loader handles parsing .axe files
        val result = jsBridge.evaluate("JSON.stringify(resolverLoader.getResolvers())")
        if (result != null && result != "null") {
            val cleaned = result.trim('"').replace("\\\"", "\"")
            _resolvers.value = json.decodeFromString<List<ResolverInfo>>(cleaned)
        }
    }

    /** Resolve a track query through all available resolvers. */
    suspend fun resolve(query: String): List<ResolvedSource> {
        val escaped = query.replace("'", "\\'")
        val result = jsBridge.evaluate(
            "JSON.stringify(await resolverLoader.resolve('$escaped'))"
        )
        if (result != null && result != "null") {
            val cleaned = result.trim('"').replace("\\\"", "\"")
            return json.decodeFromString<List<ResolvedSource>>(cleaned)
        }
        return emptyList()
    }
}

@Serializable
data class ResolverInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ResolvedSource(
    val url: String,
    val sourceType: String,
    val resolver: String,
    val quality: Int? = null,
    val headers: Map<String, String>? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    /** Match confidence from the resolver (0.0–1.0). Desktop defaults to 0.9 for successful resolves. */
    val confidence: Double? = null,
    /** Whether the resolver explicitly couldn't match this track. */
    val noMatch: Boolean = false,
)

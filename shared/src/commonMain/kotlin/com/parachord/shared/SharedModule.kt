package com.parachord.shared

/**
 * Parachord shared module — KMP business logic shared between Android and iOS.
 *
 * Phase 0: Module structure established.
 * Phase 1: Core data models (Track, Album, Artist, Playlist, Friend, etc.),
 *          AI models, metadata models, playback context, resource wrapper.
 * Phase 2+: API clients, repositories, resolver pipeline, plugin system.
 */
object SharedModule {
    const val VERSION = "0.2.0"

    /** All shared model packages for reference. */
    val MODEL_PACKAGES = listOf(
        "com.parachord.shared.model",
        "com.parachord.shared.ai",
        "com.parachord.shared.metadata",
        "com.parachord.shared.playback",
    )
}

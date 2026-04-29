@file:Suppress("unused")
package com.parachord.android.ai

/**
 * Source-compat typealiases. The real implementation moved to
 * `com.parachord.shared.ai.ChatContextProvider`. PlaybackStateHolder is
 * Android-only (it carries Android-specific Track/queue types from
 * `com.parachord.android.playback.PlaybackState`), so the chat context
 * receives a small [ChatPlaybackSnapshot] via a `getPlaybackSnapshot`
 * suspend lambda wired in `AndroidModule`.
 */
typealias ChatContextProvider = com.parachord.shared.ai.ChatContextProvider
typealias ChatPlaybackSnapshot = com.parachord.shared.ai.ChatPlaybackSnapshot

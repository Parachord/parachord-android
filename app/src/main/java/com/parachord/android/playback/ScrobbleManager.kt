package com.parachord.android.playback

/**
 * Bridge shim — ScrobbleManager now lives in shared/commonMain so iOS uses the
 * same threshold + dispatch logic (#193). The two platform-coupled concerns
 * (playback-state flow, JS-plugin dispatch) are forwarded as constructor params
 * wired in AndroidModule. Preserves app-side imports (PlaybackController).
 */
typealias ScrobbleManager = com.parachord.shared.playback.scrobbler.ScrobbleManager

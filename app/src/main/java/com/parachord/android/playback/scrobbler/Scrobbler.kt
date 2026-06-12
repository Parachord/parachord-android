package com.parachord.android.playback.scrobbler

/**
 * Bridge shim — the Scrobbler interface now lives in shared/commonMain so iOS
 * scrobbles through the same instances (#193). Preserves app-side imports.
 */
typealias Scrobbler = com.parachord.shared.playback.scrobbler.Scrobbler

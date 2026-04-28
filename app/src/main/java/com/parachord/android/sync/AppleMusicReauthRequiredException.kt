package com.parachord.android.sync

/**
 * Typealias preserving the existing `com.parachord.android.sync.AppleMusicReauthRequiredException`
 * import path while the actual class moved to shared/commonMain in Phase 9E.1.7.
 *
 * Source-compat bridge — consumers don't need to update their imports right now.
 */
typealias AppleMusicReauthRequiredException = com.parachord.shared.sync.AppleMusicReauthRequiredException

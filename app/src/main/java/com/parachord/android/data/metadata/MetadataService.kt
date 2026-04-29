@file:Suppress("unused")
package com.parachord.android.data.metadata

/**
 * Source-compat typealias. The Android wrapper class has been eliminated
 * in favor of constructing the shared [com.parachord.shared.metadata.MetadataService]
 * directly in `AndroidModule.kt` — the wrapper was a thin forwarder
 * whose only meaningful contribution was an iTunes-search artwork
 * enrichment lambda for Cover Art Archive 404s. That lambda now lives
 * inline in the Koin factory, where it has access to `AppleMusicClient`
 * directly.
 *
 * Consumers (~10 ViewModels and 4 services) keep their existing
 * `import com.parachord.android.data.metadata.MetadataService` thanks
 * to this alias.
 */
typealias MetadataService = com.parachord.shared.metadata.MetadataService

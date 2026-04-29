@file:Suppress("unused")
package com.parachord.android.data.metadata

/**
 * Source-compat typealias. The real implementation moved to
 * `shared/commonMain` so iOS can consume it. The constructor signature
 * shrunk: the prior `OAuthManager` parameter was unused (token refresh
 * goes through Ktor's `OAuthRefreshPlugin`), so it was dropped during
 * the move. Koin binding updated accordingly.
 */
typealias SpotifyProvider = com.parachord.shared.metadata.SpotifyProvider

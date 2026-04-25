package com.parachord.android.sync

import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies AppleMusicSyncProvider satisfies the [SyncProvider]
 * interface and declares its capability flags correctly. Apple Music
 * has no follow API and PUT/PATCH/DELETE all return 401 in practice
 * — the flags advertise these limitations to SyncEngine and the UI.
 *
 * If Apple's API behavior changes (e.g. they enable PUT for some
 * tokens), this test must be updated in the same commit so the intent
 * is explicit.
 */
class AppleMusicSyncProviderConformanceTest {
    private val provider: SyncProvider = AppleMusicSyncProvider(
        api = mockk(relaxed = true),
        settingsStore = mockk(relaxed = true),
    )

    @Test
    fun `id is applemusic`() {
        assertEquals("applemusic", provider.id)
    }

    @Test
    fun `displayName is human-readable Apple Music`() {
        assertEquals("Apple Music", provider.displayName)
    }

    @Test
    fun `features declare AM degradation profile`() {
        val expected = ProviderFeatures(
            snapshots = SnapshotKind.DateString,
            supportsFollow = false,
            supportsPlaylistDelete = false,
            supportsPlaylistRename = false,
            supportsTrackReplace = false,
        )
        assertEquals(expected, provider.features)
    }

    @Test
    fun `is assignable to SyncProvider`() {
        assertTrue(provider is SyncProvider)
    }
}

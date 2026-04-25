package com.parachord.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test that the shared SyncProvider types compile and carry the
 * expected shape. The full conformance test for SpotifySyncProvider's
 * features lands in P2-T3.
 */
class SyncProviderShapeTest {
    @Test
    fun `ProviderFeatures captures all five capability flags`() {
        val features = ProviderFeatures(
            snapshots = SnapshotKind.Opaque,
            supportsFollow = true,
            supportsPlaylistDelete = true,
            supportsPlaylistRename = true,
            supportsTrackReplace = true,
        )
        assertEquals(SnapshotKind.Opaque, features.snapshots)
        assertEquals(true, features.supportsFollow)
        assertEquals(true, features.supportsPlaylistDelete)
        assertEquals(true, features.supportsPlaylistRename)
        assertEquals(true, features.supportsTrackReplace)
    }

    @Test
    fun `SnapshotKind exposes Opaque, DateString, None`() {
        val all = SnapshotKind.entries
        assertEquals(3, all.size)
        assert(SnapshotKind.Opaque in all)
        assert(SnapshotKind.DateString in all)
        assert(SnapshotKind.None in all)
    }

    @Test
    fun `DeleteResult sealed hierarchy compiles`() {
        val success: DeleteResult = DeleteResult.Success
        val unsupported: DeleteResult = DeleteResult.Unsupported(401)
        val failed: DeleteResult = DeleteResult.Failed(RuntimeException("boom"))
        listOf(success, unsupported, failed).forEach { result ->
            val tag: String = when (result) {
                is DeleteResult.Success -> "ok"
                is DeleteResult.Unsupported -> "unsupported:${result.status}"
                is DeleteResult.Failed -> "failed:${result.error.message}"
            }
            assertEquals(true, tag.isNotEmpty())
        }
    }

    @Test
    fun `RemoteCreated allows null snapshotId for None providers`() {
        val withSnapshot = RemoteCreated(externalId = "abc", snapshotId = "snap-1")
        val withoutSnapshot = RemoteCreated(externalId = "xyz", snapshotId = null)
        assertEquals("abc", withSnapshot.externalId)
        assertEquals("snap-1", withSnapshot.snapshotId)
        assertEquals(null, withoutSnapshot.snapshotId)
    }
}

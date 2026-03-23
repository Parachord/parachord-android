package com.parachord.android.data.repository

import com.parachord.android.data.api.LbCreatedForPlaylist
import com.parachord.android.data.api.LbPlaylistTrack
import com.parachord.android.data.api.ListenBrainzApi
import com.parachord.android.data.store.SettingsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WeeklyPlaylistsRepositoryTest {

    private lateinit var api: ListenBrainzApi
    private lateinit var settingsStore: SettingsStore
    private lateinit var repo: WeeklyPlaylistsRepository

    @Before
    fun setup() {
        api = mockk()
        settingsStore = mockk()
        repo = WeeklyPlaylistsRepository(api, settingsStore)
    }

    @Test
    fun `returns null when no username configured`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns null
        assertNull(repo.loadWeeklyPlaylists())
    }

    @Test
    fun `filters playlists by weekly jams and weekly exploration`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns listOf(
            LbCreatedForPlaylist("1", "testuser's weekly jams 2026-03-15", "2026-03-15"),
            LbCreatedForPlaylist("2", "testuser's weekly exploration 2026-03-15", "2026-03-15"),
            LbCreatedForPlaylist("3", "some other playlist", "2026-03-15"),
        )

        val result = repo.loadWeeklyPlaylists()
        assertNotNull(result)
        assertEquals(1, result?.jams?.size)
        assertEquals(1, result?.exploration?.size)
    }

    @Test
    fun `sorts playlists by date descending and takes max 4`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns (1..6).map { i ->
            LbCreatedForPlaylist("$i", "testuser's weekly jams 2026-03-0$i", "2026-03-0$i")
        }

        val result = repo.loadWeeklyPlaylists()
        assertEquals(4, result?.jams?.size)
        // Most recent first
        assertEquals("6", result?.jams?.first()?.id)
    }

    @Test
    fun `assigns correct week labels`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns listOf(
            LbCreatedForPlaylist("1", "weekly jams week 1", "2026-03-22"),
            LbCreatedForPlaylist("2", "weekly jams week 2", "2026-03-15"),
            LbCreatedForPlaylist("3", "weekly jams week 3", "2026-03-08"),
        )

        val result = repo.loadWeeklyPlaylists()
        assertEquals("This Week", result?.jams?.get(0)?.weekLabel)
        assertEquals("Last Week", result?.jams?.get(1)?.weekLabel)
        assertEquals("2 Weeks Ago", result?.jams?.get(2)?.weekLabel)
    }

    @Test
    fun `returns cached result on non-force refresh`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns listOf(
            LbCreatedForPlaylist("1", "weekly jams", "2026-03-22"),
        )

        val first = repo.loadWeeklyPlaylists()
        // Change API response
        coEvery { api.getCreatedForPlaylists("testuser") } returns emptyList()

        val second = repo.loadWeeklyPlaylists(forceRefresh = false)
        assertEquals(first, second) // cached
    }

    @Test
    fun `force refresh bypasses cache`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns listOf(
            LbCreatedForPlaylist("1", "weekly jams", "2026-03-22"),
        )
        repo.loadWeeklyPlaylists()

        coEvery { api.getCreatedForPlaylists("testuser") } returns emptyList()

        val result = repo.loadWeeklyPlaylists(forceRefresh = true)
        assertTrue(result?.isEmpty != false) // empty result from fresh fetch
    }

    @Test
    fun `loadPlaylistCovers extracts up to 4 distinct album art URLs`() = runTest {
        coEvery { api.getPlaylistTracksRich("p1") } returns listOf(
            LbPlaylistTrack("1", "T1", "A1", albumArt = "http://img1"),
            LbPlaylistTrack("2", "T2", "A2", albumArt = "http://img1"), // duplicate
            LbPlaylistTrack("3", "T3", "A3", albumArt = "http://img2"),
            LbPlaylistTrack("4", "T4", "A4", albumArt = "http://img3"),
            LbPlaylistTrack("5", "T5", "A5", albumArt = "http://img4"),
            LbPlaylistTrack("6", "T6", "A6", albumArt = "http://img5"),
        )

        val covers = repo.loadPlaylistCovers("p1")
        assertEquals(4, covers.size)
        assertEquals(covers.distinct(), covers) // all unique
    }

    @Test
    fun `clearCache invalidates cached result`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } returns listOf(
            LbCreatedForPlaylist("1", "weekly jams", "2026-03-22"),
        )
        repo.loadWeeklyPlaylists()
        repo.clearCache()

        coEvery { api.getCreatedForPlaylists("testuser") } returns emptyList()
        val result = repo.loadWeeklyPlaylists()
        assertTrue(result?.isEmpty != false)
    }

    @Test
    fun `returns null on API error`() = runTest {
        coEvery { settingsStore.getListenBrainzUsername() } returns "testuser"
        coEvery { api.getCreatedForPlaylists("testuser") } throws RuntimeException("Network error")

        assertNull(repo.loadWeeklyPlaylists())
    }
}

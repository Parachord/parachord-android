package com.parachord.android.playback.handlers

import android.app.Application
import android.os.Build
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.SpDevice
import com.parachord.shared.api.SpotifyClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Tests for the "sticky default" helper that persists LOCAL_DEVICE_ID as the
 * preferred device after a successful local play, when no preference was set.
 *
 * Defense-in-depth companion to the pickDevice prefer-local-over-active-remote
 * fix — makes the next session immune to phantom active remotes by giving the
 * Connect API an explicit preference to honor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerStickyDefaultTest {

    private lateinit var spotifyClient: SpotifyClient
    private lateinit var settingsStore: SettingsStore
    private lateinit var oAuthManager: OAuthManager
    private lateinit var handler: SpotifyPlaybackHandler

    @Before
    fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Pixel 9a")
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")

        spotifyClient = mockk(relaxed = true)
        settingsStore = mockk(relaxed = true)
        oAuthManager = mockk(relaxed = true)
        handler = SpotifyPlaybackHandler(
            spotifyClient = spotifyClient,
            settingsStore = settingsStore,
            oAuthManager = oAuthManager,
            context = RuntimeEnvironment.getApplication(),
        )
    }

    /**
     * Local synthetic placeholder used, no preference set → preference becomes
     * LOCAL_DEVICE_ID. This is the target scenario: anonymous play succeeds,
     * sticky default fires, next session is durable.
     */
    @Test
    fun `local placeholder + null preference persists LOCAL_DEVICE_ID`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val placeholder = SpDevice(
            id = SpotifyPlaybackHandler.LOCAL_DEVICE_ID,
            name = "Google Pixel 9a",
            isActive = false,
            type = "Smartphone",
        )

        handler.stickyDefaultLocalIfUnset(placeholder)

        coVerify { settingsStore.setPreferredSpotifyDeviceId(SpotifyPlaybackHandler.LOCAL_DEVICE_ID) }
    }

    /**
     * Resolved real local smartphone (Build.MODEL match) used, no preference set
     * → preference becomes LOCAL_DEVICE_ID. After resolveLocalDevice() runs,
     * the "used" device is no longer the placeholder — it's the real device
     * Spotify registered. The helper must recognize that as local too.
     */
    @Test
    fun `resolved local smartphone + null preference persists LOCAL_DEVICE_ID`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val phone = SpDevice(
            id = "phone-real-id",
            name = "Pixel 9a",
            isActive = true,
            type = "Smartphone",
        )

        handler.stickyDefaultLocalIfUnset(phone)

        coVerify { settingsStore.setPreferredSpotifyDeviceId(SpotifyPlaybackHandler.LOCAL_DEVICE_ID) }
    }

    /**
     * Local placeholder used but preference already set to a different device
     * (e.g., user has Bedroom TV preferred) → preference unchanged. Never
     * overwrite explicit user choice.
     */
    @Test
    fun `local placeholder + existing remote preference is no-op`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "tv-id"

        val placeholder = SpDevice(
            id = SpotifyPlaybackHandler.LOCAL_DEVICE_ID,
            name = "Google Pixel 9a",
            isActive = false,
            type = "Smartphone",
        )

        handler.stickyDefaultLocalIfUnset(placeholder)

        coVerify(exactly = 0) { settingsStore.setPreferredSpotifyDeviceId(any()) }
    }

    /**
     * Remote device (TV) used → preference unchanged regardless of prior state.
     * Sticky default only fires for local devices.
     */
    @Test
    fun `remote device + null preference is no-op`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")

        handler.stickyDefaultLocalIfUnset(tv)

        coVerify(exactly = 0) { settingsStore.setPreferredSpotifyDeviceId(any()) }
    }

    /**
     * Race: by the time stickyDefaultLocalIfUnset fires, a concurrent picker
     * action has already persisted "tv-id". Even though the device the helper
     * is called with is the local placeholder (would normally trigger sticky),
     * the helper must re-read at write time and bail. The contract: the helper
     * never trusts a cached earlier read from the broader play flow; its read
     * IS the gate.
     */
    @Test
    fun `race - preference set by concurrent picker is preserved`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "tv-id"

        val placeholder = SpDevice(
            id = SpotifyPlaybackHandler.LOCAL_DEVICE_ID,
            name = "Google Pixel 9a",
            isActive = false,
            type = "Smartphone",
        )

        handler.stickyDefaultLocalIfUnset(placeholder)

        coVerify(exactly = 0) { settingsStore.setPreferredSpotifyDeviceId(any()) }
    }
}

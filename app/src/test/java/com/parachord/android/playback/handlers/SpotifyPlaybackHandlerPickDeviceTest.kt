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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpotifyPlaybackHandlerPickDeviceTest {

    private lateinit var spotifyClient: SpotifyClient
    private lateinit var settingsStore: SettingsStore
    private lateinit var oAuthManager: OAuthManager
    private lateinit var handler: SpotifyPlaybackHandler

    @Before
    fun setUp() {
        // Force Build.MODEL/MANUFACTURER to known values so the local-smartphone
        // detection has stable inputs across CI environments.
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
     * Pre-fix repro test. With no preference set, devices = [TV(active), MacBook(inactive)],
     * a synthetic local placeholder is injected. The new step-4 rule must prefer the local
     * placeholder over the already-active TV.
     */
    @Test
    fun `no preference prefers local placeholder over already-active remote`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")
        val macbook = SpDevice(id = "mac-id", name = "J's MacBook Pro", isActive = false, type = "Computer")

        val picked = handler.pickDevice(listOf(tv, macbook))

        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, picked?.id)
    }

    /**
     * When Spotify HAS registered a local smartphone matching Build.MODEL,
     * no synthetic placeholder is added but the real local smartphone should
     * still win over an already-active remote when no preference is set.
     */
    @Test
    fun `no preference prefers real local smartphone over already-active remote`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")
        val macbook = SpDevice(id = "mac-id", name = "J's MacBook Pro", isActive = false, type = "Computer")
        val phone = SpDevice(id = "phone-id", name = "Pixel 9a", isActive = false, type = "Smartphone")

        val picked = handler.pickDevice(listOf(tv, macbook, phone))

        assertEquals("phone-id", picked?.id)
    }

    /**
     * Renamed-phone case (observed in the wild: device named "J9a"). The user
     * renamed their Spotify Connect device so it no longer contains Build.MODEL
     * ("Pixel 9a") or Build.MANUFACTURER ("Google"). It's still the sole
     * smartphone in the list, so it must be treated as the local device and
     * win over an already-active phantom remote (a Bedroom TV the API still
     * flags active=true). Without the sole-smartphone fallback, routing falls
     * through to the already-active rule and silently casts to the TV.
     */
    @Test
    fun `no preference prefers sole renamed smartphone over already-active remote`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val phone = SpDevice(id = "j9a-id", name = "J9a", isActive = false, type = "Smartphone")
        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")

        val picked = handler.pickDevice(listOf(phone, tv))

        assertEquals("j9a-id", picked?.id)
    }

    /**
     * Multi-smartphone guard: when more than one smartphone is present and none
     * matches Build.MODEL, the sole-smartphone heuristic must NOT fire (we can't
     * assume which is local). Because no local smartphone is identified, the
     * synthetic "This device" placeholder is injected and routing prefers it
     * (step 4) — waking local Spotify to resolve the real device. The key
     * invariant: a phantom active TV must NOT steal playback.
     */
    @Test
    fun `multiple smartphones inject synthetic and do not route to phantom remote`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val phoneA = SpDevice(id = "a-id", name = "J9a", isActive = false, type = "Smartphone")
        val phoneB = SpDevice(id = "b-id", name = "Other Phone", isActive = false, type = "Smartphone")
        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")

        val picked = handler.pickDevice(listOf(phoneA, phoneB, tv))

        // No single identifiable local → synthetic placeholder, NOT the active TV.
        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, picked?.id)
    }

    /**
     * Explicit preference for the local placeholder still honored.
     */
    @Test
    fun `local preference still honored even when remote is active`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns SpotifyPlaybackHandler.LOCAL_DEVICE_ID

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")

        val picked = handler.pickDevice(listOf(tv))

        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, picked?.id)
    }

    /**
     * Explicit preference for a specific remote device still honored even if
     * another device is currently active.
     */
    @Test
    fun `specific preference still honored over active device`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "tv-id"

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = false, type = "TV")
        val phone = SpDevice(id = "phone-id", name = "Pixel 9a", isActive = true, type = "Smartphone")

        val picked = handler.pickDevice(listOf(tv, phone))

        assertEquals("tv-id", picked?.id)
    }

    /**
     * Single real device path still auto-selects (step 3 unchanged) — when only
     * one real device exists alongside the synthetic local placeholder, take it.
     * Note: when the only real device is a non-Smartphone, `withLocal` ends up
     * with 2 entries (the real device + the synthetic local placeholder), so
     * `realDevices.size == 1` matches and the real device is picked.
     */
    @Test
    fun `single real device still auto-selected`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns null

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")

        val picked = handler.pickDevice(listOf(tv))

        assertEquals("tv-id", picked?.id)
    }

    /**
     * Stale preference (preferred device gone from list) gets cleared, falls
     * through to the new step-4 local-preference rule, picks local placeholder.
     */
    @Test
    fun `stale preference cleared then defaults to local`() = runTest {
        coEvery { settingsStore.getPreferredSpotifyDeviceId() } returns "missing-device"

        val tv = SpDevice(id = "tv-id", name = "Bedroom TV", isActive = true, type = "TV")
        val macbook = SpDevice(id = "mac-id", name = "J's MacBook Pro", isActive = false, type = "Computer")

        val picked = handler.pickDevice(listOf(tv, macbook))

        coVerify { settingsStore.clearPreferredSpotifyDeviceId() }
        assertEquals(SpotifyPlaybackHandler.LOCAL_DEVICE_ID, picked?.id)
    }
}

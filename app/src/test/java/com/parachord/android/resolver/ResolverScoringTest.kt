package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ResolverScoringTest {

    private lateinit var settingsStore: SettingsStore
    private lateinit var scoring: ResolverScoring

    @Before
    fun setup() {
        settingsStore = mockk()
        scoring = ResolverScoring(settingsStore)

        // Default: canonical order, all resolvers active
        coEvery { settingsStore.getResolverOrder() } returns
            listOf("spotify", "applemusic", "bandcamp", "soundcloud", "localfiles", "youtube")
        coEvery { settingsStore.getActiveResolvers() } returns emptyList()
    }

    // -- selectBest --

    @Test
    fun `selectBest returns null for empty sources`() = runTest {
        assertNull(scoring.selectBest(emptyList()))
    }

    @Test
    fun `selectBest returns single source directly`() = runTest {
        val source = makeSource("spotify", confidence = 0.5)
        assertEquals(source, scoring.selectBest(listOf(source)))
    }

    @Test
    fun `selectBest picks higher priority resolver over higher confidence`() = runTest {
        val spotify = makeSource("spotify", confidence = 0.5)
        val soundcloud = makeSource("soundcloud", confidence = 0.95)
        val result = scoring.selectBest(listOf(soundcloud, spotify))
        assertEquals("spotify", result?.resolver)
    }

    @Test
    fun `selectBest uses confidence as tiebreaker within same priority`() = runTest {
        // Both at same priority by putting them adjacent in custom order
        coEvery { settingsStore.getResolverOrder() } returns listOf("spotify", "soundcloud")

        val low = makeSource("spotify", confidence = 0.5)
        val high = makeSource("spotify", confidence = 0.95)
        val result = scoring.selectBest(listOf(low, high))
        assertEquals(0.95, result?.confidence)
    }

    @Test
    fun `selectBest preferred resolver always wins`() = runTest {
        val spotify = makeSource("spotify", confidence = 0.95)
        val soundcloud = makeSource("soundcloud", confidence = 0.5)
        val result = scoring.selectBest(listOf(spotify, soundcloud), preferredResolver = "soundcloud")
        assertEquals("soundcloud", result?.resolver)
    }

    @Test
    fun `selectBest filters by active resolvers`() = runTest {
        coEvery { settingsStore.getActiveResolvers() } returns listOf("soundcloud")

        val spotify = makeSource("spotify", confidence = 0.95)
        val soundcloud = makeSource("soundcloud", confidence = 0.5)
        val result = scoring.selectBest(listOf(spotify, soundcloud))
        assertEquals("soundcloud", result?.resolver)
    }

    @Test
    fun `selectBest returns null when no active resolvers match`() = runTest {
        coEvery { settingsStore.getActiveResolvers() } returns listOf("bandcamp")

        val spotify = makeSource("spotify", confidence = 0.95)
        val result = scoring.selectBest(listOf(spotify))
        assertNull(result)
    }

    @Test
    fun `selectBest unknown resolver gets lowest priority`() = runTest {
        val spotify = makeSource("spotify", confidence = 0.5)
        val unknown = makeSource("newresolver", confidence = 1.0)
        val result = scoring.selectBest(listOf(unknown, spotify))
        assertEquals("spotify", result?.resolver)
    }

    @Test
    fun `selectBest null confidence treated as zero`() = runTest {
        val withConf = makeSource("spotify", confidence = 0.1)
        val noConf = makeSource("spotify", confidence = null)
        val result = scoring.selectBest(listOf(noConf, withConf))
        assertEquals(0.1, result?.confidence)
    }

    // -- insertInCanonicalOrder --

    @Test
    fun `insertInCanonicalOrder adds new resolver at correct position`() {
        val order = listOf("spotify", "soundcloud")
        val result = scoring.insertInCanonicalOrder(order, "applemusic")
        assertEquals(listOf("spotify", "applemusic", "soundcloud"), result)
    }

    @Test
    fun `insertInCanonicalOrder no-op if already present`() {
        val order = listOf("spotify", "soundcloud")
        val result = scoring.insertInCanonicalOrder(order, "spotify")
        assertEquals(order, result)
    }

    @Test
    fun `insertInCanonicalOrder appends unknown resolver`() {
        val order = listOf("spotify", "soundcloud")
        val result = scoring.insertInCanonicalOrder(order, "newresolver")
        assertEquals(listOf("spotify", "soundcloud", "newresolver"), result)
    }

    @Test
    fun `insertInCanonicalOrder handles empty list`() {
        val result = scoring.insertInCanonicalOrder(emptyList(), "bandcamp")
        assertEquals(listOf("bandcamp"), result)
    }

    @Test
    fun `insertInCanonicalOrder inserts youtube at end of canonical`() {
        val order = listOf("spotify", "applemusic", "soundcloud")
        val result = scoring.insertInCanonicalOrder(order, "youtube")
        assertEquals(listOf("spotify", "applemusic", "soundcloud", "youtube"), result)
    }

    @Test
    fun `insertInCanonicalOrder inserts bandcamp between applemusic and soundcloud`() {
        val order = listOf("spotify", "applemusic", "soundcloud")
        val result = scoring.insertInCanonicalOrder(order, "bandcamp")
        assertEquals(listOf("spotify", "applemusic", "bandcamp", "soundcloud"), result)
    }

    // -- helpers --

    private fun makeSource(resolver: String, confidence: Double?) = ResolvedSource(
        url = "https://example.com/$resolver",
        sourceType = "stream",
        resolver = resolver,
        confidence = confidence,
    )
}

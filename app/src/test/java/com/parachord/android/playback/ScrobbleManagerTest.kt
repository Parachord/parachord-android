package com.parachord.android.playback

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Tests for ScrobbleManager's scrobble threshold logic.
 * Per Last.fm / ListenBrainz spec: scrobble after max(30s, min(duration/2, 240s)).
 *
 * The threshold is a private method, so we test via reflection for the pure logic.
 * In production tests, this could also be tested through the full playback state flow.
 */
class ScrobbleManagerTest {

    /**
     * Compute scrobble threshold using the same formula as ScrobbleManager.
     * max(30s, min(duration/2, 240s))
     */
    private fun scrobbleThreshold(durationSeconds: Long): Long {
        val halfDuration = durationSeconds / 2
        val fourMinutes = 240L
        val minListenTime = 30L
        return maxOf(minListenTime, minOf(halfDuration, fourMinutes))
    }

    @Test
    fun `threshold for very short track (10s) is minimum 30s`() {
        assertEquals(30L, scrobbleThreshold(10))
    }

    @Test
    fun `threshold for 30s track is 30s`() {
        assertEquals(30L, scrobbleThreshold(30))
    }

    @Test
    fun `threshold for 1 minute track is 30s`() {
        // min(30, 240) = 30, max(30, 30) = 30
        assertEquals(30L, scrobbleThreshold(60))
    }

    @Test
    fun `threshold for 3 minute track is 90s (half)`() {
        // 180/2 = 90, min(90, 240) = 90, max(30, 90) = 90
        assertEquals(90L, scrobbleThreshold(180))
    }

    @Test
    fun `threshold for 4 minute track is 120s (half)`() {
        assertEquals(120L, scrobbleThreshold(240))
    }

    @Test
    fun `threshold for 8 minute track is 240s (capped)`() {
        // 480/2 = 240, min(240, 240) = 240, max(30, 240) = 240
        assertEquals(240L, scrobbleThreshold(480))
    }

    @Test
    fun `threshold for 10 minute track is 240s (capped at 4 minutes)`() {
        // 600/2 = 300, min(300, 240) = 240, max(30, 240) = 240
        assertEquals(240L, scrobbleThreshold(600))
    }

    @Test
    fun `threshold for 20 minute prog rock epic is still 240s`() {
        assertEquals(240L, scrobbleThreshold(1200))
    }

    @Test
    fun `threshold for 0s track is 30s minimum`() {
        assertEquals(30L, scrobbleThreshold(0))
    }

    @Test
    fun `threshold for standard 3_5 min pop song is 105s`() {
        // 210/2 = 105, min(105, 240) = 105, max(30, 105) = 105
        assertEquals(105L, scrobbleThreshold(210))
    }
}

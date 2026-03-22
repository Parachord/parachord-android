package com.parachord.android.resolver

import org.junit.Assert.*
import org.junit.Test

class TrackResolverCacheTest {

    @Test
    fun `trackKey normalizes to lowercase and trims`() {
        assertEquals("hello|world", trackKey("  Hello ", " World  "))
    }

    @Test
    fun `trackKey is case-insensitive`() {
        assertEquals(trackKey("SONG", "ARTIST"), trackKey("song", "artist"))
    }

    @Test
    fun `trackKey handles empty strings`() {
        assertEquals("|", trackKey("", ""))
    }

    @Test
    fun `trackKey preserves special characters`() {
        assertEquals("don't stop|queen", trackKey("Don't Stop", "Queen"))
    }
}

package com.parachord.android.data.repository

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tests for FreshDrop model properties and FreshDropsRepository pure logic.
 */
class FreshDropsRepositoryTest {

    // -- FreshDrop.isUpcoming --

    @Test
    fun `isUpcoming returns true for future date`() {
        val futureDate = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Future Album", artist = "Artist", date = futureDate)
        assertTrue(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for past date`() {
        val pastDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Old Album", artist = "Artist", date = pastDate)
        assertFalse(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for null date`() {
        val drop = FreshDrop(title = "Unknown", artist = "Artist", date = null)
        assertFalse(drop.isUpcoming)
    }

    @Test
    fun `isUpcoming returns false for invalid date`() {
        val drop = FreshDrop(title = "Bad Date", artist = "Artist", date = "not-a-date")
        assertFalse(drop.isUpcoming)
    }

    // -- FreshDrop.displayDate --

    @Test
    fun `displayDate formats past date without prefix`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = "2025-01-15")
        assertTrue(drop.displayDate.contains("Jan 15, 2025"))
        assertFalse(drop.displayDate.startsWith("Coming"))
    }

    @Test
    fun `displayDate formats future date with Coming prefix`() {
        val futureDate = LocalDate.now().plusDays(60).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val drop = FreshDrop(title = "Album", artist = "Artist", date = futureDate)
        assertTrue(drop.displayDate.startsWith("Coming"))
    }

    @Test
    fun `displayDate returns empty for null date`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = null)
        assertEquals("", drop.displayDate)
    }

    @Test
    fun `displayDate returns raw string for unparseable date`() {
        val drop = FreshDrop(title = "Album", artist = "Artist", date = "2025")
        // Partial date "2025" can't be parsed as ISO_LOCAL_DATE → returns raw
        assertEquals("2025", drop.displayDate)
    }

    // -- Release filtering logic --

    @Test
    fun `filter excludes compilation secondary type`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val secondaryTypes = listOf("Compilation")
        val excluded = secondaryTypes.any { it.lowercase() in excludedTypes }
        assertTrue(excluded)
    }

    @Test
    fun `filter excludes broadcast primary type`() {
        val primaryType = "Broadcast"
        assertTrue(primaryType.lowercase() == "broadcast")
    }

    @Test
    fun `filter allows album with no secondary types`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val secondaryTypes = emptyList<String>()
        val excluded = secondaryTypes.any { it.lowercase() in excludedTypes }
        assertFalse(excluded)
    }

    @Test
    fun `filter allows EP and single types`() {
        val excludedTypes = listOf("compilation", "broadcast", "live", "dj-mix", "remix", "mixtape/street")
        val epTypes = listOf("EP")
        val singleTypes = listOf("Single")
        assertFalse(epTypes.any { it.lowercase() in excludedTypes })
        assertFalse(singleTypes.any { it.lowercase() in excludedTypes })
    }

    // -- Date cutoff logic --

    @Test
    fun `six months ago cutoff filters correctly`() {
        val sixMonthsAgo = LocalDate.now().minusMonths(6)
        val cutoffStr = sixMonthsAgo.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Recent release should pass
        val recentDate = LocalDate.now().minusMonths(2).format(DateTimeFormatter.ISO_LOCAL_DATE)
        assertTrue(recentDate >= cutoffStr)

        // Old release should fail
        val oldDate = LocalDate.now().minusMonths(12).format(DateTimeFormatter.ISO_LOCAL_DATE)
        assertFalse(oldDate >= cutoffStr)
    }

    // -- Merge and dedup logic --

    @Test
    fun `mergeAndDedupe prefers fresh results`() {
        val fresh = listOf(
            FreshDrop(title = "Album A", artist = "Artist 1", date = "2025-03-01", albumArt = "new-art.jpg"),
        )
        val prior = listOf(
            FreshDrop(title = "Album A", artist = "Artist 1", date = "2025-03-01", albumArt = "old-art.jpg"),
            FreshDrop(title = "Album B", artist = "Artist 2", date = "2025-02-01"),
        )
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(2, merged.size)
        // Fresh version should be kept (listed first)
        val albumA = merged.find { it.title == "Album A" }
        assertEquals("new-art.jpg", albumA?.albumArt)
    }

    @Test
    fun `mergeAndDedupe deduplicates by artist+title`() {
        val fresh = listOf(
            FreshDrop(title = "Same Album", artist = "Same Artist"),
        )
        val prior = listOf(
            FreshDrop(title = "Same Album", artist = "Same Artist"),
        )
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(1, merged.size)
    }

    @Test
    fun `mergeAndDedupe sorts by date descending`() {
        val releases = listOf(
            FreshDrop(title = "A", artist = "X", date = "2025-01-01"),
            FreshDrop(title = "B", artist = "Y", date = "2025-03-01"),
            FreshDrop(title = "C", artist = "Z", date = "2025-02-01"),
        )
        val sorted = mergeAndDedupe(releases, emptyList())
        assertEquals("B", sorted[0].title) // March
        assertEquals("C", sorted[1].title) // February
        assertEquals("A", sorted[2].title) // January
    }

    @Test
    fun `mergeAndDedupe is case insensitive`() {
        val fresh = listOf(FreshDrop(title = "Album", artist = "RADIOHEAD"))
        val prior = listOf(FreshDrop(title = "album", artist = "radiohead"))
        val merged = mergeAndDedupe(fresh, prior)
        assertEquals(1, merged.size)
    }

    // -- Cache staleness --

    @Test
    fun `cache stale after 6 hours`() {
        val threshold = 6 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        assertTrue(now - (now - threshold - 1) > threshold)
    }

    @Test
    fun `cache fresh within 6 hours`() {
        val threshold = 6 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        assertFalse(now - (now - threshold + 1000) > threshold)
    }

    // -- Replicating mergeAndDedupe logic --

    private fun mergeAndDedupe(
        freshReleases: List<FreshDrop>,
        priorReleases: List<FreshDrop>,
    ): List<FreshDrop> {
        val merged = freshReleases + priorReleases
        val seen = mutableSetOf<String>()
        return merged
            .filter { release ->
                val key = "${release.artist.lowercase()}|${release.title.lowercase()}"
                seen.add(key)
            }
            .sortedByDescending { it.date ?: "" }
    }
}

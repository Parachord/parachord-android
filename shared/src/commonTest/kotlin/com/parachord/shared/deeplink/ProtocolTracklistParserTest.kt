package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProtocolTracklistParserTest {

    /** Stub platform XSPF parser — returns whatever the test sets. */
    private fun fakeXspf(result: ParsedProtocolTracklist): (String) -> ParsedProtocolTracklist =
        { result }

    /** Fail-loudly stub for tests that should never hit XSPF. */
    private val noXspf: (String) -> ParsedProtocolTracklist = {
        throw AssertionError("XSPF parser should not have been invoked")
    }

    // ── Format dispatch ──

    @Test
    fun dispatchesXmlPrefixToXspfLambda() {
        val expected = ParsedProtocolTracklist(
            title = "From Lambda",
            creator = null,
            tracks = listOf(ProtocolTrack("A", "T")),
        )
        val out = parseProtocolTracklist("<?xml version=\"1.0\"?><playlist/>", fakeXspf(expected))
        assertEquals("From Lambda", out.title)
    }

    @Test
    fun dispatchesXmlBareTagToXspfLambda() {
        val expected = ParsedProtocolTracklist(null, null, listOf(ProtocolTrack("A", "T")))
        val out = parseProtocolTracklist("<playlist/>", fakeXspf(expected))
        assertEquals(1, out.tracks.size)
    }

    @Test
    fun rejectsUnrecognizedFormat() {
        assertFailsWith<IllegalArgumentException> {
            parseProtocolTracklist("M3U raw text", noXspf)
        }
    }

    // ── Generic JSON ──

    @Test
    fun parsesGenericJsonWithTopLevelTracks() {
        val payload = """
            {"title":"Mix",
             "tracks":[
               {"artist":"Witch Post","title":"Twin Fawn"},
               {"artist":"Wintersleep","title":"Wishing Moon","album":"New Inheritors"}
             ]}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals("Mix", out.title)
        assertEquals(2, out.tracks.size)
        assertEquals("Witch Post", out.tracks[0].artist)
        assertEquals("Wishing Moon", out.tracks[1].title)
        assertEquals("New Inheritors", out.tracks[1].album)
    }

    @Test
    fun parsesGenericJsonBareArray() {
        val payload = """[{"artist":"A","title":"T1"},{"artist":"B","title":"T2"}]"""
        val out = parseProtocolTracklist(payload, noXspf)
        assertNull(out.title)
        assertEquals(2, out.tracks.size)
    }

    @Test
    fun skipsGenericJsonTracksMissingArtist() {
        val payload = """{"tracks":[{"artist":"OK","title":"OK"},{"title":"Missing artist"}]}"""
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals(1, out.tracks.size)
        assertEquals("OK", out.tracks[0].artist)
    }

    // ── JSPF ──

    @Test
    fun parsesStandardJspf() {
        val payload = """
            {"playlist":{
                "title":"My Mix",
                "creator":"jesse",
                "track":[
                    {"title":"Twin Fawn","creator":"Witch Post","album":"Witch Post"},
                    {"title":"Wishing Moon","creator":"Wintersleep"}
                ]
            }}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals("My Mix", out.title)
        assertEquals("jesse", out.creator)
        assertEquals(2, out.tracks.size)
        assertEquals("Witch Post", out.tracks[0].artist)
        assertEquals("Twin Fawn", out.tracks[0].title)
        assertEquals("Witch Post", out.tracks[0].album)
    }

    @Test
    fun unwrapsLbRadioWrapper() {
        // ListenBrainz lb-radio API returns { payload: { jspf: { playlist: ... } } }
        val payload = """
            {"payload":{"jspf":{"playlist":{
                "title":"LB Radio: Witch Post",
                "track":[{"title":"Twin Fawn","creator":"Witch Post"}]
            }}}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals("LB Radio: Witch Post", out.title)
        assertEquals(1, out.tracks.size)
    }

    @Test
    fun joinsCreatorArrayWithComma() {
        val payload = """
            {"playlist":{"track":[{
                "title":"Collab",
                "creator":["Artist A","Artist B"]
            }]}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals("Artist A, Artist B", out.tracks[0].artist)
    }

    @Test
    fun extractsMbidFromBareIdentifier() {
        val mbid = "c70a2d8f-c1b7-4f10-9d10-95158a08b528"
        val payload = """
            {"playlist":{"track":[{
                "title":"T","creator":"A",
                "identifier":"$mbid"
            }]}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals(mbid, out.tracks[0].mbid)
    }

    @Test
    fun extractsMbidFromMusicbrainzUrlIdentifier() {
        val mbid = "c70a2d8f-c1b7-4f10-9d10-95158a08b528"
        val payload = """
            {"playlist":{"track":[{
                "title":"T","creator":"A",
                "identifier":"https://musicbrainz.org/recording/$mbid"
            }]}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals(mbid, out.tracks[0].mbid)
    }

    @Test
    fun extractsMbidFromIdentifierArray() {
        val mbid = "c70a2d8f-c1b7-4f10-9d10-95158a08b528"
        val payload = """
            {"playlist":{"track":[{
                "title":"T","creator":"A",
                "identifier":["spotify:track:foo","https://musicbrainz.org/recording/$mbid"]
            }]}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals(mbid, out.tracks[0].mbid)
    }

    @Test
    fun extractsIsrcFromExtension() {
        val payload = """
            {"playlist":{"track":[{
                "title":"T","creator":"A",
                "extension":{"isrc":"USRC17607839"}
            }]}}
        """.trimIndent()
        val out = parseProtocolTracklist(payload, noXspf)
        assertEquals("USRC17607839", out.tracks[0].isrc)
    }

    // ── Limits ──

    @Test
    fun rejectsEmptyTracklist() {
        val payload = """{"tracks":[]}"""
        assertFailsWith<IllegalArgumentException> {
            parseProtocolTracklist(payload, noXspf)
        }
    }

    @Test
    fun rejectsOversizeTracklist() {
        val tracks = (1..MAX_INLINE_TRACKS + 1).joinToString(",") {
            """{"artist":"A$it","title":"T$it"}"""
        }
        val payload = """{"tracks":[$tracks]}"""
        assertFailsWith<IllegalArgumentException> {
            parseProtocolTracklist(payload, noXspf)
        }
    }
}

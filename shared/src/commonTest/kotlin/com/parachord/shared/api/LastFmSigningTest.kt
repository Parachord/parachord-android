package com.parachord.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TDD for the Last.fm `api_sig` signing (scrobbling #193, shared port). The
 * algorithm (mirrors the Android LastFmScrobbler): MD5 of the params sorted by
 * key (EXCLUDING `format`), concatenated as key+value, with the shared secret
 * appended.
 */
class LastFmSigningTest {

    @Test
    fun md5_matchesKnownVector() {
        // Canonical: md5("abc") = 900150983cd24fb0d6963f7d28e17f72
        assertEquals("900150983cd24fb0d6963f7d28e17f72", LastFmSigning.md5("abc"))
        // Empty string vector.
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LastFmSigning.md5(""))
    }

    @Test
    fun sigBase_sortsByKey_excludesFormat_appendsSecret() {
        val base = LastFmSigning.sigBase(
            mapOf("b" to "2", "a" to "1", "format" to "json", "c" to "3"),
            sharedSecret = "secret",
        )
        assertEquals("a1b2c3secret", base)
    }

    @Test
    fun apiSig_isMd5OfSigBase() {
        val params = mapOf("method" to "track.scrobble", "artist" to "The Beatles", "sk" to "key")
        assertEquals(
            LastFmSigning.md5(LastFmSigning.sigBase(params, "shh")),
            LastFmSigning.apiSig(params, "shh"),
        )
    }
}

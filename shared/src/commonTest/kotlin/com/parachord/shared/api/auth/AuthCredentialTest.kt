package com.parachord.shared.api.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthCredentialTest {
    @Test
    fun authRealm_enumeratesAllSupportedRealms() {
        val expected = setOf(
            AuthRealm.Spotify,
            AuthRealm.AppleMusicLibrary,
            AuthRealm.ListenBrainz,
            AuthRealm.LastFm,
            AuthRealm.Ticketmaster,
            AuthRealm.SeatGeek,
            AuthRealm.Discogs,
        )
        assertEquals(expected, AuthRealm.entries.toSet())
    }

    @Test
    fun bearerToken_dataClass_equalsAndHashCode() {
        val a = AuthCredential.BearerToken("abc")
        val b = AuthCredential.BearerToken("abc")
        val c = AuthCredential.BearerToken("def")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun bearerWithMUT_carriesBothTokens() {
        val cred = AuthCredential.BearerWithMUT(devToken = "dev123", mut = "mut456")
        assertEquals("dev123", cred.devToken)
        assertEquals("mut456", cred.mut)
    }

    @Test
    fun lastFmSigned_optionalSessionKey() {
        val authPhase = AuthCredential.LastFmSigned(sharedSecret = "secret", sessionKey = null)
        val scrobblePhase = AuthCredential.LastFmSigned(sharedSecret = "secret", sessionKey = "sk")
        assertEquals(null, authPhase.sessionKey)
        assertEquals("sk", scrobblePhase.sessionKey)
    }
}

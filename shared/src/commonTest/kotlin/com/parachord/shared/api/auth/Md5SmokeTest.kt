package com.parachord.shared.api.auth

import org.kotlincrypto.hash.md.MD5
import kotlin.test.Test
import kotlin.test.assertEquals

class Md5SmokeTest {
    @Test
    fun md5_emptyString_matchesKnownDigest() {
        // RFC 1321 test vector
        val digest = MD5().digest("".encodeToByteArray())
        val hex = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex)
    }

    @Test
    fun md5_lastFmExampleString_matchesKnownDigest() {
        // Known fixture: MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        val digest = MD5().digest("abc".encodeToByteArray())
        val hex = digest.joinToString("") { byte -> ((byte.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex)
    }
}

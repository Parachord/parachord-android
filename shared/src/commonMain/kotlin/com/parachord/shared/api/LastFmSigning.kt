package com.parachord.shared.api

import org.kotlincrypto.hash.md.MD5

/**
 * Last.fm (and Libre.fm) `api_sig` request signing — shared so iOS can scrobble
 * too (#193). The signature is `MD5( sorted(key+value, excluding "format") +
 * sharedSecret )`, lowercase hex. Mirrors the Android LastFmScrobbler.postSigned
 * algorithm exactly. Pure + deterministic — see LastFmSigningTest.
 */
internal object LastFmSigning {

    /** Lowercase hex MD5 of [input]. */
    fun md5(input: String): String =
        MD5().digest(input.encodeToByteArray()).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    /** The pre-MD5 signature base string (exposed for testing). */
    fun sigBase(params: Map<String, String>, sharedSecret: String): String =
        params.filterKeys { it != "format" }
            .entries.sortedBy { it.key }
            .joinToString("") { "${it.key}${it.value}" } + sharedSecret

    /** Last.fm `api_sig` for the given params + shared secret. */
    fun apiSig(params: Map<String, String>, sharedSecret: String): String =
        md5(sigBase(params, sharedSecret))
}

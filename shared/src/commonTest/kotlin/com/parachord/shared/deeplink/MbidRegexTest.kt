package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MbidRegexTest {
    private val canonical = "c70a2d8f-c1b7-4f10-9d10-95158a08b528"

    @Test
    fun acceptsCanonicalLowercaseUuid() {
        assertTrue(isValidMbid(canonical))
    }

    @Test
    fun rejectsUppercase() {
        // Strict regex is lowercase-only. Callers pre-normalize via .lowercase()
        // before invoking the resolver, so uppercase here is a real bug.
        assertFalse(isValidMbid(canonical.uppercase()))
    }

    @Test
    fun rejectsMissingDashes() {
        assertFalse(isValidMbid(canonical.replace("-", "")))
    }

    @Test
    fun rejectsTooShort() {
        assertFalse(isValidMbid(canonical.dropLast(1)))
    }

    @Test
    fun rejectsTooLong() {
        assertFalse(isValidMbid(canonical + "0"))
    }

    @Test
    fun rejectsNonHexChars() {
        assertFalse(isValidMbid("z70a2d8f-c1b7-4f10-9d10-95158a08b528"))
    }

    @Test
    fun rejectsNull() {
        assertFalse(isValidMbid(null))
    }

    @Test
    fun rejectsEmpty() {
        assertFalse(isValidMbid(""))
    }
}

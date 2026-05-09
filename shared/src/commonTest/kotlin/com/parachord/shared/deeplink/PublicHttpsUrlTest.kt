package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PublicHttpsUrlTest {

    // ── Acceptance: real public URLs ──

    @Test
    fun accepts_publicHttpsHost() {
        validatePublicHttpsUrl("https://example.com/playlist.xspf")
    }

    @Test
    fun accepts_publicIpv4() {
        validatePublicHttpsUrl("https://1.2.3.4/x")
    }

    @Test
    fun accepts_172_15_x() {
        // 172.15 is just below the 172.16-31 private block.
        validatePublicHttpsUrl("https://172.15.0.1/x")
    }

    @Test
    fun accepts_172_32_x() {
        // 172.32 is just above the 172.16-31 private block.
        validatePublicHttpsUrl("https://172.32.0.1/x")
    }

    @Test
    fun accepts_publicCgnatBoundary_100_63() {
        // 100.63.x.x is just below the 100.64-127 CGNAT block.
        validatePublicHttpsUrl("https://100.63.255.255/x")
    }

    @Test
    fun accepts_publicCgnatBoundary_100_128() {
        // 100.128.x.x is just above the 100.64-127 CGNAT block.
        validatePublicHttpsUrl("https://100.128.0.0/x")
    }

    @Test
    fun accepts_urlWithUserinfo() {
        validatePublicHttpsUrl("https://user:pass@example.com/x")
    }

    @Test
    fun accepts_urlWithPortAndQuery() {
        validatePublicHttpsUrl("https://example.com:8443/x?foo=bar")
    }

    // ── Rejection: scheme ──

    @Test
    fun rejects_httpScheme() {
        assertFailsWith<IllegalArgumentException> {
            validatePublicHttpsUrl("http://example.com/x")
        }
    }

    @Test
    fun rejects_fileScheme() {
        assertFailsWith<IllegalArgumentException> {
            validatePublicHttpsUrl("file:///etc/passwd")
        }
    }

    // ── Rejection: localhost / private IPv4 ──

    @Test
    fun rejects_localhost() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://localhost/x") }
    }

    @Test
    fun rejects_localhostTrailingDot() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://localhost./x") }
    }

    @Test
    fun rejects_dotLocal() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://evil.local/x") }
    }

    @Test
    fun rejects_dotLocalTrailingDot() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://evil.local./x") }
    }

    @Test
    fun rejects_127_0_0_1() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://127.0.0.1/x") }
    }

    @Test
    fun rejects_10_x() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://10.0.0.1/x") }
    }

    @Test
    fun rejects_192_168_x() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://192.168.1.1/x") }
    }

    @Test
    fun rejects_172_16_x() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://172.16.0.1/x") }
    }

    @Test
    fun rejects_172_31_x() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://172.31.255.255/x") }
    }

    @Test
    fun rejects_169_254_x() {
        // Link-local — covers AWS metadata 169.254.169.254.
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://169.254.169.254/latest") }
    }

    @Test
    fun rejects_0_0_0_0() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://0.0.0.0/x") }
    }

    // ── Rejection: CGNAT ──

    @Test
    fun rejects_cgnat_100_64() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://100.64.1.1/x") }
    }

    @Test
    fun rejects_cgnat_100_127() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://100.127.255.255/x") }
    }

    // ── Rejection: IPv6 ──

    @Test
    fun rejects_ipv6_loopback() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[::1]/x") }
    }

    @Test
    fun rejects_ipv6_uniqueLocal_fc() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[fc00::1]/x") }
    }

    @Test
    fun rejects_ipv6_uniqueLocal_fd() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[fdff::1]/x") }
    }

    @Test
    fun rejects_ipv6_linkLocal_fe80() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[fe80::1]/x") }
    }

    @Test
    fun rejects_ipv4MappedIpv6_dottedForm() {
        // ::ffff:127.0.0.1 — text-format IPv4-mapped reaching loopback.
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[::ffff:127.0.0.1]/x") }
    }

    @Test
    fun rejects_ipv4MappedIpv6_hexForm() {
        // ::ffff:7f00:1 — same address, hex words form.
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://[::ffff:7f00:1]/x") }
    }

    // ── Rejection: malformed / non-canonical IPv4 forms ──

    @Test
    fun rejects_singleIntegerIpv4() {
        // 2130706433 = 127.0.0.1 in decimal-int form.
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://2130706433/x") }
    }

    @Test
    fun rejects_octalIpv4() {
        // 0177 = 127. We refuse leading-zero octets to fail closed.
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https://0177.0.0.1/x") }
    }

    @Test
    fun rejects_emptyHost() {
        assertFailsWith<IllegalArgumentException> { validatePublicHttpsUrl("https:///path") }
    }
}

package com.carlese.simpleproxy.app

import com.carlese.simpleproxy.app.data.ProxyProtocol
import com.carlese.simpleproxy.app.data.ProxyUriParser
import com.carlese.simpleproxy.app.data.isValidIpv4Address
import org.junit.Assert.*
import org.junit.Test

class ProxyUriParserSecurityTest {

    @Test
    fun parseFullSocks5Uri_extractsCredentialsHostAndPort() {
        val parsed = ProxyUriParser.parse("socks5://alice:p%40ss@proxy.example.com:9050")
        assertNotNull(parsed)
        assertEquals(ProxyProtocol.SOCKS5, parsed!!.protocol)
        assertEquals("proxy.example.com", parsed.host)
        assertEquals(9050, parsed.port)
        assertEquals("alice", parsed.username)
        assertEquals("p@ss", parsed.password)
    }

    @Test
    fun parseCredentialsWithPlusAndEncodedAt_preservesLiteralPlus() {
        val parsed = ProxyUriParser.parse("socks5://user+tag:sec+ret%401@proxy.example.com:1080")
        assertNotNull(parsed)
        assertEquals("user+tag", parsed!!.username)
        assertEquals("sec+ret@1", parsed.password)
    }

    @Test
    fun parseHttpsAndHttpUris_assignExpectedDefaults() {
        val https = ProxyUriParser.parse("https://gw.example.org")
        assertEquals(ProxyProtocol.HTTPS, https!!.protocol)
        assertEquals(443, https.port)

        val http = ProxyUriParser.parse("http://gw.example.org")
        assertEquals(ProxyProtocol.HTTP, http!!.protocol)
        assertEquals(8080, http.port)
    }

    @Test
    fun blankOrMissingHostUri_returnsNullSafely() {
        assertNull(ProxyUriParser.parse(""))
        assertNull(ProxyUriParser.parse("   "))
        assertNull(ProxyUriParser.parse("socks5://:1080"))
    }

    @Test
    fun ipv4DnsValidator_acceptsValidAndRejectsMalformedAddresses() {
        assertTrue(isValidIpv4Address("1.1.1.1"))
        assertTrue(isValidIpv4Address(" 9.9.9.9 "))
        assertTrue(isValidIpv4Address("0.0.0.0"))
        assertTrue(isValidIpv4Address("255.255.255.255"))

        assertFalse(isValidIpv4Address(""))
        assertFalse(isValidIpv4Address("1.1.1"))
        assertFalse(isValidIpv4Address("256.1.1.1"))
        assertFalse(isValidIpv4Address("01.1.1.1"))
        assertFalse(isValidIpv4Address("1.1.1.1.1"))
        assertFalse(isValidIpv4Address("dns.google"))
    }
}

package com.rootforsree.webshell.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenGeneratorTest {

    @Test
    fun `token is 32 url-safe characters`() {
        val token = TokenGenerator.generate()
        assertEquals(32, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `every start gets a different token`() {
        val seen = (1..100).map { TokenGenerator.generate() }.toSet()
        assertEquals(100, seen.size)
    }

    @Test
    fun `tokens never contain characters that need escaping in a query string`() {
        repeat(100) {
            val token = TokenGenerator.generate()
            assertTrue(!token.contains('+') && !token.contains('/') && !token.contains('='))
        }
    }
}

class NetworkAddressesTest {

    @Test
    fun `wlan interface is Wi-Fi`() {
        assertEquals("Wi-Fi", NetworkAddresses.classify("wlan0", "192.168.0.5"))
    }

    @Test
    fun `tailscale interface is Tailscale`() {
        assertEquals("Tailscale", NetworkAddresses.classify("tailscale0", "100.119.8.48"))
    }

    @Test
    fun `a 100-64 address is Tailscale even on an odd interface name`() {
        assertEquals("Tailscale", NetworkAddresses.classify("rmnet0", "100.90.1.2"))
        assertTrue(NetworkAddresses.isTailscaleAddress("100.119.8.48"))
        assertTrue(NetworkAddresses.isTailscaleAddress("100.64.0.1"))
        assertTrue(!NetworkAddresses.isTailscaleAddress("100.128.0.1"))
        assertTrue(!NetworkAddresses.isTailscaleAddress("192.168.0.5"))
    }

    @Test
    fun `unknown interfaces keep their name`() {
        assertEquals("eth0", NetworkAddresses.classify("eth0", "10.0.0.2"))
    }

    @Test
    fun `shell url carries host port and token`() {
        val url = buildShellUrl(LanAddress("Wi-Fi", "192.168.0.5"), 8080, "abc123")
        assertEquals("http://192.168.0.5:8080/?token=abc123", url)
    }

    @Test
    fun `two tokens give two different urls`() {
        val first = buildShellUrl(LanAddress("Wi-Fi", "192.168.0.5"), 8080, "token-one")
        val second = buildShellUrl(LanAddress("Wi-Fi", "192.168.0.5"), 8080, "token-two")
        assertNotEquals(first, second)
    }
}

class ProcStatTest {

    /** Builds a /proc/<pid>/stat line with the fields in kernel order. */
    private fun statLine(
        pid: Int,
        comm: String,
        ppid: Int,
        pgrp: Int,
        session: Int,
        starttime: Long,
    ): String {
        val afterComm = listOf(
            "S", ppid, pgrp, session, 34816, pid, 4194304, 100, 0, 0, 0,
            10, 5, 0, 0, 20, 0, 1, 0, starttime, 1000000, 100,
        ).joinToString(" ")
        return "$pid ($comm) $afterComm"
    }

    @Test
    fun `session leader is its own session`() {
        val stat = statLine(pid = 1234, comm = "sh", ppid = 1, pgrp = 1234, session = 1234, starttime = 123456)
        assertTrue(ShellSession.parseSessionLeader(stat, 1234))
        assertEquals(123456L, ShellSession.parseStarttime(stat))
    }

    @Test
    fun `plain shell child is not a session leader`() {
        val stat = statLine(pid = 1235, comm = "sh", ppid = 1234, pgrp = 1234, session = 1234, starttime = 123457)
        assertTrue(!ShellSession.parseSessionLeader(stat, 1235))
        assertEquals(123457L, ShellSession.parseStarttime(stat))
    }

    @Test
    fun `comm with spaces and parens still parses`() {
        val stat = statLine(pid = 1236, comm = "my sh (x)", ppid = 1, pgrp = 1236, session = 1236, starttime = 123458)
        assertTrue(ShellSession.parseSessionLeader(stat, 1236))
        assertEquals(123458L, ShellSession.parseStarttime(stat))
    }

    @Test
    fun `garbage stat line parses to nothing`() {
        assertTrue(!ShellSession.parseSessionLeader("garbage", 1))
        assertEquals(null, ShellSession.parseStarttime("garbage"))
        assertTrue(!ShellSession.parseSessionLeader("", 1))
    }
}

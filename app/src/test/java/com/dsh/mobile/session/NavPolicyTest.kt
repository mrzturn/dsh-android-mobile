package com.dsh.mobile.session

import org.junit.Assert.assertEquals
import org.junit.Test

class NavPolicyTest {

    @Test
    fun `same host stays internal`() {
        val (a, _, nb) = NavPolicy.decide("192.168.1.10", "http://192.168.1.10:3080/gui/x", pairing = true)
        assertEquals(NavAction.OPEN_INTERNAL, a)
        assertEquals(null, nb)
    }

    @Test
    fun `cross-host pair grant redirect is adopted while pairing`() {
        val (a, _, nb) = NavPolicy.decide(
            "127.0.0.1",
            "http://192.168.1.10:3080/pair-app?grant=abc",
            pairing = true,
        )
        assertEquals(NavAction.ADOPT_BASE, a)
        assertEquals("http://192.168.1.10:3080", nb)
    }

    @Test
    fun `cross-host pair hop goes external after pairing`() {
        val (a, _, nb) = NavPolicy.decide(
            "192.168.1.10",
            "http://10.0.0.9:3080/pair-app?grant=x",
            pairing = false,
        )
        assertEquals(NavAction.OPEN_EXTERNAL, a)
        assertEquals(null, nb)
    }

    @Test
    fun `non-http scheme goes external even while pairing`() {
        val (a, _, _) = NavPolicy.decide("192.168.1.10", "intent://x/#Intent;scheme=http;end", pairing = true)
        assertEquals(NavAction.OPEN_EXTERNAL, a)
    }

    @Test
    fun `cross-host non-pair page goes external while pairing`() {
        val (a, _, _) = NavPolicy.decide("192.168.1.10", "https://evil.example.com/steal", pairing = true)
        assertEquals(NavAction.OPEN_EXTERNAL, a)
    }
}

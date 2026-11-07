package com.dsh.mobile.pairing

import com.dsh.mobile.data.PairAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairInputTest {

    @Test
    fun `pair link from QR parses to PairLink`() {
        val a = PairInput.parse("http://192.168.1.10:3080/pair-accept?pair=0123456789abcdef0123456789abcdef")
        assertTrue(a is PairAttempt.PairLink)
        a as PairAttempt.PairLink
        assertEquals("http://192.168.1.10:3080", a.baseUrl)
        assertEquals("0123456789abcdef0123456789abcdef", a.token)
        assertEquals("http://192.168.1.10:3080/pair-accept?pair=0123456789abcdef0123456789abcdef", a.url)
    }

    @Test
    fun `relay https link parses`() {
        val a = PairInput.parse("https://abc123.dsh-market.com/pair-accept?pair=abcdef0123456789abcdef0123456789")
        assertTrue(a is PairAttempt.PairLink)
        assertEquals("https://abc123.dsh-market.com", (a as PairAttempt.PairLink).baseUrl)
    }

    @Test
    fun `url embedded in surrounding text still parses`() {
        val a = PairInput.parse("请打开 http://10.0.0.2:3080/pair-accept?pair=0123456789abcdef0123456789abcdef 完成配对")
        assertNotNull(a)
        assertTrue(a is PairAttempt.PairLink)
    }

    @Test
    fun `bare 32-hex key maps to TokenWithHost`() {
        val a = PairInput.parse("ABCDEF0123456789ABCDEF0123456789")
        assertTrue(a is PairAttempt.TokenWithHost)
        assertEquals("abcdef0123456789abcdef0123456789", (a as PairAttempt.TokenWithHost).token)
    }

    @Test
    fun `browser-auth key link falls back to Direct`() {
        val a = PairInput.parse("http://127.0.0.1:3080/?key=some-browser-credential")
        assertTrue(a is PairAttempt.Direct)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(PairInput.parse("hello world"))
        assertNull(PairInput.parse(""))
        assertNull(PairInput.parse("12345"))
    }

    @Test
    fun `combine builds pair link from host and key`() {
        val a = PairInput.combine("http://192.168.1.10:3080/", "0123456789abcdef0123456789abcdef")
        assertTrue(a is PairAttempt.PairLink)
        assertEquals(
            "http://192.168.1.10:3080/pair-accept?pair=0123456789abcdef0123456789abcdef",
            (a as PairAttempt.PairLink).url,
        )
    }

    @Test
    fun `uppercase 32-hex key is normalized to lowercase pair link`() {
        val a = PairInput.parse("HTTP://192.168.1.10:3080/pair-accept?pair=" + "ABCDEF0123456789ABCDEF0123456789")
        assertTrue(a is PairAttempt.PairLink)
        assertEquals("http://192.168.1.10:3080/pair-accept?pair=abcdef0123456789abcdef0123456789", (a as PairAttempt.PairLink).url)
    }

    @Test
    fun `non-hex token (grant-shaped) falls back to Direct for the server to judge`() {
        // dsh-remote-web-ui mints 32-hex pair tokens; any other token material
        // (e.g. a pasted /pair-app?grant=... one-time grant) must not be
        // rewritten — open it as-is and let the host fence decide.
        val a = PairInput.parse("https://desk.example.com/pair-app?grant=Ab12Cd34-_x")
        assertTrue(a is PairAttempt.Direct)
    }
}

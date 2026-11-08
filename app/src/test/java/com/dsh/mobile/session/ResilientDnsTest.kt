package com.dsh.mobile.session

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {

    private class FakeDns : Dns {
        var calls = 0
        var answers = ArrayDeque<List<InetAddress>>()
        var error: UnknownHostException? = null

        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            if (answers.isNotEmpty()) return answers.removeFirst()
            error?.let { throw it }
            return emptyList()
        }
    }

    private fun addr(host: String, o1: Int) =
        InetAddress.getByAddress(host, byteArrayOf(104, 16, o1.toByte(), 7))

    private fun dns(fake: FakeDns) = ResilientDns(fake, attempts = 3, retryDelayMs = 0)

    @Test
    fun `success is returned on first call`() {
        val fake = FakeDns().apply { answers.addLast(listOf(addr("relay", 1))) }
        val result = dns(fake).lookup("57524da5964ff53b.dsh-market.com")
        assertEquals(1, fake.calls)
        assertEquals(1, result.size)
    }

    @Test
    fun `empty answer is retried until a real one arrives`() {
        val fake = FakeDns().apply {
            answers.addLast(emptyList())
            answers.addLast(emptyList())
            answers.addLast(listOf(addr("relay", 2)))
        }
        val result = dns(fake).lookup("relay.example.com")
        assertEquals(3, fake.calls)
        assertEquals(2.toByte(), result[0].address[2])
    }

    @Test
    fun `later failures fall back to last known good answer`() {
        val fake = FakeDns().apply {
            answers.addLast(listOf(addr("relay", 3)))
            error = UnknownHostException("flaky")
        }
        val resolver = dns(fake)
        assertEquals(3.toByte(), resolver.lookup("relay.example.com")[0].address[2])
        // Now every attempt fails; the cached answer must still come back.
        val fallback = resolver.lookup("relay.example.com")
        assertEquals(3.toByte(), fallback[0].address[2])
        assertEquals(1 + 3, fake.calls) // 1 initial + 3 failed attempts
    }

    @Test
    fun `never-resolved host throws UnknownHostException`() {
        val fake = FakeDns().apply { error = UnknownHostException("nope") }
        try {
            dns(fake).lookup("missing.example.com")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            // The impl rethrows the underlying exception; only the type is guaranteed.
            assertEquals(3, fake.calls)
        }
    }
}

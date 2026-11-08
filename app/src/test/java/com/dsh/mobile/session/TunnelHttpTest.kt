package com.dsh.mobile.session

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TunnelHttpTest {

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(data) }
    }.toByteArray()

    private fun readAll(stream: java.io.InputStream): ByteArray = stream.use { it.readBytes() }

    // ---- peelStreaming ----

    @Test
    fun `plain bytes pass through untouched`() {
        val plain = "<html>ok</html>".toByteArray()
        val out = TunnelHttp.peelStreaming(ByteArrayInputStream(plain))
        assertEquals("<html>ok</html>", readAll(out).decodeToString())
    }

    @Test
    fun `single gzip layer is peeled`() {
        val payload = "{\"ok\":true,\"paired\":false}"
        val out = TunnelHttp.peelStreaming(ByteArrayInputStream(gzip(payload.toByteArray())))
        assertEquals(payload, readAll(out).decodeToString())
    }

    @Test
    fun `double gzip layer (the relay bug) is fully peeled`() {
        val payload = "{\"ok\":true,\"phase\":\"disconnected\"}"
        val inner = gzip(payload.toByteArray())
        val outer = gzip(inner)
        val out = TunnelHttp.peelStreaming(ByteArrayInputStream(outer))
        assertEquals(payload, readAll(out).decodeToString())
    }

    @Test
    fun `sse stream stays streaming through the peel`() {
        // First chunk readable before EOF: proves no full buffering.
        val body = ByteArrayInputStream("event: ping\ndata: 1\n\n".toByteArray())
        val out = TunnelHttp.peelStreaming(body)
        val buf = ByteArray(12)
        val n = out.read(buf)
        assertEquals("event: ping\n", buf.copyOf(n).decodeToString())
        out.close()
    }

    @Test
    fun `corrupt gzip magic falls back to raw passthrough`() {
        // 1f 8b prefix but an invalid gzip header: peel must not throw at wrap
        // time — it degrades to raw bytes so the WebView sees what it would
        // have seen without the interceptor.
        val junk = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x00, 0x01, 0x02)
        val out = TunnelHttp.peelStreaming(ByteArrayInputStream(junk))
        assertTrue("corrupt gzip stream must pass through unchanged", junk.contentEquals(readAll(out)))
    }

    // ---- gunzipAll ----

    @Test
    fun `gunzipAll handles zero one and two layers`() {
        val payload = "hello relay".toByteArray()
        assertEquals("hello relay", TunnelHttp.gunzipAll(payload).decodeToString())
        assertEquals("hello relay", TunnelHttp.gunzipAll(gzip(payload)).decodeToString())
        assertEquals("hello relay", TunnelHttp.gunzipAll(gzip(gzip(payload))).decodeToString())
    }

    @Test
    fun `gunzipAll leaves non-gzip input unchanged`() {
        val junk = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertTrue(TunnelHttp.gunzipAll(junk).contentEquals(junk))
    }

    // ---- header filters ----

    @Test
    fun `response headers drop hop-by-hop and compression markers`() {
        val filtered = TunnelHttp.filterResponseHeaders(
            listOf(
                "content-type" to "application/json; charset=utf-8",
                "content-encoding" to "gzip",
                "transfer-encoding" to "chunked",
                "x-relay-transport" to "bridge",
                "set-cookie" to "dsh_pair=abc123",
                "set-cookie" to "second=1",
            ),
        )
        assertEquals("application/json; charset=utf-8", filtered["content-type"])
        assertFalse(filtered.containsKey("content-encoding"))
        assertFalse(filtered.containsKey("transfer-encoding"))
        assertFalse(filtered.containsKey("x-relay-transport"))
        // Both set-cookies survive without collapsing.
        assertTrue((filtered["set-cookie"] ?: "").contains("dsh_pair=abc123"))
        assertTrue((filtered["set-cookie"] ?: "").contains("second=1"))
    }

    @Test
    fun `request headers drop accept-encoding and host`() {
        val filtered = TunnelHttp.filterRequestHeaders(
            mapOf(
                "Accept" to "application/json",
                "accept-encoding" to "gzip, br",
                "Host" to "x.dsh-market.com",
                "content-type" to "application/json",
            ),
        )
        assertEquals("application/json", filtered["Accept"])
        assertEquals("application/json", filtered["content-type"])
        assertFalse(filtered.containsKey("accept-encoding"))
        assertFalse(filtered.containsKey("Host"))
    }

    // ---- reason phrase ----

    @Test
    fun `reasonPhrase fills blanks`() {
        assertEquals("OK", TunnelHttp.reasonPhrase(200, ""))
        assertEquals("See Other", TunnelHttp.reasonPhrase(303, null))
        assertEquals("Custom", TunnelHttp.reasonPhrase(200, "Custom"))
    }
}

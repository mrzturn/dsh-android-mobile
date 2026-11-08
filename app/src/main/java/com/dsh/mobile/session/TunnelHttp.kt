package com.dsh.mobile.session

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * App-side workaround for the dsh-market relay's response-mangling bug
 * (observed 2026-11-08 on `*.dsh-market.com` bridged responses):
 *
 * The relay bridge ignores the client's `Accept-Encoding` and fetches the
 * upstream with its own. Upstream gzip bytes then come back with the
 * `content-encoding` header stripped — and, when the client itself asked for
 * gzip, with one extra compression layer stacked on top (declared once).
 * Chromium peels at most one layer and renders leftover gzip bytes as text:
 * the "Tunnel ·" garbage screen.
 *
 * Countermeasure: proxied requests are sent with `Accept-Encoding: identity`
 * (never negotiate compression), and response bytes are sniffed on the fly —
 * any gzip magic is peeled, repeatedly, as a lazy stream, so plaintext (SSE
 * included) and multi-hundred-MB bodies both flow through untouched.
 *
 * Pure JVM logic; no Android imports so it is unit-testable.
 */
object TunnelHttp {

    /** Max gzip layers to peel before handing the stream back as-is. */
    const val MAX_LAYERS = 5

    /** Response headers never handed back to the WebView on a proxied stream. */
    private val RESPONSE_DROP = setOf(
        "connection",
        "keep-alive",
        "transfer-encoding",
        "upgrade",
        "content-length", // unknowable once decoded / re-framed
        "content-encoding", // we deliver fully decoded bytes
        "x-relay-transport", // relay-internal marker
        "speculation-rules", // points at a /cdn-cgi path that 404s behind the bridge
        "report-to",
        "nel",
        "alt-svc",
    )

    /** Request headers that must not be replayed to the origin. */
    private val REQUEST_DROP = setOf(
        "host",
        "connection",
        "keep-alive",
        "transfer-encoding",
        "upgrade",
        // We speak compression ourselves; never let the relay/upstream pick one.
        "accept-encoding",
    )

    /** Whether the bytes start with the gzip magic (1f 8b). */
    fun hasGzipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /** Fixed size of the gzip header (magic, method, flags, mtime, xfl, os). */
    private const val GZIP_HEADER_LEN = 10

    /**
     * Whether the first bytes of [src] look like a gzip header we should
     * decode. Peeks up to [GZIP_HEADER_LEN] bytes and pushes them all back
     * untouched. Valid gzip requires magic 1f 8b, the deflate method byte,
     * and the full 10-byte header — anything shorter cannot be a real gzip
     * stream (an empty one is already 20 bytes), so it passes through.
     */
    private fun sniffsAsGzip(src: PushbackInputStream): Boolean {
        val header = ByteArray(GZIP_HEADER_LEN)
        var n = 0
        while (n < header.size) {
            val b = src.read()
            if (b < 0) break
            header[n++] = b.toByte()
        }
        if (n > 0) src.unread(header, 0, n)
        return n == GZIP_HEADER_LEN &&
            header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() &&
            header[2] == 8.toByte()
    }

    /**
     * Lazily peel every gzip layer of [src]. Each layer is validated by
     * peeking its header: a plausible gzip header wraps a
     * [GZIPInputStream]; anything else passes through untouched. Decoding
     * stays streaming (EventSource and large bodies never buffer), and the
     * header is fully validated before the (eagerly-parsing) GZIP ctor ever
     * touches the stream, so a failed sniff can never consume bytes.
     */
    fun peelStreaming(src: InputStream, maxLayers: Int = MAX_LAYERS): InputStream {
        var current: InputStream = src
        repeat(maxLayers) {
            val pushed = PushbackInputStream(current, GZIP_HEADER_LEN)
            if (!sniffsAsGzip(pushed)) return pushed
            current = GZIPInputStream(pushed)
        }
        return current
    }

    /** Eager variant for fully-buffered bodies (JS bridge path, tests). */
    fun gunzipAll(data: ByteArray): ByteArray {
        var current = data
        repeat(MAX_LAYERS) {
            if (!hasGzipMagic(current)) return current
            current = runCatching {
                GZIPInputStream(ByteArrayInputStream(current)).use { it.readBytes() }
            }.getOrNull() ?: return current
        }
        return current
    }

    /** Filter upstream response headers for the WebView-facing map. */
    fun filterResponseHeaders(raw: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((name, value) in raw) {
            val lower = name.lowercase()
            if (lower in RESPONSE_DROP) continue
            if (lower == "set-cookie") continue // re-collected below, must not collapse
            // First value wins; later duplicates of the same header collapse.
            out.putIfAbsent(lower, value)
        }
        // set-cookie must not collapse: keep every occurrence, newline-joined.
        val setCookies = raw.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second }
        if (setCookies.isNotEmpty()) out["set-cookie"] = setCookies.joinToString("\n")
        return out
    }

    /** Filter the request headers the WebView wanted, for the origin call. */
    fun filterRequestHeaders(raw: Map<String, String>): Map<String, String> =
        raw.filterKeys { it.lowercase() !in REQUEST_DROP }

    /** A non-empty status reason for [code] (WebResourceResponse requires one). */
    fun reasonPhrase(code: Int, upstream: String?): String {
        if (!upstream.isNullOrBlank()) return upstream
        return when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"
            304 -> "Not Modified"; 307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"
            503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
            else -> "Status $code"
        }
    }
}

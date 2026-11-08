package com.dsh.mobile.session

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * WebView network interceptor that routes session-origin traffic through
 * OkHttp with transparent multi-layer gzip peeling — the app-side workaround
 * for the dsh-market relay compression bug (see [TunnelHttp]).
 *
 * Two transports:
 *  - [intercept]: wired into `WebViewClient.shouldInterceptRequest`. Covers
 *    every body-less request (navigations, GET/HEAD/OPTIONS, subresources,
 *    EventSource). Response bytes are sniffed and gzip layers peeled as a
 *    lazy stream — SSE and large bodies never buffer.
 *  - [Bridge.http]: a synchronous `@JavascriptInterface` call used by the
 *    injected fetch patch (`assets/dsh-http-patch.js`) for body-bearing
 *    methods (POST/PUT/PATCH/DELETE) — `shouldInterceptRequest` cannot read
 *    their bodies, so the page hands body+headers to native and gets a
 *    complete decoded response back.
 *
 * Cookies stay owned by the [CookieManager]: request cookies are attached
 * from it, every upstream `set-cookie` (all redirect hops — OkHttp follows
 * redirects internally) is written back into it, so an intercepted
 * `/pair-accept` still issues the `dsh_pair` device cookie.
 *
 * Failure policy: a session-origin request must NEVER fall back to the
 * WebView network stack — the relay serves it double-compressed bytes
 * (zstd wrapping gzip, no matching header), which render as garbage. DNS
 * and connect errors are retried with backoff; whatever still fails is
 * surfaced as an error response the page can act on.
 */
class TunnelProxy(private val origin: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ResilientDns()) // resolver intermittently returns empty answers for the tunnel host
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE streams stay open
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true) // stale-connection / DNS races re-dial
        .build()

    /** The JS-facing half; one instance per proxied WebView. */
    val bridge = Bridge()

    /** Origin (scheme://host[:port]) used for the same-origin gate. */
    private val originUri: java.net.URI? = runCatching { java.net.URI(origin) }.getOrNull()

    /** Entry point for WebViewClient.shouldInterceptRequest (null = let WebView handle it). */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val scheme = url.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (!isSessionOrigin(url.toString())) return null
        val result = executeWithRetry(request.method.uppercase(), url.toString(), request.requestHeaders ?: emptyMap(), null)
        return result.toWebResourceResponse()
    }

    /** Whether [url] points at the session origin this proxy serves. */
    fun isSessionOrigin(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = parsed.host ?: return false
        val originHost = originUri?.host ?: return false
        if (!host.equals(originHost, ignoreCase = true)) return false
        val originPort = originUri.port.takeIf { it > 0 } ?: defaultPort(originUri.scheme)
        val port = parsed.port.takeIf { it > 0 } ?: defaultPort(parsed.scheme)
        return port == originPort &&
            parsed.scheme.equals(originUri?.scheme, ignoreCase = true)
    }

    private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    private class CallResult(
        val status: Int,
        val reason: String,
        val headers: Map<String, String>,
        val mime: String?,
        val encoding: String?,
        val body: java.io.InputStream?,
    )

    /**
     * Retries a proxied call a few times (DNS/connect flakes on the tunnel
     * path) and, if everything fails, returns a synthetic error response
     * instead of throwing. Throwing out of [intercept] makes WebView fall
     * back to its own stack — which gets the relay's double-compressed bytes.
     */
    private fun executeWithRetry(method: String, url: String, wantedHeaders: Map<String, String>, body: ByteArray?, attempts: Int = 3): CallResult {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return execute(method, url, wantedHeaders, body)
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) {
                    try {
                        Thread.sleep(250L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        return errorResult(lastError)
    }

    /** A 502-shaped response for a tunnel failure, body hinting at retry. */
    private fun errorResult(error: Exception?): CallResult {
        val message = (error?.message ?: error?.javaClass?.simpleName ?: "tunnel failure")
            .take(200)
        val html = "<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"3\">" +
            "<body style=\"font-family:sans-serif;background:#111;color:#eee;padding:2em\">" +
            "<h2>Tunnel error</h2><p>${message.replace("<", "&lt;")}</p><p>Retrying…</p>"
        val bodyStream = java.io.ByteArrayInputStream(html.toByteArray())
        return CallResult(
            status = 502,
            reason = "Bad Gateway",
            headers = mapOf("content-type" to "text/html; charset=utf-8", "cache-control" to "no-store"),
            mime = "text/html",
            encoding = null,
            body = bodyStream,
        )
    }

    /** Perform one proxied call and map it onto WebView/JS shapes. */
    private fun execute(method: String, url: String, wantedHeaders: Map<String, String>, body: ByteArray?): CallResult {
        val cm = CookieManager.getInstance()
        val requestHeaders = TunnelHttp.filterRequestHeaders(wantedHeaders).toMutableMap()
        requestHeaders.remove("cookie") // CookieManager is the single source of truth
        cm.getCookie(url)?.let { requestHeaders["cookie"] = it }

        val builder = Request.Builder()
            .url(url)
            .method(method, body?.let { bytes ->
                val type = requestHeaders["content-type"]?.toMediaTypeOrNull()
                bytes.toRequestBody(type)
            })
        for ((name, value) in requestHeaders) {
            runCatching { builder.header(name, value) }
        }
        // Never negotiate compression: the relay mangles encoded responses.
        builder.header("accept-encoding", "identity")

        val res = client.newCall(builder.build()).execute()
        try {
            val rawPairs = buildList {
                val hs = res.headers
                for (i in 0 until hs.size) add(hs.name(i) to hs.value(i))
            }
            // Preserve the pair-chain device cookie issued on intercepted hops.
            for ((name, value) in rawPairs) {
                if (name.equals("set-cookie", ignoreCase = true)) {
                    runCatching { cm.setCookie(url, value) }
                }
            }
            val headers = TunnelHttp.filterResponseHeaders(rawPairs)
            val contentType = res.header("content-type")
            val mime = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            // The GUI declares charset only in the Content-Type header (no early
            // <meta charset>), and WebResourceResponse(mime, encoding, …) does
            // NOT inherit it from the mime string — pass it explicitly or
            // Chromium falls back to windows-1252 and every CJK glyph mojibakes.
            val encoding = contentType?.substringAfter("charset=", "")
                ?.substringBefore(';')?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
                ?: if (mime?.startsWith("text/") == true) "utf-8" else null
            val src = res.body?.byteStream()
                ?: return CallResult(res.code, TunnelHttp.reasonPhrase(res.code, res.message), headers, mime, null, null)
            val peeled = TunnelHttp.peelStreaming(src)
            return CallResult(
                res.code, TunnelHttp.reasonPhrase(res.code, res.message), headers, mime, encoding, peeled,
            )
        } catch (error: Exception) {
            runCatching { res.close() }
            throw error
        }
    }

    private fun CallResult.toWebResourceResponse(): WebResourceResponse {
        val response = WebResourceResponse(mime, encoding, body)
        response.setStatusCodeAndReasonPhrase(status, reason)
        response.responseHeaders = headers
        return response
    }

    /** Synchronous JS bridge: method/url/headers/body-in → decoded response JSON out. */
    inner class Bridge {
        @JavascriptInterface
        fun http(method: String, url: String, headersJson: String, bodyB64: String?): String {
            val out = JSONObject()
            try {
                val wanted = HashMap<String, String>()
                if (headersJson.isNotBlank()) {
                    val parsed = JSONObject(headersJson)
                    for (key in parsed.keys()) wanted[key] = parsed.optString(key)
                }
                val body = bodyB64?.takeIf { it.isNotEmpty() }
                    ?.let { Base64.decode(it, Base64.NO_WRAP) }
                val methodU = method.uppercase()
                val result = if (methodU == "GET" || methodU == "HEAD" || methodU == "OPTIONS") {
                    executeWithRetry(methodU, url, wanted, null)
                } else {
                    executeWithRetry(methodU, url, wanted, body ?: ByteArray(0))
                }
                val bytes = result.body?.use { it.readBytes() } ?: ByteArray(0)
                out.put("status", result.status)
                out.put("reason", result.reason)
                out.put("headers", JSONObject(result.headers))
                out.put("bodyB64", if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (error: Exception) {
                out.put("status", 0)
                out.put("error", error.message ?: error.javaClass.simpleName)
            }
            return out.toString()
        }
    }
}

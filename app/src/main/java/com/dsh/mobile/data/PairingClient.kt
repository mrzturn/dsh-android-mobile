package com.dsh.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin native client for the public surface of the dsh-remote-web-ui plugin:
 * GET /api/pair/status (unauthenticated reachability + phase probe) and
 * POST /api/pair/issue is intentionally NOT called from the phone — minting
 * tokens is a desktop (loopback-only) control plane action.
 */
class PairingClient {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Browser-like UA so the host derives a sensible device label on pair. */
    val pairingUserAgent: String =
        "Mozilla/5.0 (Linux; Android 16; SM-F766B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 DshMobile/1.0"

    /**
     * Probe GET <code>{base}/api/pair/status</code>.
     * @return the parsed status, or null when the host is unreachable/refusing.
     */
    suspend fun probeStatus(baseUrl: String): PairStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(normalizeBase(baseUrl) + "/api/pair/status")
                .header("User-Agent", pairingUserAgent)
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val body = res.body?.string() ?: return@use null
                json.decodeFromString<PairStatus>(body)
            }
        }.getOrNull()
    }

    /** Normalized origin (scheme://host[:port], no trailing slash). */
    fun normalizeBase(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }
}

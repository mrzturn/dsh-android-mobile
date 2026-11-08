package com.dsh.mobile.session

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * OkHttp DNS that rides out the flaky resolver observed on the field: the
 * phone's network DNS intermittently returns empty answers for the relay's
 * random tunnel subdomains (verified live: 2 of 5 probes got no answer).
 *
 * Strategy:
 *  1. retry the underlying resolver a few times — an empty answer returns
 *     immediately, so tight retries cost little;
 *  2. fall back to the last address set that previously resolved. The relay
 *     sits behind Cloudflare, whose anycast addresses are stable, so the
 *     cached set stays valid for the lifetime of a session.
 *
 * Without this, one transient resolve failure used to bubble up out of
 * [TunnelProxy.execute] and — via the interceptor's old catch-and-return-null
 * — handed the request to the WebView network stack, which received the
 * relay's double-compressed bytes: the original garbled-rendering bug.
 */
class ResilientDns(
    private val base: Dns = Dns.SYSTEM,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 150,
) : Dns {

    /** Hostname → last non-empty answer the system resolver produced. */
    private val lastKnownGood = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        var last: UnknownHostException? = null
        repeat(attempts) { attempt ->
            try {
                val answer = base.lookup(hostname)
                if (answer.isNotEmpty()) {
                    lastKnownGood[hostname] = answer
                    return answer
                }
                last = UnknownHostException("empty DNS answer for $hostname")
            } catch (e: UnknownHostException) {
                last = e
            }
            if (attempt < attempts - 1) {
                try {
                    Thread.sleep(retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return lastKnownGood[hostname]?.takeIf { it.isNotEmpty() }
                        ?: throw (last ?: UnknownHostException(hostname))
                }
            }
        }
        lastKnownGood[hostname]?.takeIf { it.isNotEmpty() }?.let { return it }
        throw last ?: UnknownHostException(hostname)
    }
}

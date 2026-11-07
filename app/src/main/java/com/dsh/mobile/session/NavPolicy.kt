package com.dsh.mobile.session

import java.net.URI

/**
 * Main-frame navigation decision for the session WebView.
 *
 * The plugin's pair boot flow redirects /pair-accept?pair=<token> to an
 * ABSOLUTE /pair-app?grant=<one-time> URL on the server's canonical base.
 * When the phone reached the server under a different address (typed host,
 * second NIC, VPN alias) that redirect is cross-host — throwing it to an
 * external browser would strand the one-time grant there and break pairing,
 * because the dsh_pair device cookie must be set inside OUR WebView for the
 * session origin the app polls.
 *
 * Rule: while pairing, keep /pair* main-frame hops in-app; when the hop
 * lands on a different origin, adopt that origin as the session base (the
 * server's canonical address is the address the rest of the session uses).
 * After pairing, any cross-host hop goes external as before.
 */
enum class NavAction { OPEN_INTERNAL, OPEN_EXTERNAL, ADOPT_BASE }

object NavPolicy {

    private val PAIR_PATH = Regex("^/pair(?:-app|-accept|-ping)?(?:[/?#]|$)")

    fun decide(
        baseHost: String?,
        targetUrl: String,
        pairing: Boolean,
    ): Triple<NavAction, String, String?> {
        val uri = runCatching { URI(targetUrl) }.getOrNull()
            ?: return Triple(NavAction.OPEN_EXTERNAL, targetUrl, null)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "http" && scheme != "https") {
            return Triple(NavAction.OPEN_EXTERNAL, targetUrl, null)
        }
        val sameHost = host != null && host == baseHost
        if (sameHost) return Triple(NavAction.OPEN_INTERNAL, targetUrl, null)
        val isPairHop = pairing && uri.path != null && PAIR_PATH.containsMatchIn(uri.path)
        if (isPairHop) {
            val port = if (uri.port > 0) ":" + uri.port else ""
            val newBase = scheme + "://" + host + port
            return Triple(NavAction.ADOPT_BASE, targetUrl, newBase)
        }
        return Triple(NavAction.OPEN_EXTERNAL, targetUrl, null)
    }
}
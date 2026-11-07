package com.dsh.mobile.pairing

import com.dsh.mobile.data.PairAttempt
import com.dsh.mobile.data.PairingClient

/**
 * Parse user material (scanned QR content, pasted link, or host + key)
 * into a [PairAttempt].
 *
 * Recognized shapes:
 *  - "http://host:port/pair-accept?pair=<token>"  (QR link, http or https)
 *  - "http://host:port/?pair=<token>"             (same, different path)
 *  - "https://xxx.dsh-market.com/pair-accept?pair=<token>" (relay/tunnel)
 *  - "http://host:port/?key=<browser-auth>"       (direct harness auth link)
 *  - bare 32-hex token (paired with a separately provided base URL)
 */
object PairInput {

    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    /** @return the attempt, or null when nothing parseable was found. */
    fun parse(text: String): PairAttempt? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Pull the first URL out of arbitrary surrounding text.
        val urlMatch = Regex("https?://[^\\s'\"<>]+").find(trimmed)
        if (urlMatch != null) {
            val url = urlMatch.value.trimEnd(',', ')', ']')
            return fromUrl(url)
        }
        if (HEX32.matches(trimmed)) {
            return PairAttempt.TokenWithHost("", trimmed.lowercase())
        }
        return null
    }

    /** Interpret one URL; [fromUrl] with a separate host fills TokenWithHost. */
    fun fromUrl(url: String): PairAttempt? {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val base = PairingClient().normalizeBase(
            (parsed.scheme ?: "http") + "://" + (parsed.host ?: return null) +
                (if (parsed.port > 0) ":${parsed.port}" else "")
        )
        val query = parsed.rawQuery ?: ""
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val pair = params["pair"] ?: params["token"]
        if (!pair.isNullOrBlank()) {
            val clean = java.net.URLDecoder.decode(pair, "UTF-8")
            if (HEX32.matches(clean)) return PairAttempt.PairLink("$base/pair-accept?pair=$clean", base, clean)
        }
        // key= link or any other harness URL → direct open (server decides).
        return PairAttempt.Direct(url.trim(), base)
    }

    /** Combine a bare token with a base URL field. */
    fun combine(baseRaw: String, token: String): PairAttempt? {
        val base = runCatching { PairingClient().normalizeBase(baseRaw) }.getOrNull() ?: return null
        if (base.length < 12) return null
        return PairAttempt.PairLink("$base/pair-accept?pair=$token", base, token)
    }
}

package com.dsh.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One saved DSH instance the phone has connected to. */
@Serializable
data class Connection(
    /** Stable local id (uuid-ish). */
    val id: String,
    /** User-visible label, e.g. "Workstation · LAN". */
    val label: String,
    /** Normalized origin, e.g. "http://192.168.1.10:3080" (no trailing slash). */
    val baseUrl: String,
    /** "lan" or "tunnel" — cosmetic only. */
    val kind: String = "lan",
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    val hostLabel: String
        get() = baseUrl.removePrefix("https://").removePrefix("http://")
}

/** Response of GET /api/pair/status (public; no credentials needed). */
@Serializable
data class PairStatus(
    val ok: Boolean = false,
    val paired: Boolean = false,
    val phase: String = "unknown",
    val requirePairingForLan: Boolean = true,
    val lanAvailable: Boolean = false,
    val deviceCount: Int = 0,
    val onlineCount: Int = 0,
    val tokenExpiresAt: Long? = null,
) {
    /** Whether the desktop currently advertises a live pairing QR window. */
    val pairingOpen: Boolean get() = phase == "waiting"
    /** Whether at least one device (possibly this phone) holds a session. */
    val anyDeviceConnected: Boolean get() = phase == "connected"
}

/** How the user provided the pairing material. */
sealed interface PairAttempt {
    /** A full pair link (scanned QR or pasted URL) carrying ?pair=<token>. */
    data class PairLink(val url: String, val baseUrl: String, val token: String) : PairAttempt
    /** A bare 32-hex key + a server base URL. */
    data class TokenWithHost(val baseUrl: String, val token: String) : PairAttempt
    /** Any other harness URL (e.g. ?key= browser-auth link) — open directly. */
    data class Direct(val url: String, val baseUrl: String) : PairAttempt
}

/** Shared JSON instance for persistence and wire decoding. */
val AppJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

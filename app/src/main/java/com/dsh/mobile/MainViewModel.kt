package com.dsh.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.appGraph
import com.dsh.mobile.data.Connection
import com.dsh.mobile.data.PairAttempt
import com.dsh.mobile.data.PairStatus
import com.dsh.mobile.pairing.PairInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Top-level navigation state. */
sealed interface Route {
    data object Home : Route
    data object Scanner : Route
    data class Session(val connection: Connection, val entryUrl: String, val pairing: Boolean) : Route
}

/** One transient snackbar message. */
data class Toast(val text: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph

    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route

    /** Saved connections (passthrough from the store). */
    val connections: StateFlow<List<Connection>> get() = graph.connectionStore.connections

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast

    /** Pre-checked reachability per origin (home screen chips). */
    val statusCache = MutableStateFlow<Map<String, PairStatus>>(emptyMap())

    /** Connection origins currently being probed (spinner on the card). */
    val probing = MutableStateFlow<Set<String>>(emptySet())

    fun consumeToast() { _toast.value = null }

    fun toast(text: String) { _toast.value = Toast(text) }

    fun goHome() { _route.value = Route.Home }

    fun openScanner() { _route.value = Route.Scanner }

    /** Entry point for a parsed attempt (QR result, paste, manual). */
    fun start(attempt: PairAttempt) {
        when (attempt) {
            is PairAttempt.PairLink -> {
                val conn = graph.connectionStore.upsert(attempt.baseUrl, kind = kindOf(attempt.baseUrl))
                _route.value = Route.Session(conn, attempt.url, pairing = true)
            }
            is PairAttempt.TokenWithHost -> {
                if (attempt.baseUrl.isBlank()) {
                    _toast.value = Toast("识别到配对 Key，但还需要服务器地址——请用“链接 + Key”方式输入")
                } else {
                    val link = PairInput.combine(attempt.baseUrl, attempt.token)
                    if (link is PairAttempt.PairLink) start(link)
                }
            }
            is PairAttempt.Direct -> {
                val conn = graph.connectionStore.upsert(attempt.baseUrl, kind = kindOf(attempt.baseUrl))
                _route.value = Route.Session(conn, attempt.url, pairing = false)
            }
        }
    }

    /** Reopen an existing connection (device cookie should still be live). */
    fun reconnect(connection: Connection) {
        _route.value = Route.Session(connection, connection.baseUrl + "/pair-app", pairing = false)
    }

    /** Drop the stored device credential for a connection (local sign-out). */
    fun forget(connection: Connection) {
        graph.connectionStore.forget(connection.id)
        clearCookiesFor(connection.baseUrl)
    }

    fun rename(connection: Connection, label: String) {
        graph.connectionStore.rename(connection.id, label)
    }

    fun clearCookiesFor(baseUrl: String) {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setCookie(baseUrl, "dsh_pair=; Path=/; Max-Age=0")
        cm.setCookie(baseUrl, "dsh-remote-device=; Path=/; Max-Age=0")
        cm.flush()
    }

    /** Probe one origin's /api/pair/status for the home card. */
    fun probe(connection: Connection) {
        if (probing.value.contains(connection.baseUrl)) return
        probing.value = probing.value + connection.baseUrl
        viewModelScope.launch {
            val st = graph.pairingClient.probeStatus(connection.baseUrl)
            st?.let { statusCache.value = statusCache.value + (connection.baseUrl to it) }
            probing.value = probing.value - connection.baseUrl
        }
    }

    fun probeAll() {
        graph.connectionStore.connections.value.forEach { probe(it) }
    }

    /** Handle content shared/scanned/pasted into the app from outside. */
    fun handleIncoming(text: String?) {
        if (text.isNullOrBlank()) return
        val attempt = PairInput.parse(text)
        if (attempt == null) {
            _toast.value = Toast("无法识别的内容——需要配对链接或 32 位配对 Key")
        } else {
            start(attempt)
        }
    }

    /** Handle an http(s) VIEW intent pointing at a pair link. */
    fun handleViewUri(uri: Uri?) {
        val s = uri?.toString() ?: return
        handleIncoming(s)
    }

    private fun kindOf(base: String) = if (base.startsWith("https://")) "tunnel" else "lan"
}

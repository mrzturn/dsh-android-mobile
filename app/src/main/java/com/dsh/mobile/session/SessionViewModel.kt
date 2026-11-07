package com.dsh.mobile.session

import android.app.Application
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.appGraph
import com.dsh.mobile.data.Connection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Load state of the WebView session. */
sealed interface SessionLoad {
    data object Idle : SessionLoad
    data class Loading(val progress: Int) : SessionLoad
    data class Failed(val code: Int, val description: String?, val url: String) : SessionLoad
    data object Ready : SessionLoad
}

/**
 * Holds one live WebView session against a DSH host. The ViewModel survives
 * configuration changes; the WebView itself survives too (retained in the
 * activity across rotation via [WebViewCache]).
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph

    private val _load = MutableStateFlow<SessionLoad>(SessionLoad.Idle)
    val load: StateFlow<SessionLoad> = _load

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    /** Set when the entry URL failed hard (unreachable host) for a retry UI. */
    val lastFailureUrl = MutableStateFlow<String?>(null)

    /**
     * True when the server served the "Pairing link invalid" refusal page
     * (HTTP 200): the device cookie or grant is stale/revoked. The session
     * UI then offers a jump back to the scanner instead of a dead page.
     */
    val stalePairing = MutableStateFlow(false)

    var connection: Connection? = null

    /** True while the pair boot flow (token -> grant -> device cookie) is in flight. */
    var pairing: Boolean = true

    fun bind(connection: Connection) {
        this.connection = connection
        pairing = true
    }

    /**
     * Adopt the server's canonical origin mid-pairing (cross-host grant
     * redirect): re-point the session at the base the server itself chose,
     * so the device cookie and later polls live on the same origin.
     */
    fun adoptBase(newBase: String) {
        val conn = connection ?: return
        if (conn.baseUrl == newBase) return
        val moved = graph.connectionStore.upsert(
            newBase,
            kind = conn.kind,
            label = conn.label,
        )
        connection = moved
    }

    fun markPaired() { pairing = false }

    fun onProgress(progress: Int) {
        _load.value = if (progress in 1..99) SessionLoad.Loading(progress) else _load.value
    }

    fun onPageFinished(view: WebView, url: String?) {
        _load.value = SessionLoad.Ready
        _pageTitle.value = view.title ?: ""
        _canGoBack.value = view.canGoBack()
        lastFailureUrl.value = null
        // The host serves the refusal as a 200 page with this fixed title.
        stalePairing.value = view.title?.trim() == "Pairing link invalid"
        CookieManager.getInstance().flush()
    }

    fun onReceivedError(code: Int, description: String?, failingUrl: String?) {
        // Only surface errors for the main frame.
        if (failingUrl != null && connection != null && failingUrl.startsWith(connection!!.baseUrl)) {
            _load.value = SessionLoad.Failed(code, description, failingUrl)
            lastFailureUrl.value = failingUrl
        }
    }

    fun retry(view: WebView?) {
        val url = lastFailureUrl.value ?: view?.url ?: return
        _load.value = SessionLoad.Loading(0)
        view?.loadUrl(url)
    }

    /** Local sign-out: drop the device cookie, then ask the UI to go home. */
    fun clearCredentialsAndBack() {
        val base = connection?.baseUrl ?: return
        val cm = CookieManager.getInstance()
        cm.setCookie(base, "dsh_pair=; Path=/; Max-Age=0")
        cm.setCookie(base, "dsh-remote-device=; Path=/; Max-Age=0")
        cm.flush()
        routeExit.value = true
    }

    /** One-shot flag the screen consumes to navigate home after sign-out. */
    val routeExit = MutableStateFlow(false)
    fun consumeExit() { routeExit.value = false }

    override fun onCleared() {
        // Keep cookies (device sessions) — they are the persisted credential.
        CookieManager.getInstance().flush()
        super.onCleared()
    }
}

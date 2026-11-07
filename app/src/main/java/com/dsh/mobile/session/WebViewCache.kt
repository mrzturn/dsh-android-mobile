package com.dsh.mobile.session

import android.webkit.WebView

/**
 * Retains the single live session WebView across activity recreations
 * (rotation, fold/unfold on the Z Flip 7) so the official Web GUI keeps its
 * state. Destroyed when the activity truly finishes.
 */
object WebViewCache {
    private var retained: WebView? = null
    private var origin: String? = null

    fun get(origin: String): WebView? = retained?.takeIf { this.origin == origin }

    fun put(origin: String, webView: WebView) {
        destroy()
        retained = webView
        this.origin = origin
    }

    fun destroyIfOrigin(origin: String) {
        if (this.origin == origin) destroy()
    }

    fun destroy() {
        retained?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.removeAllViews()
                wv.destroy()
            }
        }
        retained = null
        origin = null
    }
}

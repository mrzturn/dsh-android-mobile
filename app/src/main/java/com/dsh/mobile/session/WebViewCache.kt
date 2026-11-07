package com.dsh.mobile.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.webkit.WebView

/**
 * Retains the single live session WebView across activity recreations
 * (rotation, fold/unfold on the Z Flip 7) so the official Web GUI keeps its
 * state. Destroyed when the activity truly finishes.
 *
 * The WebView is created with a [MutableContextWrapper]; on reuse the wrapper
 * is rebound to the new activity's base context, so the retained instance
 * never pins a destroyed activity (lint StaticFieldLeak).
 */
@SuppressLint("StaticFieldLeak") // intentional singleton: WebView survives activity recreation via MutableContextWrapper rebinding; destroyed on disconnect (SessionScreen)
object WebViewCache {
    private var retained: WebView? = null
    private var origin: String? = null

    /** Wrap creation context so it can be re-targeted on activity recreation. */
    fun newCreationContext(activityContext: Context): MutableContextWrapper =
        MutableContextWrapper(activityContext)

    /** Returns the cached WebView (if any), rebound to the current context. */
    fun get(origin: String, activityContext: Context): WebView? =
        retained?.takeIf { this.origin == origin }?.also {
            (it.context as? MutableContextWrapper)?.baseContext = activityContext
        }

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
                (wv.context as? MutableContextWrapper)?.baseContext =
                    wv.context.applicationContext
                wv.destroy()
            }
        }
        retained = null
        origin = null
    }
}

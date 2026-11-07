package com.dsh.mobile.session

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Native side of the FlexPad bridge. The injected assets/flexpad.js exposes
 * window.DshMobileFlexpad; native touchpad/quick keys call into it through
 * evaluateJavascript, and JS calls back into the @JavascriptInterface methods
 * here. Bridge methods arrive on a dedicated thread, so UI work hops to main.
 */
class FlexPadBridge {

    private var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var metricsListener: ((JSONObject) -> Unit)? = null

    /** WebView viewport in CSS pixels, published by SessionScreen. */
    @Volatile var viewportW: Double = 0.0
    @Volatile var viewportH: Double = 0.0

    /** Convenience pair for compose state reads. */
    val viewportPair: Pair<Float, Float>
        get() = Pair(viewportW.toFloat(), viewportH.toFloat())

    fun setViewport(wCss: Float, hCss: Float) {
        viewportW = wCss.toDouble(); viewportH = hCss.toDouble()
    }

    companion object {
        // The bridge script (assets/flexpad.js) is injected INLINE by
        // DshWebView on every page start — a file:// script tag would be
        // blocked as mixed content on https (tunnel) origins.
    }

    fun attach(view: WebView) {
        webView = view
    }

    fun detach() {
        webView = null
    }

    fun onMetrics(listener: (JSONObject) -> Unit) {
        metricsListener = listener
    }

    /** Evaluate JS on the UI thread and ignore failures. */
    fun evaluate(script: String) {
        main.post {
            val view = webView ?: return@post
            view.evaluateJavascript(script, null)
        }
    }

    // ---- JS -> native (thread: JS bridge) ----

    @JavascriptInterface
    fun onBridgeReady(metricsJson: String) {
        val parsed = runCatching { JSONObject(metricsJson) }.getOrNull() ?: return
        main.post { metricsListener?.invoke(parsed) }
    }

    // ---- native -> JS convenience wrappers ----

    fun clickAt(x: Double, y: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.clickAt($x,$y)")

    fun move(x: Double, y: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.move($x,$y)")

    fun down(x: Double, y: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.down($x,$y)")

    fun up(x: Double, y: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.up($x,$y)")

    fun drag(x1: Double, y1: Double, x2: Double, y2: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.drag($x1,$y1,$x2,$y2,12)")

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.scrollBy($x,$y,$dx,$dy)")

    fun rightClick(x: Double, y: Double) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.rightClick($x,$y)")

    fun sendKey(key: String, keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean) =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.sendKey(" +
            "'" + key + "'," + keyCode + "," + ctrl + "," + shift + "," + alt + ")")

    fun focusComposer() =
        evaluate("window.DshMobileFlexpad && DshMobileFlexpad.focusComposer()")
}

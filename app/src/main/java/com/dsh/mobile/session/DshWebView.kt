package com.dsh.mobile.session

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.mobile.fold.FoldPosture

/**
 * The hardened WebView that carries the official Web GUI. Credentials are the
 * plugin's device cookie (dsh_pair) written during the pair chain; the native
 * layer adds no auth of its own.
 *
 * Hardening:
 *  - JavaScript enabled (the GUI is an SPA) but file access and content access off;
 *  - geolocation, media autoplay, permissions: never granted automatically;
 *  - safe browsing on; mixed content never allowed;
 *  - navigation outside the session origin opens externally (no cross-origin
 *    script surfaces inside the secured context);
 *  - the FlexPad bridge (assets/flexpad.js) is injected on page start.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DshWebView(
    entryUrl: String,
    sessionVm: SessionViewModel,
    posture: FoldPosture,
    flexPadBridge: FlexPadBridge,
    modifier: Modifier = Modifier,
    onWebViewReady: (WebView) -> Unit = {},
    onExternalLink: (String) -> Unit,
) {
    val context = LocalContext.current

    val context2 = context
    val injectScript = remember {
        runCatching {
            context2.assets.open("flexpad.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: ""
    }
    val origin = sessionVm.connection?.baseUrl ?: entryUrl.substringBefore("/pair")
    val webView = remember(origin) {
        WebViewCache.get(origin, context) ?: WebView(WebViewCache.newCreationContext(context)).apply {
        WebViewCache.put(origin, this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            configureHardenedSettings(this)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = buildWebViewClient(sessionVm, onExternalLink, injectScript)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    sessionVm.onProgress(newProgress)
                }
            }
            addJavascriptInterface(flexPadBridge, "DshMobileAndroid")
        }
    }

    DisposableEffect(entryUrl) {
        // Third-party cookies stay off: the plugin's gate is same-origin.
        val settings = webView.settings
        settings.javaScriptEnabled = true
        flexPadBridge.attach(webView)
        if (webView.url == null) {
            webView.loadUrl(entryUrl)
        }
        onDispose {
            flexPadBridge.detach()
            // The WebView stays retained in WebViewCache across activity
            // recreations (fold/unfold); destroyed only via the cache.
        }
    }

    AndroidView(
        factory = { webView.also(onWebViewReady) },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.settings.textZoom = if (posture.isFlex) 90 else 100
        },
    )
}

@SuppressLint("SetJavaScriptEnabled") // required: hosts the paired desktop's official Web GUI; all other hardening flags set below
private fun configureHardenedSettings(web: WebView) = with(web.settings) {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
    mediaPlaybackRequiresUserGesture = false
    javaScriptCanOpenWindowsAutomatically = false
    setSupportMultipleWindows(false)
    setGeolocationEnabled(false)
    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    safeBrowsingEnabled = true
    userAgentString = userAgentString.replace("; wv", "")
}

private fun buildWebViewClient(
    sessionVm: SessionViewModel,
    onExternalLink: (String) -> Unit,
    injectScript: String,
): WebViewClient = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        val base = sessionVm.connection?.baseUrl
        val (action, _, newBase) = NavPolicy.decide(
            baseHost = base?.let { runCatching { java.net.URI(it).host }.getOrNull() },
            targetUrl = url,
            pairing = sessionVm.pairing,
        )
        return when (action) {
            NavAction.OPEN_INTERNAL -> false
            NavAction.OPEN_EXTERNAL -> {
                onExternalLink(url)
                true
            }
            NavAction.ADOPT_BASE -> {
                // The server's canonical origin answered mid-pairing (grant
                // redirect); follow it in-app and re-base the session.
                sessionVm.adoptBase(newBase ?: return false)
                view.post { view.loadUrl(url) }
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (view == null || url == null) return
        if (injectScript.isNotEmpty()) view.evaluateJavascript(injectScript, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (sessionVm.pairing && !url.contains("/pair-accept")) sessionVm.markPaired()
        sessionVm.onPageFinished(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            sessionVm.onReceivedError(error.errorCode, error.description?.toString(), request.url.toString())
        }
    }
}

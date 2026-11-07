package com.dsh.mobile.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device tests: real WebView hardening + NavPolicy integration, run on the
 * emulator AVD (API 34) as the pre-install gate.
 */
@RunWith(AndroidJUnit4::class)
class SessionDeviceTest {

    @Test
    fun webviewHardeningFlagsAreSet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var settings: android.webkit.WebSettings? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        ctx.mainExecutor.execute {
            val wv = WebView(ctx)
            configureHardenedSettings(wv)
            settings = wv.settings
            wv.destroy()
            latch.countDown()
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val s = settings!!
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertEquals(false, s.allowFileAccess)
        assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, s.mixedContentMode)
        // cover-screen zoom affordances
        assertTrue(s.supportZoom())
        assertTrue(s.useWideViewPort)
    }

    @Test
    fun navPolicyAdoptsCanonicalBaseMidPairing() {
        val (action, _, newBase) = NavPolicy.decide(
            baseHost = "127.0.0.1",
            targetUrl = "http://192.168.1.10:3080/pair-app?grant=abc",
            pairing = true,
        )
        assertEquals(NavAction.ADOPT_BASE, action)
        assertEquals("http://192.168.1.10:3080", newBase)
    }

    @Test
    fun navPolicyStaysStrictAfterPairing() {
        val (action, _, newBase) = NavPolicy.decide(
            baseHost = "192.168.1.10",
            targetUrl = "http://example.com/pair-app?grant=x",
            pairing = false,
        )
        assertEquals(NavAction.OPEN_EXTERNAL, action)
        assertEquals(null, newBase)
    }
}
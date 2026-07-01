// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ichi2.utils.ViewGroupUtils.setRenderWorkaround

/**
 * Vantage readiness dashboard: shows the three scores with their ranges, exam
 * coverage, and the best next topic in a WebView, matching the desktop UI.
 *
 * The page is a self-contained asset (assets/vantage/index.html) rendered from
 * the same web files as the desktop add-on, so the phone and desktop look alike.
 */
class VantageDashboardActivity : AnkiActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true
        setRenderWorkaround(this)
        webView.addJavascriptInterface(Bridge(webView), "pycmdBridge")
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    // Wire the in-page Back/Refresh buttons (they call pycmd) to Android.
                    view.evaluateJavascript(
                        "window.pycmd = function(c){ pycmdBridge.invoke(c); };",
                        null,
                    )
                }
            }
        webView.loadUrl("file:///android_asset/vantage/index.html")
    }

    private inner class Bridge(
        val webView: WebView,
    ) {
        @JavascriptInterface
        fun invoke(cmd: String) {
            // Runs on a binder thread; hop to the UI thread for view operations.
            runOnUiThread {
                when (cmd) {
                    "vantage:back" -> finish()
                    "vantage:refresh" -> webView.reload()
                }
            }
        }
    }

    companion object {
        fun getIntent(context: Context): Intent = Intent(context, VantageDashboardActivity::class.java)
    }
}

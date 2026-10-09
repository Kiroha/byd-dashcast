package com.byd.dashcast.satellite

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import com.byd.dashcast.R
import com.byd.dashcast.util.LocaleHelper
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference

/** Receive-only WebRTC viewer. Loaded exclusively from a bundled page, never from the network. */
@Suppress("DEPRECATION") // API 28/29 need these display and fullscreen APIs.
class SatelliteVideoActivity : Activity(), SatelliteVideoHub.Viewer {
    private var webView: WebView? = null
    private var ready = false
    private var visible = false
    private var session: String? = null
    private val main = Handler(Looper.getMainLooper())

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.applyLocale(base))

    @SuppressLint("SetJavaScriptEnabled") // Only bundled HTML; network/file navigation is blocked.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SatellitePrefs.isEnabled(this) ||
            (intent.hasExtra(EXPECTED_DISPLAY) && windowManager.defaultDisplay.displayId != intent.getIntExtra(EXPECTED_DISPLAY, -1))) {
            finish(); return
        }
        // A second preview must release the old decoder rather than retain two WebViews.
        current.get()?.finish()
        current = WeakReference(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        try {
            val view = WebView(this)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            view.addJavascriptInterface(Bridge(), "DashCastVideo")
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    unavailable()
                    return true
                }
            }
            setContentView(view)
            val html = assets.open("satellite_receiver.html").bufferedReader().use { it.readText() }
                .replace("__WAITING__", escapeHtml(getString(R.string.satellite_waiting)))
            view.loadDataWithBaseURL("https://satellite.dashcast.invalid/", html, "text/html", "UTF-8", null)
        } catch (_: Exception) { unavailable() }
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready(supported: Boolean) {
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (!supported) { unavailable(); return@post }
                ready = true
                if (visible) SatelliteVideoHub.attach(this@SatelliteVideoActivity)
            }
        }

        @JavascriptInterface
        fun send(id: String, message: String) {
            if (message.length > SatelliteProtocol.MAX_MESSAGE_BYTES) return
            SatelliteVideoHub.reply(this@SatelliteVideoActivity, id, message)
        }
    }

    override fun signal(session: String, message: JSONObject) {
        val json = message.toString()
        main.post {
            if (!ready || isFinishing || isDestroyed) return@post
            this.session = session
            webView?.evaluateJavascript("window.receiveSignal(${JSONObject.quote(session)},${JSONObject.quote(json)})", null)
        }
    }

    override fun reset() {
        main.post {
            session = null
            if (!isFinishing && !isDestroyed) webView?.evaluateJavascript("window.resetReceiver()", null)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (ready) SatelliteVideoHub.attach(this)
    }

    override fun onStop() {
        visible = false
        SatelliteVideoHub.detach(this)
        reset()
        super.onStop()
    }

    private fun unavailable() {
        Toast.makeText(this, R.string.satellite_unavailable, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        ready = false
        SatelliteVideoHub.detach(this)
        main.removeCallbacksAndMessages(null)
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            try { removeJavascriptInterface("DashCastVideo"); stopLoading(); destroy() } catch (_: Exception) {}
        }
        webView = null
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    companion object {
        const val EXPECTED_DISPLAY = "satellite_expected_display"
        private var current = WeakReference<SatelliteVideoActivity>(null)
        fun closeViewer() { current.get()?.finish() }
        private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

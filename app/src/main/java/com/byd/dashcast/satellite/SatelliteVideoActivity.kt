package com.byd.dashcast.satellite

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.util.LocaleHelper
import com.byd.dashcast.util.AppLogger
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
    private var showMetrics = false
    private enum class Bootstrap { STARTING, READY, FAILED }
    private var bootstrap = Bootstrap.STARTING
    private var generation = 0L
    private var videoAttempt: Long? = null
    private var videoNegotiation: String? = null
    private val observationLock = Any()
    private data class PendingObservation(val generation: Long, val peer: Long,
        val value: SatelliteVideoDiagnostics.Observation, val terminal: Boolean)
    private var pendingObservation: PendingObservation? = null
    private var observationPosted = false

    private fun endVideoAttempt(end: SatelliteVideoDiagnostics.End) {
        videoAttempt?.let { SatelliteVideoDiagnostics.endAttempt(it, end) }
        videoAttempt = null
        videoNegotiation = null
        synchronized(observationLock) { pendingObservation = null }
    }
    private lateinit var container: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var stateLabel: TextView
    private lateinit var retryButton: Button
    private lateinit var providerLabel: TextView
    private var startupDeadline: Runnable? = null
    private val projectionGuard = object : Runnable {
        override fun run() {
            if (!visible || isFinishing || isDestroyed) return
            if (!validDestination()) { finish(); return }
            main.postDelayed(this, 500)
        }
    }
    @Suppress("DEPRECATION")
    private fun validDestination(): Boolean {
        if (!SatellitePrefs.isEnabled(this)) return false
        if (!intent.hasExtra(EXPECTED_DISPLAY)) return true // Main display preview is independent.
        val expected = intent.getIntExtra(EXPECTED_DISPLAY, -1)
        val cluster = ClusterService.getInstance()
        return SatelliteVideoDestination.allowed(expected, windowManager.defaultDisplay.displayId,
            cluster?.displayId ?: -1, cluster?.isProjectionActive() == true)
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.applyLocale(base))

    @SuppressLint("SetJavaScriptEnabled") // Only bundled HTML; network/file navigation is blocked.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!validDestination()) {
            finish(); return
        }
        // A second preview must release the old decoder rather than retain two WebViews.
        current.get()?.finish()
        current = WeakReference(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.BLACK)
        }
        stateLabel = TextView(this).apply {
            tag = "satellite_video_state"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        retryButton = Button(this).apply {
            tag = "satellite_video_retry"
            setText(R.string.satellite_pair_retry)
            setOnClickListener { startViewer() }
        }
        overlay.addView(stateLabel)
        providerLabel = TextView(this).apply {
            tag = "satellite_video_provider"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        overlay.addView(providerLabel)
        overlay.addView(retryButton)
        overlay.addView(Button(this).apply {
            setText(R.string.consent_close)
            setOnClickListener { finish() }
        })
        container.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(container)
        startViewer()
    }

    @SuppressLint("SetJavaScriptEnabled") // Only bundled HTML; network/file navigation is blocked.
    private fun startViewer() {
        if (isFinishing || isDestroyed || !validDestination()) return
        releaseWebView()
        bootstrap = Bootstrap.STARTING
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.VIEWER_STARTING)
        ready = false
        stateLabel.setText(R.string.satellite_video_loading)
        retryButton.visibility = View.GONE
        providerLabel.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        val attempt = generation
        try {
            val view = WebView(this)
            webView = view
            SatelliteVideoDiagnostics.providerVersion(runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull())
            view.settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
            }
            showMetrics = getPreferences(Context.MODE_PRIVATE).getBoolean("video_metrics", false)
            // Opt-in diagnostic overlay. Configure on the main display before opening the cluster.
            if (!intent.hasExtra(EXPECTED_DISPLAY)) view.setOnLongClickListener {
                showMetrics = !showMetrics
                getPreferences(Context.MODE_PRIVATE).edit { putBoolean("video_metrics", showMetrics) }
                view.evaluateJavascript("window.setMetricsVisible($showMetrics)", null)
                true
            }
            view.addJavascriptInterface(Bridge(attempt), "DashCastVideo")
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                    error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) failed(attempt, "page_load_failed")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failed(attempt, "renderer_gone")
                    return true
                }
            }
            container.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            val html = assets.open("satellite_receiver.html").bufferedReader().use { it.readText() }
                .replace("__WAITING__", escapeHtml(getString(R.string.satellite_video_waiting)))
            view.loadDataWithBaseURL("https://satellite.dashcast.invalid/", html, "text/html", "UTF-8", null)
            watchStartup()
        } catch (_: Exception) { failed(attempt, "webview_create_failed") }
        catch (_: LinkageError) { failed(attempt, "webview_linkage_failed") }
    }

    inner class Bridge(private val attempt: Long) {
        @JavascriptInterface
        fun ready(supported: Boolean) {
            main.post {
                if (attempt != generation || bootstrap != Bootstrap.STARTING || isFinishing || isDestroyed) return@post
                if (!supported) { failed(attempt, "webrtc_unavailable"); return@post }
                cancelStartupDeadline()
                bootstrap = Bootstrap.READY
                ready = true
                overlay.visibility = View.GONE
                AppLogger.i("SatelliteVideo", "bootstrap: ready")
                webView?.evaluateJavascript("window.setMetricsVisible($showMetrics)", null)
                if (visible) SatelliteVideoHub.attach(this@SatelliteVideoActivity)
            }
        }

        @JavascriptInterface
        fun send(peerAttempt: Long, id: String, message: String) {
            if (message.length > SatelliteProtocol.MAX_MESSAGE_BYTES) return
            main.post {
                if (attempt == generation && peerAttempt == videoAttempt && ready && visible && !isFinishing && !isDestroyed) {
                    SatelliteVideoHub.reply(this@SatelliteVideoActivity, id, message)
                    val json = runCatching { JSONObject(message) }.getOrNull()
                    if (json?.optString("type") == "video.error") endVideoAttempt(when (json.optString("code")) {
                        "video_timeout" -> SatelliteVideoDiagnostics.End.VIDEO_TIMEOUT
                        "playback_failed" -> SatelliteVideoDiagnostics.End.PLAYBACK_FAILED
                        "connection_lost" -> SatelliteVideoDiagnostics.End.CONNECTION_LOST
                        "negotiation_failed" -> SatelliteVideoDiagnostics.End.NEGOTIATION_FAILED
                        else -> SatelliteVideoDiagnostics.End.OTHER_FAILURE
                    })
                }
            }
        }

        @JavascriptInterface
        fun failed(code: String) {
            // Do not accept arbitrary console/error text, SDP or URLs into support logs.
            if (code == "script_error") main.post { failed(attempt, "script_error") }
        }

        @JavascriptInterface
        fun progress(peerAttempt: Long, code: String) {
            main.post {
                if (attempt == generation && peerAttempt == videoAttempt && ready && visible && !isFinishing && !isDestroyed)
                    SatelliteVideoDiagnostics.progress(code)
            }
        }

        @JavascriptInterface
        fun observe(peerAttempt: Long, raw: String) = enqueueObservation(peerAttempt, raw, false)

        @JavascriptInterface
        fun observeFinal(peerAttempt: Long, raw: String) = enqueueObservation(peerAttempt, raw, true)

        private fun enqueueObservation(peerAttempt: Long, raw: String, terminal: Boolean) {
            val observation = SatelliteVideoDiagnostics.Observation.parse(raw) ?: return
            // At most one main-thread delivery is queued, even if the UI thread is busy.
            synchronized(observationLock) {
                val pending = pendingObservation
                if (pending != null && (pending.generation > attempt ||
                        (pending.generation == attempt && pending.peer > peerAttempt))) return
                if (pending?.generation == attempt && pending.peer == peerAttempt && pending.terminal) return
                pendingObservation = PendingObservation(attempt, peerAttempt, observation, terminal)
                if (observationPosted) return
                observationPosted = true
            }
            main.post {
                val latest = synchronized(observationLock) {
                    observationPosted = false
                    pendingObservation.also { pendingObservation = null }
                } ?: return@post
                if (latest.generation == generation && latest.peer == videoAttempt && ready && visible && !isFinishing && !isDestroyed)
                    SatelliteVideoDiagnostics.observe(latest.peer, latest.value, latest.terminal)
            }
        }
    }

    override fun signal(session: String, message: JSONObject) {
        val json = message.toString()
        val attempt = generation
        main.post {
            if (attempt != generation || !ready || !visible || isFinishing || isDestroyed) return@post
            this.session = session
            when (message.optString("type")) {
                "video.offer" -> {
                    if (message.has("sourceMode") && message.opt("sourceMode") != "reference") return@post
                    endVideoAttempt(SatelliteVideoDiagnostics.End.REPLACED)
                    val sourceMode = if (message.opt("sourceMode") == "reference") SatelliteVideoDiagnostics.SourceMode.REFERENCE
                        else SatelliteVideoDiagnostics.SourceMode.CAPTURE
                    videoAttempt = SatelliteVideoDiagnostics.beginAttempt(sourceMode)
                    videoNegotiation = message.optString("negotiation")
                }
                "video.stop" -> if (message.optString("negotiation") == videoNegotiation)
                    endVideoAttempt(SatelliteVideoDiagnostics.End.SENDER_STOPPED)
            }
            webView?.evaluateJavascript("window.receiveSignal(${JSONObject.quote(session)},${JSONObject.quote(json)},${videoAttempt ?: 0})", null)
        }
    }

    override fun reset() {
        val attempt = generation
        main.post {
            if (attempt != generation) return@post
            endVideoAttempt(SatelliteVideoDiagnostics.End.CONTROL_RESET)
            session = null
            if (!isFinishing && !isDestroyed) webView?.evaluateJavascript("window.resetReceiver()", null)
        }
    }

    override fun onStart() {
        super.onStart()
        val newlyVisible = !visible
        visible = true
        if (newlyVisible) projectionGuard.run()
        watchStartup()
        if (newlyVisible && ready && !isFinishing) SatelliteVideoHub.attach(this)
    }

    override fun onStop() {
        visible = false
        endVideoAttempt(SatelliteVideoDiagnostics.End.VIEWER_HIDDEN)
        main.removeCallbacks(projectionGuard)
        cancelStartupDeadline()
        SatelliteVideoHub.detach(this)
        reset()
        super.onStop()
    }

    private fun watchStartup() {
        if (!visible || isFinishing || isDestroyed || bootstrap != Bootstrap.STARTING || startupDeadline != null) return
        val attempt = generation
        startupDeadline = Runnable { failed(attempt, "bootstrap_timeout") }.also {
            main.postDelayed(it, STARTUP_TIMEOUT_MS)
        }
    }

    private fun cancelStartupDeadline() {
        startupDeadline?.let(main::removeCallbacks)
        startupDeadline = null
    }

    private fun failed(attempt: Long, code: String) {
        if (attempt != generation || bootstrap == Bootstrap.FAILED || isFinishing || isDestroyed) return
        bootstrap = Bootstrap.FAILED
        SatelliteVideoDiagnostics.failure(code)
        ready = false
        releaseWebView()
        stateLabel.setText(R.string.satellite_video_unavailable)
        val providerVersion = runCatching { WebView.getCurrentWebViewPackage()?.versionName }
            .getOrNull()?.takeIf { it.matches(Regex("[0-9.]{1,40}")) } ?: "?"
        providerLabel.text = getString(R.string.satellite_video_provider, providerVersion)
        providerLabel.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        AppLogger.w("SatelliteVideo", "bootstrap: $code (WebView $providerVersion)")
    }

    private fun releaseWebView() {
        endVideoAttempt(SatelliteVideoDiagnostics.End.VIEWER_RELEASED)
        generation++ // Fence every old bridge, renderer callback and deadline before retry.
        cancelStartupDeadline()
        SatelliteVideoHub.detach(this)
        session = null
        webView?.apply {
            (parent as? ViewGroup)?.removeView(this)
            try { removeJavascriptInterface("DashCastVideo"); stopLoading(); destroy() } catch (_: Exception) {}
        }
        webView = null
    }

    override fun onDestroy() {
        ready = false
        releaseWebView()
        main.removeCallbacksAndMessages(null)
        if (current.get() === this) current.clear()
        super.onDestroy()
    }

    companion object {
        const val EXPECTED_DISPLAY = "satellite_expected_display"
        internal const val STARTUP_TIMEOUT_MS = 10_000L
        private var current = WeakReference<SatelliteVideoActivity>(null)
        fun closeViewer() { current.get()?.finish() }
        private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

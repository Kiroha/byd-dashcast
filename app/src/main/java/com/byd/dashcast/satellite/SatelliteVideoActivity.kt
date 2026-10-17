package com.byd.dashcast.satellite

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.dashcast.R
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.util.LocaleHelper
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Receive-only app-local native WebRTC. Hidden previews retain no peer, decoder or EGL surface. */
@Suppress("DEPRECATION")
class SatelliteVideoActivity : Activity(), SatelliteVideoHub.Viewer {
    private val main = Handler(Looper.getMainLooper())
    private val controlEpoch = AtomicLong()
    @Volatile private var generation = 0L
    @Volatile private var visible = false
    @Volatile private var ready = false
    @Volatile private var receiver: SatelliteVideoReceiver? = null
    private lateinit var container: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var stateLabel: TextView
    private lateinit var retryButton: Button
    private lateinit var referenceLabel: TextView
    private var deadline: Runnable? = null
    private val projectionGuard = object : Runnable {
        override fun run() {
            if (!visible || isFinishing || isDestroyed) return
            if (!validDestination()) { finish(); return }
            main.postDelayed(this, 500)
        }
    }
    private fun validDestination(): Boolean {
        if (!SatellitePrefs.isEnabled(this)) return false
        if (!intent.hasExtra(EXPECTED_DISPLAY)) return true
        val cluster = ClusterService.getInstance()
        return SatelliteVideoDestination.allowed(intent.getIntExtra(EXPECTED_DISPLAY, -1),
            windowManager.defaultDisplay.displayId, cluster?.displayId ?: -1, cluster?.isProjectionActive() == true)
    }
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.applyLocale(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!validDestination()) { finish(); return }
        current.get()?.let { it.releaseReceiver(); it.finish() }
        current = WeakReference(this)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24); setBackgroundColor(Color.BLACK)
        }
        stateLabel = TextView(this).apply {
            tag = "satellite_video_state"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        retryButton = Button(this).apply {
            tag = "satellite_video_retry"; setText(R.string.satellite_native_retry)
            setOnClickListener { startViewer() }
        }
        overlay.addView(stateLabel)
        overlay.addView(TextView(this).apply {
            setText(R.string.satellite_native_backend); setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        overlay.addView(retryButton)
        overlay.addView(Button(this).apply { setText(R.string.satellite_native_close); setOnClickListener { finish() } })
        container.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        referenceLabel = TextView(this).apply {
            setText(R.string.satellite_native_reference); textSize = 14f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK); visibility = View.GONE
        }
        container.addView(referenceLabel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(container)
    }
    private fun startViewer() {
        if (!visible || isFinishing || isDestroyed || !validDestination()) return
        releaseReceiver()
        val owner = generation
        stateLabel.setText(R.string.satellite_native_loading)
        retryButton.visibility = View.GONE; referenceLabel.visibility = View.GONE; overlay.visibility = View.VISIBLE
        SatelliteVideoDiagnostics.record(SatelliteVideoDiagnostics.Event.VIEWER_STARTING)
        val callbacks = object : SatelliteVideoReceiver.Listener {
            private fun current() = owner == generation && visible && !isFinishing && !isDestroyed
            override fun ready() {
                if (!current() || ready) return
                deadline?.let(main::removeCallbacks); deadline = null
                ready = true
                stateLabel.setText(R.string.satellite_native_waiting)
                SatelliteVideoHub.attach(this@SatelliteVideoActivity)
            }
            override fun failed(code: String) {
                if (!current()) return
                deadline?.let(main::removeCallbacks); deadline = null
                SatelliteVideoDiagnostics.failure(code)
                stateLabel.setText(if (code == "hardware_decoder_unavailable") R.string.satellite_native_no_hardware else R.string.satellite_native_failed)
                retryButton.visibility = View.VISIBLE; referenceLabel.visibility = View.GONE; overlay.visibility = View.VISIBLE
                // Initialization failure must release partial EGL/native resources as well.
                if (!ready) releaseReceiver()
            }
            override fun status(reference: Boolean, rendered: Boolean) {
                if (!current() || !ready) return
                referenceLabel.visibility = if (reference) View.VISIBLE else View.GONE
                overlay.visibility = if (rendered) View.GONE else View.VISIBLE
                if (!rendered) stateLabel.setText(R.string.satellite_native_waiting)
            }
            override fun reply(session: String, raw: String) {
                if (current() && ready) SatelliteVideoHub.reply(this@SatelliteVideoActivity, session, raw)
            }
        }
        try {
            val next = receiverFactory(this, callbacks).also { receiver = it }
            container.addView(next.view, 0, FrameLayout.LayoutParams(-1, -1))
            deadline = Runnable { callbacks.failed("native_receiver_unavailable") }.also { main.postDelayed(it, STARTUP_TIMEOUT_MS) }
            next.start()
        } catch (_: Exception) { callbacks.failed("native_receiver_unavailable") }
        catch (_: LinkageError) { callbacks.failed("native_receiver_unavailable") }
    }
    override fun signal(session: String, message: JSONObject) {
        // The receiver bounds/coalesces its inbox before posting; the Hub never queues arbitrary SDPs on main.
        if (message.optString("type") == "video.offer") controlEpoch.incrementAndGet()
        val current = receiver
        if (ready && visible && current === receiver) current?.signal(session, message)
    }
    override fun reset() {
        val epoch = controlEpoch.incrementAndGet() // Invalidate queued offers immediately, including under the Hub monitor.
        receiver?.reset() // Only invalidation/enqueue; native disposal never runs under the Hub monitor.
        main.post {
            if (epoch == controlEpoch.get() && visible && !isFinishing && !isDestroyed && ready) {
                referenceLabel.visibility = View.GONE; overlay.visibility = View.VISIBLE
                stateLabel.setText(R.string.satellite_native_waiting)
            }
        }
    }
    override fun onStart() {
        super.onStart(); visible = true
        if (!isFinishing) { projectionGuard.run(); startViewer() }
    }
    override fun onStop() {
        visible = false
        main.removeCallbacks(projectionGuard)
        releaseReceiver()
        super.onStop()
    }
    private fun releaseReceiver() {
        generation++; controlEpoch.incrementAndGet(); ready = false
        deadline?.let(main::removeCallbacks); deadline = null
        SatelliteVideoHub.detach(this)
        val old = receiver; receiver = null
        old?.close() // Fences callbacks before any asynchronous codec disposal.
        old?.view?.let { it.visibility = View.GONE; container.removeView(it) }
    }
    override fun onDestroy() {
        releaseReceiver(); main.removeCallbacksAndMessages(null)
        if (current.get() === this) current.clear()
        super.onDestroy()
    }
    companion object {
        const val EXPECTED_DISPLAY = "satellite_expected_display"
        internal const val STARTUP_TIMEOUT_MS = 10_000L
        internal var receiverFactory: (Context, SatelliteVideoReceiver.Listener) -> SatelliteVideoReceiver = { context, listener -> NativeVideoReceiver(context, listener) }
        private var current = WeakReference<SatelliteVideoActivity>(null)
        fun closeViewer() { current.get()?.let { it.releaseReceiver(); it.finish() } }
    }
}

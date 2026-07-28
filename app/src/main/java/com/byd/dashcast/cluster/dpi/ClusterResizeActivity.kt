package com.byd.dashcast.cluster.dpi

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit

import com.byd.dashcast.R
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * v1.2.71 — Fullscreen editor that lets the user position/resize a fission-cluster
 * app via direct manipulation (drag + 4 corner handles) over a live mirror of
 * the cluster display.
 *
 * Intent extras:
 *   - [EXTRA_PACKAGE] (String) — package of the app already on cluster.
 *   - [EXTRA_DISPLAY_ID] (int)  — cluster display id (fission, typically 1).
 *   - [EXTRA_INIT_LTRB] (int[4]) — optional starting rect in cluster coords; defaults to full cluster.
 *
 * UX flow: a mirror displays cluster pixels into a [TextureView]; [ResizeFrameView] draws the
 * editable rectangle and routes touches; every frame change schedules a throttled
 * `moveAndResize` (60 ms). Annuler reverts to the initial rect (one final apply) then finishes;
 * Valider just finishes (last applied rect is already in place).
 *
 * **Hands off** the v1.2.70 cascade in `Phase4TaskVerbs.moveAndResize` — we only feed it
 * rectangles. Do not add bounds-massaging or pre/post hooks here.
 */
class ClusterResizeActivity : Activity(),
    TextureView.SurfaceTextureListener,
    ResizeFrameView.Listener {

    private lateinit var mTexture: TextureView
    private lateinit var mFrame: ResizeFrameView
    private lateinit var mCoords: TextView
    private lateinit var mCancel: Button
    private lateinit var mOk: Button
    private lateinit var mClose: Button
    private lateinit var mSwLive: MaterialSwitch

    /**
     * v1.2.84 — when false (default), drag/preset/release does NOT push to the cluster;
     * only Valider / Annuler commit. When true, every frame change is applied live
     * (legacy behaviour) — guarded by a confirmation popup.
     */
    private var mLiveMode = false
    private lateinit var mPkg: String
    private var mDisplayId = 1
    private lateinit var mInitRect: IntArray // [l,t,r,b]

    private lateinit var mMirror: ClusterMirrorManager
    private var mMirrorStarted = false
    private var mActiveSurface: Surface? = null

    private val mUi = Handler(Looper.getMainLooper())
    private val mApplyLock = Any()
    private var mPendingRect: IntArray? = null // [l,t,r,b] or null
    private var mApplyScheduled = false
    private var mLastApplyTs = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_cluster_resize)

        mDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, 1)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(this, R.string.resize_no_package, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mPkg = pkg

        mTexture = findViewById(R.id.resize_texture)
        mFrame = findViewById(R.id.resize_frame)
        mCoords = findViewById(R.id.resize_coords)
        mCancel = findViewById(R.id.resize_btn_cancel)
        mOk = findViewById(R.id.resize_btn_ok)
        mClose = findViewById(R.id.resize_btn_close)
        mSwLive = findViewById(R.id.resize_sw_live)

        ClusterMirrorManager.unlockHiddenApis()
        mMirror = ClusterMirrorManager()

        val cw = mMirror.getClusterWidth()
        val ch = mMirror.getClusterHeight()
        val extra = intent.getIntArrayExtra(EXTRA_INIT_LTRB)
        mInitRect = if (extra == null || extra.size != 4) intArrayOf(0, 0, cw, ch) else extra
        mFrame.setClusterSize(cw, ch)
        mFrame.setFrame(mInitRect[0], mInitRect[1], mInitRect[2], mInitRect[3])
        mFrame.setListener(this)
        updateCoordsLabel(mInitRect[0], mInitRect[1], mInitRect[2], mInitRect[3])

        // v1.2.72 — Presets (car ergonomics: one-tap to common layouts).
        findViewById<View>(R.id.resize_preset_full).setOnClickListener { applyPreset(0, 0, cw, ch) }
        findViewById<View>(R.id.resize_preset_left).setOnClickListener { applyPreset(0, 0, cw / 2, ch) }
        findViewById<View>(R.id.resize_preset_right).setOnClickListener { applyPreset(cw / 2, 0, cw, ch) }
        findViewById<View>(R.id.resize_preset_center).setOnClickListener {
            val w = (cw * 0.60f).toInt()
            val h = (ch * 0.60f).toInt()
            val l = (cw - w) / 2
            val t = (ch - h) / 2
            applyPreset(l, t, l + w, t + h)
        }

        mTexture.setSurfaceTextureListener(this)

        // v1.2.84 — restore live-mode preference (default OFF). The switch reflects the stored
        // value but does NOT trigger the warning popup on initial bind; the popup only fires
        // on a fresh user toggle from OFF → ON.
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mLiveMode = prefs.getBoolean(PREF_LIVE_MODE, false)
        mSwLive.isChecked = mLiveMode
        mSwLive.setOnCheckedChangeListener { _, checked ->
            if (checked && !mLiveMode) {
                // OFF → ON requested: confirm with a popup that warns about flashes / artefacts.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.resize_live_warn_title)
                    .setMessage(R.string.resize_live_warn_msg)
                    .setPositiveButton(R.string.resize_live_warn_ok) { _, _ ->
                        mLiveMode = true
                        prefs.edit { putBoolean(PREF_LIVE_MODE, true) }
                        AppLogger.i(TAG, "live mode ON (user confirmed)")
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> mSwLive.isChecked = false }
                    .setOnCancelListener { mSwLive.isChecked = false }
                    .show()
            } else if (!checked) {
                mLiveMode = false
                prefs.edit { putBoolean(PREF_LIVE_MODE, false) }
                AppLogger.i(TAG, "live mode OFF")
            }
        }

        mCancel.setOnClickListener {
            // Annuler: revert to the initial rectangle visually + commit it, then exit.
            val r = mInitRect
            mFrame.setFrame(r[0], r[1], r[2], r[3])
            updateCoordsLabel(r[0], r[1], r[2], r[3])
            scheduleApply(r[0], r[1], r[2], r[3], /*commit*/ true)
            finish()
        }
        mOk.setOnClickListener {
            // v1.2.84 — Valider applies the current frame to the cluster but does NOT close the
            // activity, so the user can keep adjusting. To leave, use Close or Annuler.
            val rc = mFrame.getFrame()
            scheduleApply(rc.left, rc.top, rc.right, rc.bottom, /*commit*/ true)
        }
        mClose.setOnClickListener { finish() }

        AppLogger.d(
            TAG, "onCreate pkg=$mPkg display=$mDisplayId init=[" +
                "${mInitRect[0]},${mInitRect[1]},${mInitRect[2]},${mInitRect[3]}] liveMode=$mLiveMode"
        )
    }

    override fun onDestroy() {
        if (this::mFrame.isInitialized) mFrame.clearGestureExclusion()
        try {
            mUi.removeCallbacksAndMessages(null)
        } catch (ignore: Throwable) {
        }
        stopMirrorSafely()
        super.onDestroy()
    }

    /** v1.2.72 — Apply a preset rectangle: updates frame + commits immediately. */
    private fun applyPreset(l: Int, t: Int, r: Int, b: Int) {
        mFrame.setFrame(l, t, r, b)
        updateCoordsLabel(l, t, r, b)
        // v1.2.84 — only push to the cluster when live mode is on; otherwise the user
        // explicitly validates via the Valider button.
        if (mLiveMode) {
            scheduleApply(l, t, r, b, /*commit*/ true)
        }
    }

    // ── Mirror plumbing ────────────────────────────────────────────────────

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        if (mMirrorStarted) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val clusterDisplay: Display? = dm?.getDisplay(mDisplayId)
        val surface = Surface(st)
        mActiveSurface = surface
        var ok = false
        val daemon = ProxyClient.getDaemonBinder()
        if (daemon != null) {
            try {
                ok = mMirror.startMirrorViaDaemon(
                        this, daemon, clusterDisplay, mDisplayId, surface, w, h)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "startMirrorViaDaemon threw: " + t.message)
            }
        }
        // Same guard as MirrorCoordinator.attemptStart: without a cluster display the in-app
        // SurfaceControl path cannot succeed (no ACCESS_SURFACE_FLINGER) and only emits a bogus
        // "fallback layerStack=2" + ACCESS_SURFACE_FLINGER [ERROR] that reads like a permission
        // regression. On DL4 the Display object is null even though the display exists, so the
        // existence test goes through the registry; null on DL3/DL5 → behaviour unchanged there.
        val haveClusterDisplay = clusterDisplay != null ||
                (mDisplayId > 0 && ClusterDisplayRegistry.forDisplayId(mDisplayId) != null)
        if (!ok && haveClusterDisplay) {
            try {
                ok = mMirror.startMirror(this, clusterDisplay, surface, w, h)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "startMirror threw: " + t.message)
            }
        }
        mMirrorStarted = ok
        AppLogger.i(TAG, "mirror started=$ok (view=${w}x$h)")
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { /* no-op */ }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopMirrorSafely()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }

    private fun stopMirrorSafely() {
        if (mMirrorStarted) {
            val daemon = ProxyClient.getDaemonBinder()
            try {
                if (daemon != null) mMirror.stopMirrorViaDaemon(daemon) else mMirror.stopMirror()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "stopMirror threw: " + t.message)
            }
            mMirrorStarted = false
        }
        val s = mActiveSurface
        if (s != null) {
            try {
                s.release()
            } catch (t: Throwable) {
                AppLogger.w(TAG, "release active surface threw: " + t.message)
            }
            mActiveSurface = null
        }
    }

    // ── Frame callbacks (touch) ────────────────────────────────────────────

    override fun onFrameChanged(l: Int, t: Int, r: Int, b: Int, commit: Boolean) {
        updateCoordsLabel(l, t, r, b)
        // v1.2.84 — by default the frame moves silently; only push to the cluster when the
        // user opted into live mode. Without live mode the user must press Valider to commit.
        if (mLiveMode) {
            scheduleApply(l, t, r, b, commit)
        }
    }

    private fun updateCoordsLabel(l: Int, t: Int, r: Int, b: Int) {
        mCoords.text = getString(R.string.resize_coords_fmt, l, t, r, b, r - l, b - t)
    }

    /** Persists the committed rectangle per package (stored in the shared inset prefs file). */
    private fun persistRect(l: Int, t: Int, r: Int, b: Int) {
        if (mPkg.isEmpty()) return
        getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + mPkg, "$l,$t,$r,$b")
        }
        AppLogger.d(TAG, "persistRect $mPkg [$l,$t,$r,$b]")
    }

    // ── Throttled apply ────────────────────────────────────────────────────

    private fun scheduleApply(l: Int, t: Int, r: Int, b: Int, commit: Boolean) {
        // Persist committed rectangles so they survive relaunch: AppActionSheet re-opens this
        // editor on the saved size, and InsetAutoApplicator re-applies it after a fresh launch
        // (the shell/seekbar insets are a separate, symmetric system).
        if (commit) persistRect(l, t, r, b)
        synchronized(mApplyLock) {
            mPendingRect = intArrayOf(l, t, r, b)
            if (commit) {
                // Immediate dispatch for final commits.
                mUi.removeCallbacks(mApplyRunnable)
                mUi.post(mApplyRunnable)
                return
            }
            if (mApplyScheduled) return
            val now = System.currentTimeMillis()
            val delay = Math.max(0L, APPLY_THROTTLE_MS - (now - mLastApplyTs))
            mApplyScheduled = true
            mUi.postDelayed(mApplyRunnable, delay)
        }
    }

    private val mApplyRunnable = Runnable {
        val rect = synchronized(mApplyLock) {
            mApplyScheduled = false
            val pending = mPendingRect ?: return@Runnable
            mPendingRect = null
            mLastApplyTs = System.currentTimeMillis()
            pending
        }
        val l = rect[0]
        val t = rect[1]
        val r = rect[2]
        val b = rect[3]
        sResizeExec.execute {
            try {
                if (!ProxyClient.isConnected()) {
                    ProxyClient.connect(this)
                }
                val log = ProxyClient.moveAndResize(mPkg, mDisplayId, l, t, r, b)
                AppLogger.d(TAG, "moveAndResize [$l,$t,$r,$b] → $log")
            } catch (th: Throwable) {
                AppLogger.w(TAG, "moveAndResize failed: " + th.message)
            }
        }
    }

    // ── System UI ──────────────────────────────────────────────────────────

    @Suppress("DEPRECATION") // systemUiVisibility: immersive flags, only API available at target 29
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    companion object {
        private const val TAG = "ClusterResize"

        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_DISPLAY_ID = "displayId"
        const val EXTRA_INIT_LTRB = "initLtrb"

        private const val APPLY_THROTTLE_MS = 60

        // v1.2.84 — persisted across launches so the user only sees the warning once per
        // activation request (they re-confirm if they previously disabled it).
        private const val PREFS_NAME = "dashcast"
        private const val PREF_LIVE_MODE = "cluster_resize_live_mode"

        private val sResizeExec: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cluster-resize-apply").apply { isDaemon = true }
        }
    }
}

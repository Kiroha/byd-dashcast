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
import java.util.concurrent.RejectedExecutionException

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
    private var mDisplayId = -1
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

        mDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg.isNullOrEmpty()) {
            Toast.makeText(this, R.string.resize_no_package, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (mDisplayId <= 0) {
            Toast.makeText(this, R.string.toast_activate_timeout, Toast.LENGTH_SHORT).show()
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

        // The REAL panel geometry, not the mirror's placeholder.
        //
        // This used to read mMirror.getClusterWidth()/Height() on an instance created two lines
        // above, before startMirror() had ever run on it — so it always got ClusterMirrorManager's
        // 1920x720 default, and setClusterSize() below is called exactly once and never revisited.
        // On any other panel the whole editor session then worked in the wrong coordinate space:
        // every preset, every snap anchor and every clamp was computed against a rect larger than
        // the display, and the result went to ProxyClient.moveAndResize on the live cluster. The
        // project has already met a 1280x480 DL3 panel on a car (INC-20260715-141429).
        //
        // Same resolution order DashboardLauncher uses: getRealSize, then the daemon-resolved
        // registry for DL4 (where the OEM whitelist makes getDisplay() return null even though the
        // display exists), then the literal. The strictly-positive guard mirrors
        // ClusterMirrorManager.startMirror: a transient 0x0 during teardown must not become the
        // editor's coordinate space.
        val panel = android.graphics.Point(1920, 720)
        run {
            val dm0 = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val disp = dm0?.getDisplay(mDisplayId)
            if (disp != null) {
                val sz = android.graphics.Point()
                @Suppress("DEPRECATION")
                disp.getRealSize(sz)
                if (sz.x > 0 && sz.y > 0) panel.set(sz.x, sz.y)
            } else {
                val info = ClusterDisplayRegistry.forDisplayId(mDisplayId)
                if (info != null && info.width > 0 && info.height > 0) panel.set(info.width, info.height)
            }
            AppLogger.i(TAG, "resize editor coordinate space = ${panel.x}x${panel.y} (display $mDisplayId)")
        }
        val cw = panel.x
        val ch = panel.y
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
            if (dispatchFinalApply(r[0], r[1], r[2], r[3])) finish()
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
        // NO daemon mirror here, deliberately. Until 1.8.x this asked ProxyClient.getDaemonBinder()
        // — the PROXY daemon — so startMirrorViaDaemon wrote SurfaceDaemon.DESCRIPTOR onto a binder
        // enforcing the proxy DESCRIPTOR: rejected every time. That is why this editor's live
        // preview has never come up, and it is also why simply pointing it at the right binder is
        // NOT the fix: the surface daemon owns exactly ONE mirror (SurfaceDaemon.TRANSACT_MIRROR_START
        // calls stopMirror() first), so a working call here would re-target it away from
        // MainActivity's preview, and on close nothing restores it — MirrorCoordinator.attemptStart
        // short-circuits on ClusterService's ClusterMirrorManager still reporting mMirrorActive,
        // leaving the main preview frozen on its last frame. Trading a preview nobody has ever had
        // for a broken one is a bad deal.
        // To implement it properly: drive the mirror through the BOUND ClusterService's
        // ClusterMirrorManager (the same instance MirrorCoordinator consults) instead of this
        // Activity's private one, so the shared mMirrorActive state stays consistent and the main
        // preview restarts on return. Until then the editor draws on the placeholder, exactly as it
        // always has.
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
            // Only the in-app path can have started here (see onSurfaceTextureAvailable: no daemon
            // mirror is taken), so stop only that one. Sending TRANSACT_MIRROR_STOP to the surface
            // daemon from this Activity would tear down MainActivity's preview, which this editor
            // never started and does not own.
            try {
                mMirror.stopMirror()
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
        enqueueApply(rect)
    }

    /** Queues the terminal Cancel restore outside the Activity handler before teardown can run. */
    private fun dispatchFinalApply(l: Int, t: Int, r: Int, b: Int): Boolean {
        val rect = intArrayOf(l, t, r, b)
        synchronized(mApplyLock) {
            mUi.removeCallbacks(mApplyRunnable)
            mPendingRect = null
            mApplyScheduled = false
        }
        if (!enqueueApply(rect)) return false
        persistRect(l, t, r, b)
        return true
    }

    private fun enqueueApply(rect: IntArray): Boolean {
        val app = applicationContext
        val pkg = mPkg
        val displayId = mDisplayId
        val applied = rect.copyOf()
        return try {
            sResizeExec.execute {
                val l = applied[0]
                val t = applied[1]
                val r = applied[2]
                val b = applied[3]
                try {
                    if (!ProxyClient.isConnected()) {
                        ProxyClient.connect(app)
                    }
                    val log = ProxyClient.moveAndResize(pkg, displayId, l, t, r, b)
                    AppLogger.d(TAG, "moveAndResize [$l,$t,$r,$b] → $log")
                } catch (th: Throwable) {
                    AppLogger.w(TAG, "moveAndResize failed: " + th.message)
                }
            }
            true
        } catch (rejected: RejectedExecutionException) {
            AppLogger.e(TAG, "moveAndResize dispatch rejected", rejected)
            false
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

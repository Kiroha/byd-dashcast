package com.byd.dashcast.report

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Parcel
import android.os.SystemClock
import android.view.Display
import androidx.core.content.edit
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.hud.HudCaptureSupport
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.daemon.MirrorDaemon
import com.byd.dashcast.util.AppLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Rolling screenshot recorder for the bug reporter.
 *
 * While a cluster projection is active it periodically captures ONE JPEG of the cluster display
 * (and of the main display) into a small, self-cleaning ring buffer in /data/local/tmp, so that a
 * bug report can — with the user's explicit consent at send time (see [BugWizardActivity]) —
 * include what the screens actually showed at the moment of the problem (e.g. "cluster black" vs
 * "app stuck on a confirm screen").
 *
 * Design constraints (all enforced here):
 *  - **Never fills memory.** After every round it keeps only the [KEEP_PER_TAG] most recent shots
 *    per display AND deletes anything older than [MAX_AGE_MIN] minutes; [clear] wipes them on stop.
 *  - **Cluster-correct.** The cluster is a virtual display; `screencap -d N` silently falls back to
 *    display 0 on it, so the cluster frame is grabbed through the uid-2000 daemon
 *    ([MirrorDaemon.TRANSACT_CAPTURE_DISPLAY], ImageReader on the cluster layerStack).
 *  - **A13-safe.** Shots are written by the daemon into /data/local/tmp (uid-2000-owned); the app
 *    can't read that path on A13, so they are pulled back through the daemon only at send time.
 *  - **Privacy.** Captures stay device-local and auto-clean; they only leave the device if the
 *    user opts in when sending a report. A settings flag ([isEnabled]) can disable the whole thing.
 */
object ClusterShotRecorder {

    private const val TAG = "ClusterShotRecorder"

    /** uid-2000-owned scratch dir; the app cannot read it on A13 (pulled via the daemon at send). */
    const val SHOTS_DIR = "/data/local/tmp/dashcast_shots"

    private const val INTERVAL_MS = 15_000L
    private const val PRUNE_INTERVAL_MS = 30_000L
    private const val KEEP_PER_TAG = 6
    private const val MAX_AGE_MIN = 5
    private const val JPEG_QUALITY = 70

    private const val PREF_ENABLED = "capture_screenshots_enabled"

    private val sExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cluster-shot-recorder").apply { isDaemon = true }
    }

    @Volatile private var sLastCaptureMs = 0L
    @Volatile private var sLastPruneMs = 0L

    /** Background capture is ON by default (beta testers file the reports); a settings switch can
     *  disable it. The captures still never leave the device without send-time consent. */
    @JvmStatic
    fun isEnabled(ctx: Context): Boolean =
        try { prefs(ctx).getBoolean(PREF_ENABLED, true) } catch (t: Throwable) { true }

    @JvmStatic
    fun setEnabled(ctx: Context, enabled: Boolean) {
        try { prefs(ctx).edit { putBoolean(PREF_ENABLED, enabled) } } catch (t: Throwable) { }
        if (!enabled) clear(ctx)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Called from the ProxyKeeperService heartbeat. Two throttled jobs, both off the caller thread:
     *  - a max-age prune (~[PRUNE_INTERVAL_MS]) that runs EVEN WHEN NOT PROJECTING, so shots from a
     *    just-ended session auto-clean within [MAX_AGE_MIN] min (they are deliberately kept past the
     *    projection stop so a post-incident report can still include them);
     *  - a capture round (~[INTERVAL_MS]) only while a cluster projection is active.
     */
    @JvmStatic
    fun maybeCapture(ctx: Context) {
        if (!isEnabled(ctx)) return
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()

        val clusterId = ClusterService.getInstance()?.displayId ?: -1
        if (clusterId > 0 && now - sLastCaptureMs >= INTERVAL_MS) {
            sLastCaptureMs = now
            sLastPruneMs = now // captureRound prunes too
            sExecutor.execute { captureRound(app, clusterId) }
        } else if (now - sLastPruneMs >= PRUNE_INTERVAL_MS) {
            sLastPruneMs = now
            sExecutor.execute { prune(app) }
        }
    }

    private fun captureRound(ctx: Context, clusterId: Int) {
        try {
            val binder = DaemonBinderResolver.getRegisteredBinderOrNull()
            if (binder == null) {
                AppLogger.d(TAG, "capture skipped — mirror daemon binder not available")
                return
            }
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val stamp = System.currentTimeMillis()

            // Main display (layerStack 0).
            sizeOf(dm, 0)?.let { (w, h) ->
                capture(binder, 0, w, h, "$SHOTS_DIR/shot_d0_$stamp.jpg", "d0")
            }
            // Cluster display (its own layerStack — 2 on DL5.1, 1 on DL3 1for2).
            val cluster = dm.getDisplay(clusterId)
            if (cluster != null) {
                val sz = sizeOf(dm, clusterId)
                if (sz != null) {
                    capture(binder, layerStackOf(cluster, clusterId), sz.first, sz.second,
                            "$SHOTS_DIR/shot_cluster_$stamp.jpg", "cluster")
                }
            }
            prune(ctx)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "captureRound failed: ${t.message}")
        }
    }

    private fun capture(binder: android.os.IBinder, layerStack: Int, w: Int, h: Int,
                        path: String, tag: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MirrorDaemon.DESCRIPTOR)
            data.writeInt(layerStack)
            data.writeInt(w)
            data.writeInt(h)
            data.writeInt(JPEG_QUALITY)
            data.writeString(path)
            // Ring-buffer bound enforced by the daemon in-process (transport-independent): keep at
            // most both tags' worth of shots + drop anything older than MAX_AGE_MIN.
            data.writeInt(KEEP_PER_TAG * 2)
            data.writeInt(MAX_AGE_MIN)
            binder.transact(MirrorDaemon.TRANSACT_CAPTURE_DISPLAY, data, reply, 0)
            reply.readException()
            AppLogger.d(TAG, "capture $tag ls=$layerStack ${w}x$h -> ${reply.readString()}")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "capture $tag transact failed: ${t.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Ring-buffer prune: keep the N most recent per tag + drop anything older than MAX_AGE_MIN.
     *  Runs as the uid-2000 shell (owns the files). Reuses the proven `ls -t | tail | xargs rm`
     *  pattern from the mirrordaemon-log pruner. */
    private fun prune(ctx: Context) {
        val keepPlus1 = KEEP_PER_TAG + 1
        val cmd = "cd $SHOTS_DIR 2>/dev/null || exit 0; " +
                "for t in d0 cluster; do " +
                "ls -t shot_\${t}_*.jpg 2>/dev/null | tail -n +$keepPlus1 | xargs -r rm -f; done; " +
                "find $SHOTS_DIR -name 'shot_*.jpg' -mmin +$MAX_AGE_MIN -delete 2>/dev/null; true"
        AdbLocalClient.executeShellWithResult(ctx, cmd, null)
    }

    /** Deletes all captured shots. Called on projection stop, when disabled, and after a send. */
    @JvmStatic
    fun clear(ctx: Context) {
        sLastCaptureMs = 0L
        AdbLocalClient.executeShellWithResult(ctx,
                "rm -f $SHOTS_DIR/shot_*.jpg 2>/dev/null; true", null)
    }

    @Suppress("DEPRECATION")
    private fun sizeOf(dm: DisplayManager, id: Int): Pair<Int, Int>? {
        return try {
            val d = dm.getDisplay(id) ?: return null
            val p = Point()
            d.getRealSize(p)
            if (p.x > 0 && p.y > 0) Pair(p.x, p.y) else null
        } catch (t: Throwable) { null }
    }

    /** Display's layerStack via reflection (hidden getter); falls back to the displayId. */
    private fun layerStackOf(display: Display, fallback: Int): Int {
        return try {
            val m = Display::class.java.getMethod("getLayerStack")
            (m.invoke(display) as? Int) ?: fallback
        } catch (t: Throwable) { fallback }
    }

    // ── Send-time bundling (called from a background thread by the bug wizard) ────

    /** Synchronous `ls` of the shot files (full device paths), newest first. Empty on any failure. */
    private fun listRemoteShots(ctx: Context): List<String> {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        AdbLocalClient.executeShellWithResult(ctx,
                "ls -t $SHOTS_DIR/shot_*.jpg 2>/dev/null",
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) { holder[0] = out; latch.countDown() }
                    override fun onError(error: String?) { latch.countDown() }
                })
        try { latch.await(5, TimeUnit.SECONDS) } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return holder[0]?.lines()
                ?.map { it.trim() }
                ?.filter { it.endsWith(".jpg") && it.startsWith(SHOTS_DIR) }
                ?: emptyList()
    }

    /**
     * Pulls every current shot (via the daemon — the app can't read /data/local/tmp on A13) into
     * [destDir]. BLOCKING — call from a background thread. Returns the number of images pulled.
     */
    @JvmStatic
    fun pullShotsInto(ctx: Context, destDir: File): Int {
        val shots = listRemoteShots(ctx)
        if (shots.isEmpty()) return 0
        if (!destDir.exists()) destDir.mkdirs()
        var n = 0
        for (remote in shots) {
            try {
                val name = remote.substringAfterLast('/')
                val bytes = HudCaptureSupport.pullRemoteFile(remote, File(destDir, name))
                if (bytes > 0) n++
            } catch (t: Throwable) {
                AppLogger.w(TAG, "pull $remote failed: ${t.message}")
            }
        }
        return n
    }
}

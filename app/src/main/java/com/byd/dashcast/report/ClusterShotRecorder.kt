package com.byd.dashcast.report

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Parcel
import android.os.SystemClock
import android.view.Display
import androidx.core.content.edit
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.cluster.display.ClusterDisplayRegistry
import com.byd.dashcast.cluster.display.ClusterLayerStackPolicy
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.hud.HudCaptureSupport
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.DaemonBinderResolver
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.proxy.ProxyClient
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
 *  - **Never fills memory.** The daemon enforces a hard global count/age bound after every JPEG;
 *    periodic app-side pruning additionally keeps only [KEEP_PER_TAG] shots per display.
 *  - **Cluster-correct.** The cluster is a virtual display; `screencap -d N` silently falls back to
 *    display 0 on it, so the cluster frame is grabbed through the uid-2000 daemon
 *    ([SurfaceDaemon.TRANSACT_CAPTURE_DISPLAY], ImageReader on the cluster layerStack).
 *  - **A13-safe.** Shots are written by the daemon into /data/local/tmp (uid-2000-owned); the app
 *    can't read that path on A13, so they are pulled back through the daemon only at send time.
 *  - **Privacy.** Captures stay device-local and auto-clean; they only leave the device if the
 *    user opts in when sending a report. A settings flag ([isEnabled]) can disable the whole thing.
 */
object ClusterShotRecorder {

    private const val TAG = "ClusterShotRecorder"

    /** uid-2000-owned scratch dir; the app cannot read it on A13 (pulled via the daemon at send). */
    const val SHOTS_DIR = "/data/local/tmp/dashcast_shots"

    // AUD-PERF-P1 — cadence ramp. This recorder rides the 10 s ProxyKeeperService heartbeat and
    // is ON by default, so a flat 15 s threshold meant ~3 capture rounds/min — six full-display
    // captures and six JPEG encodes every minute — for the entire duration of every projection,
    // on a passively-cooled SoC whose GPU is shared with the IVI stack. Nothing checked whether
    // the screen had changed, so a car stopped at a light re-encoded an identical frame every
    // 20 s. The incidents these shots exist for happen in the first couple of minutes after a
    // launch, so keep the old cadence there and back off afterwards. MAX_AGE_MIN retention and
    // the ring bound are unchanged.
    private const val INTERVAL_MS        = 15_000L   // during the post-launch ramp window
    private const val INTERVAL_STEADY_MS = 90_000L   // steady state
    private const val RAMP_WINDOW_MS     = 120_000L
    private const val PRUNE_INTERVAL_MS = 30_000L
    private const val KEEP_PER_TAG = 6
    private const val MAX_AGE_MIN = 5
    private const val JPEG_QUALITY = 70

    private const val PREF_ENABLED = "capture_screenshots_enabled"

    private val sExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cluster-shot-recorder").apply { isDaemon = true }
    }

    // AUD-PERF-P1 — latched when a cluster projection comes up, cleared when it goes away.
    // Drives the cadence ramp above; measured from when the cluster actually appeared, not from
    // process start (the keeper heartbeat long outlives any single projection session).
    @Volatile private var sProjectionStartMs = 0L

    @Volatile private var sLastCaptureMs = 0L
    @Volatile private var sLastPruneMs = 0L
    @Volatile private var sLastDaemonPruneMs = 0L

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
     *  - a capture round only while a cluster projection is active, at ~[INTERVAL_MS] for the
     *    first [RAMP_WINDOW_MS] of that projection and ~[INTERVAL_STEADY_MS] thereafter.
     */
    @JvmStatic
    fun maybeCapture(ctx: Context) {
        if (!isEnabled(ctx)) return
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()

        val clusterId = ClusterService.getInstance()?.displayId ?: -1
        // AUD-PERF-P1 — latch/clear the projection-start instant, then pick the ramped interval.
        if (clusterId > 0) {
            if (sProjectionStartMs == 0L) sProjectionStartMs = now
        } else {
            sProjectionStartMs = 0L
        }
        val intervalMs =
            if (sProjectionStartMs != 0L && now - sProjectionStartMs < RAMP_WINDOW_MS) INTERVAL_MS
            else INTERVAL_STEADY_MS
        if (ClusterShotSchedulePolicy.shouldCapture(
            clusterId, now, sLastCaptureMs, intervalMs)) {
            sLastCaptureMs = now
            sExecutor.execute { captureRound(app, clusterId) }
        } else if (ClusterShotSchedulePolicy.shouldAppPrune(
            clusterId, now, sLastPruneMs, sLastDaemonPruneMs, PRUNE_INTERVAL_MS,
            // AUD-PERF-P1/P2 REGRESSION FIX — the daemon-staleness bound tracks the CAPTURE
            // cadence; it is deliberately NOT PRUNE_INTERVAL_MS. sLastDaemonPruneMs is refreshed
            // only by a successful capture, so once the ramp stretched captures to 90 s a fixed
            // 30 s bound went stale between every pair of captures and this branch fired at t=30
            // and t=60 of each cycle: ~80 app prunes per hour of projection where there had been
            // zero, each one an ADB TCP + RSA handshake. One capture cycle plus a margin means a
            // stamp this old only happens when captures are actually failing. Idle behaviour is
            // untouched -- the `clusterId <= 0` arm short-circuits before this is read.
            /* daemonStaleMs = */ intervalMs + PRUNE_INTERVAL_MS,
            // AUD-PERF-P2 — sLastCaptureMs is stamped before the capture is submitted, so this is
            // true even for a capture that later failed: a failed round still wrote nothing but
            // still means the daemon may hold shots. Only a process that never projected at all
            // skips the prune, which is precisely the case that was pure waste.
            /* everCaptured = */ sLastCaptureMs != 0L)) {
            sLastPruneMs = now
            sExecutor.execute { prune(app) }
        }
    }

    private fun captureRound(ctx: Context, clusterId: Int) {
        try {
            val binder = DaemonBinderResolver.surfaceDaemonBinder()
            if (binder == null) {
                AppLogger.d(TAG, "capture skipped — mirror daemon binder not available")
                return
            }
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            val stamp = System.currentTimeMillis()
            var daemonPruned = false

            // Main display (layerStack 0).
            sizeOf(dm, 0)?.let { (w, h) ->
                daemonPruned = capture(
                    binder, 0, w, h, "$SHOTS_DIR/shot_d0_$stamp.jpg", "d0"
                ) || daemonPruned
            }
            // Cluster display (its own layerStack — 2 on DL5.1, 1 on DL3 1for2 and DL4).
            val cluster = dm.getDisplay(clusterId)
            if (cluster != null) {
                val sz = sizeOf(dm, clusterId)
                if (sz != null) {
                    daemonPruned = capture(
                        binder, clusterLayerStack(ctx, cluster, clusterId), sz.first, sz.second,
                        "$SHOTS_DIR/shot_cluster_$stamp.jpg", "cluster"
                    ) || daemonPruned
                }
            } else {
                // DL4: dm.getDisplay() is refused by the OEM DisplayManagerService whitelist, so
                // the CLUSTER screenshot — the single most useful artefact for a projection bug —
                // was silently skipped while the main-display one still went through. The daemon
                // capture verb only ever needed ints, and ClusterManager already resolved them.
                // Registry is null on DL3/DL5 → those keep skipping exactly as before.
                val info = ClusterDisplayRegistry.forDisplayId(clusterId)
                if (info != null && info.width > 0 && info.height > 0) {
                    daemonPruned = capture(
                        binder, info.layerStack, info.width, info.height,
                        "$SHOTS_DIR/shot_cluster_$stamp.jpg", "cluster"
                    ) || daemonPruned
                }
            }
            if (daemonPruned) sLastDaemonPruneMs = SystemClock.elapsedRealtime()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "captureRound failed: ${t.message}")
        }
    }

    private fun capture(binder: android.os.IBinder, layerStack: Int, w: Int, h: Int,
                        path: String, tag: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SurfaceDaemon.DESCRIPTOR)
            data.writeInt(layerStack)
            data.writeInt(w)
            data.writeInt(h)
            data.writeInt(JPEG_QUALITY)
            data.writeString(path)
            // Ring-buffer bound enforced by the daemon in-process (transport-independent): keep at
            // most both tags' worth of shots + drop anything older than MAX_AGE_MIN.
            data.writeInt(KEEP_PER_TAG * 2)
            data.writeInt(MAX_AGE_MIN)
            binder.transact(SurfaceDaemon.TRANSACT_CAPTURE_DISPLAY, data, reply, 0)
            reply.readException()
            val result = reply.readString()
            AppLogger.d(TAG, "capture $tag ls=$layerStack ${w}x$h -> $result")
            return result?.startsWith("OK ") == true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "capture $tag transact failed: ${t.message}")
            return false
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
        runShellBlocking(ctx, cmd, "prune")
    }

    /** Deletes all captured shots. Called on projection stop, when disabled, and after a send. */
    @JvmStatic
    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        // Snapshot BEFORE enqueuing. See the compare-and-set below.
        val captureAtEnqueue = sLastCaptureMs
        val daemonPruneAtEnqueue = sLastDaemonPruneMs
        sExecutor.execute {
            // Reset the latches only if the delete actually ran. They used to be cleared
            // synchronously before the async shell call, and every failure here is swallowed, so
            // with the transport down -- the common state when a report is being filed -- the
            // JPEGs survived on disk while everCaptured went false, and the max-age prune that
            // would have swept them never ran again. They are screenshots of both driver-facing
            // screens, kept after the user asked for them to be cleared.
            if (runShellBlocking(app, "rm -f $SHOTS_DIR/shot_*.jpg 2>/dev/null; true", "clear")) {
                // ...but reset ONLY if nothing captured while we were blocked. Deferring the reset
                // to after a blocking ADB round-trip opened a lost update: a keeper heartbeat
                // landing in that window stamps sLastCaptureMs and queues a captureRound BEHIND
                // this task, so a bare `= 0L` here would erase that stamp while the queued round
                // then writes fresh JPEGs. everCaptured would read false with files on disk, and
                // if projection ends before the next heartbeat the max-age sweep never runs --
                // re-entering the exact harm this fix exists to prevent. The executor is
                // single-threaded, so a plain compare-and-set is sufficient.
                if (sLastCaptureMs == captureAtEnqueue) sLastCaptureMs = 0L
                if (sLastDaemonPruneMs == daemonPruneAtEnqueue) sLastDaemonPruneMs = 0L
            }
            // NOTE: this does NOT cover setEnabled(false), which writes the pref before calling
            // here; maybeCapture then early-returns on !isEnabled, so no prune is ever scheduled
            // again regardless of the latches. A failed delete on that path still strands the
            // files. Unchanged behaviour, called out so the claim above is not read as covering it.
        }
    }

    /** @return true if the shell call completed without throwing. */
    private fun runShellBlocking(ctx: Context, command: String, operation: String): Boolean {
        return try {
            AdbLocalClient.executeShellWithResultBlocking(ctx, command)
            true
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            AppLogger.w(TAG, "$operation failed: ${t.message}")
            false
        }
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

    /**
     * The layerStack the cluster face is actually composited on.
     *
     * On DiLink 5 the app is launched onto a shadow render display (layerStack 3/4) whose content
     * the OEM container composites onto layerStack 2 — so capturing the detected layerStack grabbed
     * a legitimately EMPTY surface and produced an all-black JPEG every time (all 34 cluster shots
     * in INC-20260804-171617 were the same 8937-byte black frame, including ones taken while the
     * projected app was demonstrably on the panel). [ClusterMirrorManager] already applied this
     * override for the preview; the recorder did not.
     *
     * Fails OPEN: any platform-detection error returns the detected value, i.e. exactly today's
     * behaviour. DiLink 3 / DiLink 4 (layerStack 1) and trinket / DiLink 5.1 (layerStack 2) are
     * never rewritten, because the rule only ever maps 3/4 → 2.
     */
    private fun clusterLayerStack(ctx: Context, display: Display, clusterId: Int): Int {
        val detected = layerStackOf(display, clusterId)
        val dl5 = try {
            com.byd.dashcast.platform.Platform.get().isDiLink5(ctx)
        } catch (t: Throwable) {
            return detected
        }
        val effective = ClusterLayerStackPolicy.composedOrSelf(dl5, detected)
        if (effective != detected) {
            AppLogger.i(TAG, "DL5 override: capture layerStack $detected → $effective "
                    + "(composed cluster face; $detected is the empty shadow render display)")
        }
        return effective
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
        var pendingPull: java.util.concurrent.Future<Int>? = null
        return try {
            // Two bounds, because the screenshots are an OPTIONAL attachment and the report is not.
            //
            // The .get() had no timeout, and the work behind it walks every shot through
            // ProxyClient.readFileChunk. With a cold or dead uid-2000 daemon each of those pays the
            // ~31 s blocking bootstrap — once per screenshot — on the thread that is trying to send
            // a bug report. The scenario is not exotic: it is precisely the daemon-down case the
            // report exists to capture, and the user is sitting in front of a Send button that has
            // already disabled itself.
            //
            // setNonBlockingReconnect is the opt-out this project already uses at exactly this kind
            // of site (BugReportCapture.hudStateSnapshot:455/464). The verbs fail fast instead of
            // waiting for a bootstrap, and a missing screenshot is strictly better than a report
            // that never leaves. Restored in a finally because the pooled thread is reused.
            pendingPull = sExecutor.submit<Int> {
                ProxyClient.setNonBlockingReconnect(true)
                try {
                    pullShotsIntoNow(ctx.applicationContext, destDir)
                } finally {
                    ProxyClient.setNonBlockingReconnect(false)
                }
            }
            pendingPull.get(PULL_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (te: java.util.concurrent.TimeoutException) {
            // Whatever landed in destDir before the budget expired is still attached — that part is
            // deliberate and unchanged. What was missing is the cancel.
            //
            // sExecutor is ONE thread, shared with captureRound and prune, and that sharing is
            // load-bearing: prune runs `rm -f shot_*.jpg` over the very files a pull is copying, so
            // serialising them is what stops a purge deleting a shot mid-copy. Do NOT "fix" this by
            // giving the pull its own executor — that trades a starvation window for a
            // delete-during-copy race.
            //
            // The consequence of the sharing is that a wedged pull holds the only worker, so every
            // periodic capture and prune queues behind it for as long as it runs. Cancelling bounds
            // that to the budget wherever the work is interruptible. Where it is not — a blocking
            // binder transact into a wedged daemon — the interrupt is a request, not a guarantee,
            // and the old behaviour stands.
            pendingPull?.cancel(true)
            AppLogger.w(TAG, "pull shots exceeded ${PULL_BUDGET_MS}ms — sending without them")
            0
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            0
        } catch (t: Throwable) {
            AppLogger.w(TAG, "pull shots failed: ${t.message}")
            0
        }
    }

    /**
     * How long a send may wait for the optional screenshots.
     *
     * Sized against the thing it is protecting from: a single daemon bootstrap is about 31 s, so
     * anything at or above that lets one cold verb consume the whole budget. Twenty seconds is
     * comfortably more than a healthy pull of the ring buffer needs and comfortably less than one
     * bootstrap.
     */
    private const val PULL_BUDGET_MS = 20_000L

    private fun pullShotsIntoNow(ctx: Context, destDir: File): Int {
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

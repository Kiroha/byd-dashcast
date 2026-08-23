package com.byd.dashcast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.fission.LayoutPrefs
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ProxyKeeperService
import com.byd.dashcast.ui.hotspot.HotspotKeeper
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.update.OtaRelaunchCoordinator
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.atomic.AtomicInteger

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // v1.2.75 — Couche 1: also wake up on package replacement (OTA update
        // of DashCast itself). Cold-spawning the daemon and the KeeperService
        // right away avoids the "daemon dead until user opens the app" window.
        val isBoot = Intent.ACTION_BOOT_COMPLETED == action
        val isReplace = Intent.ACTION_MY_PACKAGE_REPLACED == action
        if (!isBoot && !isReplace) return

        // v1.2.75 — Couche 1: start the always-on FG keeper as early as
        // possible. ensureRunning() is idempotent.
        try {
            ProxyKeeperService.ensureRunning(context.applicationContext)
        } catch (t: Throwable) {
            AppLogger.w("BootReceiver", "ProxyKeeperService.ensureRunning failed: " + t.message)
        }

        val prefs = context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val autoStartEnabled = ClusterPrefs.isBootAutoStartEnabled(context)
        val prewarmDaemon = true

        // We need to keep the process alive across multiple async tasks that
        // can run in parallel: (a) Phase 5 daemon pre-warm (~1.9 s connect()),
        // (b) display-affinity cleanup when auto-start is OFF,
        // (c) the TetherFi hotspot keep-alive pass (armed at +3 s, probe + launch).
        // PendingResult.finish() may only be called once, so we coordinate via
        // a shared atomic counter that triggers finish on the last completion.
        // The counter is initialised to 1 as a sentinel so that fast bg tasks
        // can't drive it to 0 before this method has finished scheduling every
        // pending job (race that would cause result.finish() to be called twice
        // when a slower job, like the 8 s-delayed TetherFi runnable, completes
        // later). The sentinel is released at the very end of onReceive.
        val appCtx = context.applicationContext
        val result = goAsync()
        val pending = AtomicInteger(1)
        val releaseOne = Runnable {
            if (pending.decrementAndGet() == 0) result.finish()
        }
        /**
         * Takes a token for one piece of background work and returns the runnable that releases
         * it. Taking the token IS how a branch obtains its release, so a branch cannot schedule
         * work without being counted — which is the only way this counter fails. A forgotten
         * increment finishes the broadcast while work is still running and the system is then free
         * to kill the process mid-task; a forgotten release wedges the broadcast until the ANR
         * timeout. Neither shows up anywhere a reader would look, so the shape has to prevent it.
         */
        fun takeToken(): Runnable {
            pending.incrementAndGet()
            return releaseOne
        }

        if (isReplace) {
            val done = takeToken()
            sMain.postDelayed({
                try {
                    OtaRelaunchCoordinator.relaunchIfPending(appCtx, "MY_PACKAGE_REPLACED")
                } finally {
                    done.run()
                }
            }, 750L)
        }

        if (prewarmDaemon) {
            // Phase 5 — pre-warm the proxy daemon at boot so the first user
            // action doesn't pay the ~1.9 s bootstrap cost (measured cold
            // sendInfo in build-178 device log = 1916 ms; warm = 3 ms).
            val done = takeToken()
            Thread({
                try {
                    AppLogger.i("BootReceiver", "daemon pre-warm starting...")
                    val t0 = System.currentTimeMillis()
                    val ok = ProxyClient.connect(appCtx)
                    val dt = System.currentTimeMillis() - t0
                    AppLogger.i("BootReceiver", "daemon pre-warm "
                            + (if (ok) "ok" else "FAILED") + " (" + dt + "ms)")
                } catch (t: Throwable) {
                    // Pre-warm failure must never block boot.
                    AppLogger.w("BootReceiver", "daemon pre-warm threw: " + t.message)
                } finally {
                    done.run()
                }
            }, "daemon-prewarm").start()
        }

        if (autoStartEnabled) {
            if (isLayoutAutoStartRequested(appCtx)) {
                // LAYOUT auto-start: the favourite Layout owns startup — it activates projection AND
                // launches every bound app itself, headlessly (FissionOrchestrator.
                // maybeAutoStartOnAppLaunch, the same call MainActivity.onCreate makes). Do NOT also
                // start the classic ClusterService projection: it would trip the layout's
                // projection-conflict check ("classic projection already active") and nothing would
                // launch (INC-20260716-201008 — layout user got projection on but no apps). Delay a
                // little so the daemon pre-warm connects first; the orchestrator runs on its own bg
                // thread. MainActivity's later call to the same method no-ops (one-shot per process).
                AppLogger.i("BootReceiver", "DashCast Auto-Boot: layout auto-start (headless)")
                val done = takeToken()
                sMain.postDelayed({
                    try {
                        FissionOrchestrator.maybeAutoStartOnAppLaunch(appCtx)
                    } catch (t: Throwable) {
                        AppLogger.w("BootReceiver", "layout auto-start failed: " + t.message)
                    } finally {
                        done.run()
                    }
                }, 3_000L)
            } else {
                AppLogger.i("BootReceiver", "DashCast Auto-Boot: Starting projection automatically...")
                try {
                    // EXTRA_BOOT_AUTOLAUNCH: also launch the configured single app onto the cluster
                    // once it is up, headlessly — so a tester who set an auto-launch app gets it at
                    // startup without opening DashCast (INC-20260716-091016).
                    val serviceIntent = Intent(context, ClusterService::class.java)
                        .putExtra(ClusterService.EXTRA_BOOT_AUTOLAUNCH, true)
                    context.startForegroundService(serviceIntent)
                } catch (e: Exception) {
                    AppLogger.e("BootReceiver", "Error starting ClusterService on boot: " + e.message)
                }
            }
        } else {
            AppLogger.d("BootReceiver", "DashCast Auto-Boot Projection is disabled by user.")
            // Projection not auto-started: move any apps that were on the cluster
            // (from the previous session) back to Display 0 so they don't get stuck
            // on the (possibly still-alive) VirtualDisplay.
            //
            // AUD-006 — only on a GENUINE boot. This ROM re-delivers BOOT_COMPLETED at every
            // ACC-on without rebooting (see the note below: this receiver was observed running
            // 25 min into an already-running process on a 15-hour-old boot). elapsedRealtime()
            // is the discriminator: it keeps counting from the last real boot, so a re-delivery
            // reports hours while a genuine boot reports seconds. Without this gate the cleanup
            // ran mid-session and yanked the driver's projected app off the cluster.
            // ACTION_MY_PACKAGE_REPLACED is exempt: our process was just replaced at an
            // arbitrary uptime, and apps really can be stranded on the cluster then.
            val uptimeMs = SystemClock.elapsedRealtime()
            if (!BootCleanupPolicy.shouldCleanup(isReplace, uptimeMs)) {
                AppLogger.i("BootReceiver", "BOOT_COMPLETED re-delivered at uptime " +
                        (uptimeMs / 1000) + "s (> " +
                        (BootCleanupPolicy.BOOT_CLEANUP_WINDOW_MS / 1000) +
                        "s) — not a real boot, skipping display cleanup")
            } else {
                val done = takeToken()
                Thread({
                    try {
                        BootDisplayCleanup.cleanup(context)
                    } catch (e: Exception) {
                        AppLogger.e("BootReceiver", "Display cleanup error: " + e.message)
                    } finally {
                        done.run()
                    }
                }, "boot-display-cleanup").start()
            }
        }

        // ─── v1.2.43 — Auto-start TetherFi hotspot at boot ─────────────────
        // Independent of the projection auto-boot above: a user can want one without the other,
        // so this stays gated on ITS OWN pref and never on the watchdog pref.
        //
        // v1.6.148 — PROBE-GATED. This receiver used to fire the tile Intent itself, BLIND: no
        // state probe, no coordination with HotspotKeeper. This ROM RE-DELIVERS BOOT_COMPLETED at
        // every ACC-on without rebooting (proven: this receiver ran 25 min into an already-running
        // process on a 15-hour-old boot), so that blind dispatch landed ~14 s after the keeper's
        // own first pass and the user saw TWO ProxyTileActivity popups 13.9 s apart
        // (INC-20260721-184844). We now ask the keeper for ONE probe-then-start pass: already
        // UP → nothing, DOWN → one launch, no shell at all → the keeper falls back to exactly the
        // blind in-app startActivity this block used to do, so this path is never worse than the
        // code it replaces. The keeper's dedupe rule makes the two passes safe.
        val hotspotAutoStart = prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false)
        if (hotspotAutoStart) {
            val done = takeToken()
            // 3 s — same rationale as the layout auto-start above: let the daemon pre-warm connect
            // first, because the keeper's first launch route is a uid-2000 `am start` through that
            // daemon. Shorter than the old 7 s: we no longer fire blind, so an early pass costs one
            // dumpsys, not a wasted popup. runImmediatePass never throws and invokes the release
            // exactly once (run-once guard + a 12 s internal safety timeout), so the goAsync token
            // cannot leak — and 3 s + 12 s is far inside the ~60 s a background broadcast is given.
            sMain.postDelayed({
                HotspotKeeper.runImmediatePass(appCtx, "boot/ACC-on", done)
            }, 3_000L)
        }

        // Release the sentinel: all scheduling is now done. If every bg task
        // already completed (pending == 1 sentinel only), this triggers finish.
        releaseOne.run()
    }

    /** A requested automatic Layout owns startup even while its saved favourite is being repaired. */
    private fun isLayoutAutoStartRequested(ctx: Context): Boolean = try {
        FissionOrchestrator.isAutoStartRequested(ctx)
    } catch (t: Throwable) {
        false
    }

    companion object {
        private val sMain = android.os.Handler(android.os.Looper.getMainLooper())
    }
}

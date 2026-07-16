package com.byd.dashcast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.fission.FissionOrchestrator
import com.byd.dashcast.fission.LayoutPrefs
import com.byd.dashcast.proxy.DaemonConfig
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ProxyKeeperService
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.atomic.AtomicInteger

class BootReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION") // PackageManager.getPackageInfo(String, int) — fine on minSdk 28
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
        // (c) TetherFi hotspot auto-start (postDelayed 8 s).
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

        if (prewarmDaemon) {
            // Phase 5 — pre-warm the proxy daemon at boot so the first user
            // action doesn't pay the ~1.9 s bootstrap cost (measured cold
            // sendInfo in build-178 device log = 1916 ms; warm = 3 ms).
            pending.incrementAndGet()
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
                    releaseOne.run()
                }
            }, "daemon-prewarm").start()
        }

        if (autoStartEnabled) {
            if (isLayoutAutoStartConfigured(appCtx)) {
                // LAYOUT auto-start: the favourite Layout owns startup — it activates projection AND
                // launches every bound app itself, headlessly (FissionOrchestrator.
                // maybeAutoStartOnAppLaunch, the same call MainActivity.onCreate makes). Do NOT also
                // start the classic ClusterService projection: it would trip the layout's
                // projection-conflict check ("classic projection already active") and nothing would
                // launch (INC-20260716-201008 — layout user got projection on but no apps). Delay a
                // little so the daemon pre-warm connects first; the orchestrator runs on its own bg
                // thread. MainActivity's later call to the same method no-ops (one-shot per process).
                AppLogger.i("BootReceiver", "DashCast Auto-Boot: layout auto-start (headless)")
                pending.incrementAndGet()
                sMain.postDelayed({
                    try {
                        FissionOrchestrator.maybeAutoStartOnAppLaunch(appCtx)
                    } catch (t: Throwable) {
                        AppLogger.w("BootReceiver", "layout auto-start failed: " + t.message)
                    } finally {
                        releaseOne.run()
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
            pending.incrementAndGet()
            Thread({
                try {
                    BootDisplayCleanup.cleanup(context)
                } catch (e: Exception) {
                    AppLogger.e("BootReceiver", "Display cleanup error: " + e.message)
                } finally {
                    releaseOne.run()
                }
            }, "boot-display-cleanup").start()
        }

        // ─── v1.2.43 — Auto-start TetherFi hotspot at boot ─────────────────
        // Fires the same TOGGLE→START intent that HotspotActivity uses, after
        // a short delay so the Wi-Fi stack and PackageManager are fully up.
        // Independent of the projection auto-boot above: a user can want one
        // without the other.
        val hotspotAutoStart = prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false)
        if (hotspotAutoStart) {
            // Verify TetherFi is still installed (user may have uninstalled it).
            var installed = false
            try {
                appCtx.packageManager.getPackageInfo("com.pyamsoft.tetherfi", 0)
                installed = true
            } catch (ignore: android.content.pm.PackageManager.NameNotFoundException) {
            }

            if (installed) {
                pending.incrementAndGet()
                sMain.postDelayed({
                    try {
                        val tetherIntent = Intent()
                        tetherIntent.setClassName("com.pyamsoft.tetherfi",
                                "com.pyamsoft.tetherfi.tile.ProxyTileActivity")
                        tetherIntent.putExtra("key_action", "START")
                        tetherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                or Intent.FLAG_ACTIVITY_NO_HISTORY)
                        appCtx.startActivity(tetherIntent)
                        AppLogger.i("BootReceiver", "TetherFi auto-start at boot: START dispatched")
                    } catch (t: Throwable) {
                        AppLogger.w("BootReceiver", "TetherFi auto-start failed: " + t.message)
                    } finally {
                        releaseOne.run()
                    }
                }, 7_000L)
            } else {
                AppLogger.i("BootReceiver", "TetherFi auto-start: pref ON but app not installed — skipped")
            }
        }

        // Release the sentinel: all scheduling is now done. If every bg task
        // already completed (pending == 1 sentinel only), this triggers finish.
        releaseOne.run()
    }

    /** Same predicate as MainActivity.isLayoutAutoStartConfigured — the Layout owns startup. */
    private fun isLayoutAutoStartConfigured(ctx: Context): Boolean = try {
        ClusterPrefs.isFissionAutoLayout(ctx) &&
            DaemonConfig.isFissionModeEnabled(ctx) &&
            LayoutPrefs.getFavoriteLayout(ctx) != null
    } catch (t: Throwable) {
        false
    }

    companion object {
        private val sMain = android.os.Handler(android.os.Looper.getMainLooper())
    }
}

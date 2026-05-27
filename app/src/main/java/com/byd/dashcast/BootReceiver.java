package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.byd.dashcast.beta.BetaConfig;
import com.byd.dashcast.beta.BetaProxyClient;

public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = SettingsActivity.PREFS_NAME;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean autoStartEnabled = prefs.getBoolean(SettingsActivity.PREF_BOOT_AUTO_START, false);
        boolean prewarmDaemon    = BetaConfig.isProxyDaemonEnabled(context);

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
        final Context appCtx = context.getApplicationContext();
        final PendingResult result = goAsync();
        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(1);
        final Runnable releaseOne = () -> {
            if (pending.decrementAndGet() == 0) result.finish();
        };

        if (prewarmDaemon) {
            // Phase 5 — pre-warm the proxy daemon at boot so the first user
            // action doesn't pay the ~1.9 s bootstrap cost (measured cold
            // sendInfo in build-178 device log = 1916 ms; warm = 3 ms).
            pending.incrementAndGet();
            new Thread(() -> {
                try {
                    AppLogger.i("BootReceiver", "beta daemon pre-warm starting...");
                    long t0 = System.currentTimeMillis();
                    boolean ok = BetaProxyClient.connect(appCtx);
                    long dt = System.currentTimeMillis() - t0;
                    AppLogger.i("BootReceiver", "beta daemon pre-warm "
                            + (ok ? "ok" : "FAILED") + " (" + dt + "ms)");
                } catch (Throwable t) {
                    // Pre-warm failure must never block boot: the runtime
                    // fallback in ShellGateway / AdbLocalClient will still
                    // route through legacy shell on demand.
                    AppLogger.w("BootReceiver",
                            "beta daemon pre-warm threw: " + t.getMessage());
                } finally {
                    releaseOne.run();
                }
            }, "beta-daemon-prewarm").start();
        }

        if (autoStartEnabled) {
            AppLogger.i("BootReceiver", "DashCast Auto-Boot: Starting projection automatically...");
            try {
                Intent serviceIntent = new Intent(context, ClusterService.class);
                context.startForegroundService(serviceIntent);
            } catch (Exception e) {
                AppLogger.e("BootReceiver", "Error starting ClusterService on boot: " + e.getMessage());
            }
        } else {
            AppLogger.d("BootReceiver", "DashCast Auto-Boot Projection is disabled by user.");
            // Projection not auto-started: move any apps that were on the cluster
            // (from the previous session) back to Display 0 so they don't get stuck
            // on the (possibly still-alive) VirtualDisplay.
            pending.incrementAndGet();
            new Thread(() -> {
                try {
                    MainActivity.cleanupDisplayAffinityAtBoot(context);
                } catch (Exception e) {
                    AppLogger.e("BootReceiver", "Display cleanup error: " + e.getMessage());
                } finally {
                    releaseOne.run();
                }
            }, "boot-display-cleanup").start();
        }

        // ─── v1.2.43 — Auto-start TetherFi hotspot at boot ─────────────────
        // Fires the same TOGGLE→START intent that HotspotActivity uses, after
        // a short delay so the Wi-Fi stack and PackageManager are fully up.
        // Independent of the projection auto-boot above: a user can want one
        // without the other.
        boolean hotspotAutoStart =
                prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false);
        if (hotspotAutoStart) {
            // Verify TetherFi is still installed (user may have uninstalled it).
            boolean installed = false;
            try {
                appCtx.getPackageManager().getPackageInfo("com.pyamsoft.tetherfi", 0);
                installed = true;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignore) { }

            if (installed) {
                pending.incrementAndGet();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent it = new Intent();
                        it.setClassName("com.pyamsoft.tetherfi",
                                "com.pyamsoft.tetherfi.tile.ProxyTileActivity");
                        it.putExtra("key_action", "START");
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                  | Intent.FLAG_ACTIVITY_NO_HISTORY);
                        appCtx.startActivity(it);
                        AppLogger.i("BootReceiver",
                                "TetherFi auto-start at boot: START dispatched");
                    } catch (Throwable t) {
                        AppLogger.w("BootReceiver",
                                "TetherFi auto-start failed: " + t.getMessage());
                    } finally {
                        releaseOne.run();
                    }
                }, 8_000L);
            } else {
                AppLogger.i("BootReceiver",
                        "TetherFi auto-start: pref ON but app not installed — skipped");
            }
        }

        // Release the sentinel: all scheduling is now done. If every bg task
        // already completed (pending == 1 sentinel only), this triggers finish.
        releaseOne.run();
    }
}

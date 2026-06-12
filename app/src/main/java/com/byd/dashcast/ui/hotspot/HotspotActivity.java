package com.byd.dashcast.ui.hotspot;
import com.byd.dashcast.MainActivity;
import com.byd.dashcast.infrastructure.AdbLocalClient;

import com.byd.dashcast.util.AppLogger;
import com.byd.dashcast.R;
import com.byd.dashcast.ui.diag.DiagActivity;
import com.byd.dashcast.ui.diag.SysInfoActivity;
import com.byd.dashcast.ui.log.LogActivity;
import com.byd.dashcast.ui.settings.SettingsActivity;
import com.byd.dashcast.ui.nav.NavRailLayouts;
import com.byd.dashcast.update.TetherFiUpdateChecker;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;

import com.byd.dashcast.proxy.ShellGateway;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hotspot screen — TetherFi-only (v1.2.42 refonte).
 *
 * History : v1.2.36..38 shipped an eligibility prober + ADB audit + privileged
 * perm scanner to determine whether DashCast could acquire TETHER_PRIVILEGED
 * and OVERRIDE_WIFI_CONFIG to drive the BYD softap directly. The audit proved
 * conclusively that BYD does not whitelist our cert for those perms, and on
 * DL3 the Settings APK strips the AOSP TetherSettings activity entirely.
 * The user pivoted to {@code com.pyamsoft.tetherfi} (open-source Wi-Fi Direct
 * group owner + SOCKS5/HTTP proxy) — this screen now drives TetherFi via its
 * single exported control surface and provides an optional watchdog.
 *
 * <p>TetherFi integration protocol (release tag 67, manifest inspected) :
 * <ul>
 *   <li>Target {@code com.pyamsoft.tetherfi/.tile.ProxyTileActivity}
 *       (exported, noHistory, excludeFromRecents — flashes and exits).</li>
 *   <li>String extra {@code "key_action"} =
 *       {@code "TOGGLE"} | {@code "START"} | {@code "STOP"} ;
 *       defaults to TOGGLE when absent.</li>
 *   <li>{@code MainActivity} (also exported) is used for the configuration UI
 *       (SSID / password / proxy port).</li>
 * </ul>
 *
 * <p>Watchdog : polls {@code dumpsys activity services com.pyamsoft.tetherfi}
 * via {@link AdbLocalClient} (uid 2000, has DUMP perm). When the foreground
 * service is gone, fires START. A cooldown prevents restart storms during
 * transient Wi-Fi Direct group renegotiations.
 */
public class HotspotActivity extends AppCompatActivity {

    private static final String TAG = "HotspotActivity";

    private static final String TF_PKG           = "com.pyamsoft.tetherfi";
    private static final String TF_TILE_CLS      = "com.pyamsoft.tetherfi.tile.ProxyTileActivity";
    private static final String TF_MAIN_CLS      = "com.pyamsoft.tetherfi.main.MainActivity";
    private static final String TF_KEY_ACTION    = "key_action";
    private static final String TF_ACTION_START  = "START";
    private static final String TF_ACTION_STOP   = "STOP";
    private static final String TF_ACTION_TOGGLE = "TOGGLE";
    private static final String TF_RELEASE_URL   =
            "https://github.com/pyamsoft/tetherfusenet/releases/latest";

    // ── Watchdog tuning ──────────────────────────────────────────────────────
    private static final long WATCHDOG_PERIOD_MS           = 20_000L;
    private static final long WATCHDOG_RESTART_COOLDOWN_MS = 30_000L;

    // ── Status colors ────────────────────────────────────────────────────────
    private static final int COLOR_OK_BG   = 0xFFE6F4EA; // soft green
    private static final int COLOR_OK_TX   = 0xFF1B5E20; // dark green
    private static final int COLOR_KO_BG   = 0xFFFDECEA; // soft red
    private static final int COLOR_KO_TX   = 0xFFB71C1C; // dark red

    // ── Views ────────────────────────────────────────────────────────────────
    private TextView       tvStatus;
    private TextView       tvUpdate;
    private MaterialButton btnStart;
    private MaterialButton btnStop;
    private MaterialButton btnToggle;
    private MaterialButton btnOpen;
    private MaterialSwitch swWatchdog;
    private MaterialSwitch swAutoStartBoot;
    private TextView       tvWatchdogStatus;
    // v1.2.44 — clients + live stats
    private TextView       tvClientsCount;
    private LinearLayout   llClientsList;
    private TextView       tvClientsEmpty;
    private TextView       tvStatUptime;
    private TextView       tvStatRx;
    private TextView       tvStatTx;

    // ── Watchdog runtime state ───────────────────────────────────────────────
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private boolean watchdogProbeInFlight;
    private long    lastRestartElapsed = -1L;
    private int     watchdogRestarts;
    private int     watchdogChecks;

    // ── Live stats runtime state (v1.2.44) ───────────────────────────────────
    // TrafficStats is bucketed per-UID. We cache TetherFi's UID once via PM,
    // and snapshot a baseline at first observed UP probe so the displayed
    // Rx/Tx reflects "this hotspot session" only (resets when service goes
    // DOWN). Uptime is measured locally from the same DOWN→UP edge.
    // v1.2.44 fix : the heavy probe runs every 5s (ADB dumpsys), but the
    // uptime chrono ticks every 1s independently for a fluid display.
    private static final long STATS_PERIOD_MS  = 5_000L;
    private static final long UPTIME_TICK_MS   = 1_000L;
    private final Handler statsHandler  = new Handler(Looper.getMainLooper());
    private final Handler uptimeHandler = new Handler(Looper.getMainLooper());
    private int  tfUid           = -1;
    private long upStartElapsed  = -1L;
    private long rxBaseline      = -1L;
    private long txBaseline      = -1L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hotspot);

        tvStatus         = findViewById(R.id.tv_tf_status);
        tvUpdate         = findViewById(R.id.tv_tf_update);
        btnStart         = findViewById(R.id.btn_tf_start);
        btnStop          = findViewById(R.id.btn_tf_stop);
        btnToggle        = findViewById(R.id.btn_tf_toggle);
        btnOpen          = findViewById(R.id.btn_tf_open);
        swWatchdog       = findViewById(R.id.sw_watchdog);
        swAutoStartBoot  = findViewById(R.id.sw_autostart_boot);
        tvWatchdogStatus = findViewById(R.id.tv_watchdog_status);
        tvClientsCount   = findViewById(R.id.tv_clients_count);
        llClientsList    = findViewById(R.id.ll_clients_list);
        tvClientsEmpty   = findViewById(R.id.tv_clients_empty);
        tvStatUptime     = findViewById(R.id.tv_stat_uptime);
        tvStatRx         = findViewById(R.id.tv_stat_rx);
        tvStatTx         = findViewById(R.id.tv_stat_tx);

        // Restore persisted toggles so they survive across launches and feed BootReceiver.
        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        swWatchdog.setChecked(
                prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, false));
        swAutoStartBoot.setChecked(
                prefs.getBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, false));

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        btnStart .setOnClickListener(v -> invokeTetherFiTile(TF_ACTION_START));
        btnStop  .setOnClickListener(v -> invokeTetherFiTile(TF_ACTION_STOP));
        btnToggle.setOnClickListener(v -> invokeTetherFiTile(TF_ACTION_TOGGLE));
        btnOpen  .setOnClickListener(v -> openTetherFiOrInstall());

        swWatchdog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton bv, boolean checked) {
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, checked)
                        .apply();
                if (checked) startWatchdog();
                else         stopWatchdog(true);
            }
        });

        swAutoStartBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton bv, boolean checked) {
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(SettingsActivity.PREF_HOTSPOT_AUTOSTART_BOOT, checked)
                        .apply();
                AppLogger.i(TAG, "hotspot autostart-boot " + (checked ? "ON" : "OFF"));
            }
        });

        tvUpdate.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (tag instanceof String) {
                Intent it = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse((String) tag));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(it); }
                catch (Throwable t) { /* ignore */ }
            }
        });

        // v1.2.44 — wire the navrail items so the Hotspot screen is consistent
        // with Diag / Sysinfo / Log / Settings (Hotspot itself is the active one).
        wireHotspotNavRail();
    }

    private void wireHotspotNavRail() {
        View navLogo     = findViewById(R.id.iv_nav_logo_hot);
        View navApps     = findViewById(R.id.nav_apps_hot);
        View navSettings = findViewById(R.id.nav_settings_hot);
        View navDiag     = findViewById(R.id.nav_diag_hot);
        View navSysinfo  = findViewById(R.id.nav_sysinfo_hot);
        View navLog      = findViewById(R.id.nav_log_hot);
        if (navLogo != null)     navLogo.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        if (navApps != null)     navApps.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        if (navSettings != null) navSettings.setOnClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); finish(); });
        if (navDiag != null)     navDiag.setOnClickListener(v -> { startActivity(new Intent(this, DiagActivity.class)); finish(); });
        if (navSysinfo != null)  navSysinfo.setOnClickListener(v -> { startActivity(new Intent(this, SysInfoActivity.class)); finish(); });
        if (navLog != null)      navLog.setOnClickListener(v -> { startActivity(new Intent(this, LogActivity.class)); finish(); });
        // v1.4.9-beta — Layouts
        NavRailLayouts.apply(this, R.id.nav_layouts_hot, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTetherFiStatus();
        // Re-check upstream on every resume (cheap, ~10 KB JSON, may surface a
        // freshly published release before the user thinks to look).
        checkForTetherFiUpdate();
        // Auto-restart watchdog polling if it was on and got paused in onPause-less mode.
        if (swWatchdog != null && swWatchdog.isChecked()
                && watchdogChecks == 0) {
            startWatchdog();
        }
        // v1.2.44 — start the live stats ticker (always on while screen visible;
        // cheap: TrafficStats is a /proc read for one UID).
        statsHandler.removeCallbacks(statsTick);
        statsHandler.post(statsTick);
        // v1.2.44 — independent 1Hz uptime ticker so the chrono visibly ticks
        // every second instead of only every STATS_PERIOD_MS (5s).
        uptimeHandler.removeCallbacks(uptimeTick);
        uptimeHandler.post(uptimeTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statsHandler.removeCallbacks(statsTick);
        uptimeHandler.removeCallbacks(uptimeTick);
    }

    private void checkForTetherFiUpdate() {
        if (getInstalledVersion(TF_PKG) == null) {
            if (tvUpdate != null) tvUpdate.setVisibility(View.GONE);
            return;
        }
        TetherFiUpdateChecker.check(this, new TetherFiUpdateChecker.Callback() {
            @Override public void onResult(TetherFiUpdateChecker.Result r) {
                if (isFinishing() || isDestroyed()) return;
                if (tvUpdate == null) return;
                if (r.isUpdateAvailable()) {
                    tvUpdate.setText(getString(
                            R.string.hotspot_tf_update_available,
                            r.installedVersionCode, r.remoteVersionCode));
                    tvUpdate.setTag(r.releasePageUrl);
                    tvUpdate.setVisibility(View.VISIBLE);
                } else {
                    tvUpdate.setVisibility(View.GONE);
                }
            }
            @Override public void onError(String message) {
                // Silent failure: keep the badge hidden. The user can still tap
                // "Ouvrir TetherFi" or "Installer TetherFi" if they really want
                // the latest. We log so the issue is visible in the journal.
                AppLogger.w(TAG, "TetherFi update check failed: " + message);
                if (isFinishing() || isDestroyed()) return;
                if (tvUpdate != null) tvUpdate.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopWatchdog(false);
        try {
            statsHandler.removeCallbacksAndMessages(null);
            uptimeHandler.removeCallbacksAndMessages(null);
        } catch (Throwable ignore) {}
        super.onDestroy();
    }

    // ── TetherFi presence / actions ──────────────────────────────────────────

    private void refreshTetherFiStatus() {
        String version = getInstalledVersion(TF_PKG);
        boolean installed = (version != null);
        if (installed) {
            tvStatus.setText(getString(R.string.hotspot_tf_status_installed, version));
            tvStatus.setBackgroundColor(COLOR_OK_BG);
            tvStatus.setTextColor(COLOR_OK_TX);
        } else {
            tvStatus.setText(R.string.hotspot_tf_status_missing);
            tvStatus.setBackgroundColor(COLOR_KO_BG);
            tvStatus.setTextColor(COLOR_KO_TX);
        }
        btnStart .setEnabled(installed);
        btnStop  .setEnabled(installed);
        btnToggle.setEnabled(installed);
        // btn_open is always enabled — when missing, it opens the install page.
        btnOpen.setText(installed
                ? R.string.hotspot_tf_open
                : R.string.hotspot_tf_install);
    }

    private String getInstalledVersion(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void invokeTetherFiTile(String action) {
        if (getInstalledVersion(TF_PKG) == null) {
            Toast.makeText(this, R.string.hotspot_tf_status_missing,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent it = new Intent();
        it.setClassName(TF_PKG, TF_TILE_CLS);
        it.putExtra(TF_KEY_ACTION, action);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY);
        try {
            startActivity(it);
            // Brief visual feedback in the watchdog status field if it's idle.
            if (swWatchdog == null || !swWatchdog.isChecked()) {
                tvWatchdogStatus.setText(getString(R.string.hotspot_tf_dispatched, action));
            }
        } catch (Throwable t) {
            Toast.makeText(this,
                    getString(R.string.hotspot_tf_dispatch_failed, action,
                            t.getClass().getSimpleName() + ": " + t.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openTetherFiOrInstall() {
        if (getInstalledVersion(TF_PKG) != null) {
            Intent it = new Intent();
            it.setClassName(TF_PKG, TF_MAIN_CLS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(it);
            } catch (Throwable t) {
                Toast.makeText(this,
                        getString(R.string.hotspot_tf_open_failed,
                                String.valueOf(t.getMessage())),
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Intent it = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(TF_RELEASE_URL));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(it);
            } catch (Throwable t) {
                Toast.makeText(this, TF_RELEASE_URL, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Watchdog ─────────────────────────────────────────────────────────────

    private void startWatchdog() {
        watchdogRestarts = 0;
        watchdogChecks   = 0;
        lastRestartElapsed = -1L;
        updateWatchdogLabel(getString(R.string.hotspot_watchdog_probing));
        watchdogHandler.removeCallbacksAndMessages(null);
        watchdogHandler.post(watchdogTick);
    }

    private void stopWatchdog(boolean userInitiated) {
        watchdogHandler.removeCallbacksAndMessages(null);
        watchdogProbeInFlight = false;
        if (userInitiated && tvWatchdogStatus != null) {
            tvWatchdogStatus.setText(getString(R.string.hotspot_watchdog_idle));
        }
    }

    private final Runnable watchdogTick = new Runnable() {
        @Override public void run() {
            if (watchdogProbeInFlight) {
                watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
                return;
            }
            if (getInstalledVersion(TF_PKG) == null) {
                updateWatchdogLabel(getString(R.string.hotspot_watchdog_tf_gone));
                if (swWatchdog != null) swWatchdog.setChecked(false);
                return;
            }
            watchdogProbeInFlight = true;
            watchdogChecks++;
            final int probeId = watchdogChecks;
            // Echo UP/DOWN so we always come back through onSuccess() — that way
            // a non-zero exit (service absent) doesn't surface as an ADB error.
            final String cmd =
                    "dumpsys activity services " + TF_PKG +
                    " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN";
            ShellGateway.execShellWithResult(HotspotActivity.this, cmd,
                    new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    watchdogProbeInFlight = false;
                    final boolean up = out != null && out.contains("UP");
                    runOnUiThread(() -> handleWatchdogResult(probeId, up, null));
                }
                @Override public void onError(String err) {
                    watchdogProbeInFlight = false;
                    runOnUiThread(() -> handleWatchdogResult(probeId, false, err));
                }
            });
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };

    private void handleWatchdogResult(int probeId, boolean up, String err) {
        // v1.2.44 — feed the live-stats session lifecycle on every probe,
        // regardless of whether the user toggled the watchdog UI off (so the
        // stats card stays accurate). ADB errors are treated as "unknown",
        // not as DOWN, so we don't bounce the session counters needlessly.
        if (err == null) onProbeTransition(up);
        if (swWatchdog == null || !swWatchdog.isChecked()) return; // user disabled
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        if (err != null) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_adb_err,
                    ts, probeId, err));
            return;
        }
        if (up) {
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_up,
                    ts, probeId, watchdogRestarts));
            return;
        }
        // DOWN — apply cooldown before restarting.
        long now = SystemClock.elapsedRealtime();
        if (lastRestartElapsed > 0
                && now - lastRestartElapsed < WATCHDOG_RESTART_COOLDOWN_MS) {
            long wait = (WATCHDOG_RESTART_COOLDOWN_MS - (now - lastRestartElapsed)) / 1000L;
            updateWatchdogLabel(getString(R.string.hotspot_watchdog_cooldown,
                    ts, probeId, (int) wait));
            return;
        }
        lastRestartElapsed = now;
        watchdogRestarts++;
        updateWatchdogLabel(getString(R.string.hotspot_watchdog_restart,
                ts, probeId, watchdogRestarts));
        AppLogger.i(TAG, "watchdog: TetherFi DOWN → firing START (restart #"
                + watchdogRestarts + ")");
        invokeTetherFiTile(TF_ACTION_START);
    }

    private void updateWatchdogLabel(String text) {
        if (tvWatchdogStatus != null) tvWatchdogStatus.setText(text);
    }

    // ── Live stats (v1.2.44) ─────────────────────────────────────────────────
    //
    // Hooked to the watchdog probe lifecycle: on first DOWN→UP transition we
    // snapshot the UID baseline and start the uptime clock; on UP→DOWN we
    // reset everything so the next session counts from zero.
    //
    // Note this only updates the SESSION counters; it doesn't drive the
    // hero status pill (that's still {@link #refreshTetherFiStatus()}, which
    // is presence-only).

    /** Called from {@link #handleWatchdogResult} on every probe (UP or DOWN). */
    private void onProbeTransition(boolean up) {
        if (up && upStartElapsed < 0) {
            // DOWN → UP : start a new session.
            upStartElapsed = SystemClock.elapsedRealtime();
            ensureTetherFiUidCached();
            if (tfUid > 0) {
                rxBaseline = TrafficStats.getUidRxBytes(tfUid);
                txBaseline = TrafficStats.getUidTxBytes(tfUid);
            }
        } else if (!up && upStartElapsed > 0) {
            // UP → DOWN : tear session down so the stats card visibly resets.
            upStartElapsed = -1L;
            rxBaseline = -1L;
            txBaseline = -1L;
        }
    }

    private void ensureTetherFiUidCached() {
        if (tfUid > 0) return;
        try {
            tfUid = getPackageManager().getApplicationInfo(TF_PKG, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            tfUid = -1;
        }
    }

    private final Runnable statsTick = new Runnable() {
        @Override public void run() {
            // Fire a lightweight probe of our own so the stats card works even
            // when the user has the watchdog auto-restart switch OFF. Same
            // dumpsys + grep used by the watchdog; we just don't act on DOWN.
            final String cmd =
                    "dumpsys activity services " + TF_PKG +
                    " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN";
            ShellGateway.execShellWithResult(HotspotActivity.this, cmd,
                    new AdbLocalClient.Callback() {
                @Override public void onSuccess(String out) {
                    final boolean up = out != null && out.contains("UP");
                    runOnUiThread(() -> { onProbeTransition(up); refreshStats(); });
                }
                @Override public void onError(String err) {
                    runOnUiThread(() -> refreshStats());
                }
            });
            // Clients enumeration runs alongside the presence probe.
            refreshClients();
            statsHandler.postDelayed(this, STATS_PERIOD_MS);
        }
    };

    /**
     * v1.2.44 — 1Hz uptime chrono. Updates only {@link #tvStatUptime} so the
     * user sees the seconds visibly tick by, without firing an ADB probe.
     */
    private final Runnable uptimeTick = new Runnable() {
        @Override public void run() {
            if (tvStatUptime != null) {
                if (upStartElapsed > 0) {
                    long up = SystemClock.elapsedRealtime() - upStartElapsed;
                    tvStatUptime.setText(formatUptime(up));
                } else {
                    tvStatUptime.setText("—");
                }
            }
            uptimeHandler.postDelayed(this, UPTIME_TICK_MS);
        }
    };

    private void refreshStats() {
        if (tvStatUptime == null) return;
        if (upStartElapsed < 0) {
            tvStatUptime.setText("—");
            tvStatRx.setText("—");
            tvStatTx.setText("—");
            return;
        }
        long up = SystemClock.elapsedRealtime() - upStartElapsed;
        tvStatUptime.setText(formatUptime(up));
        if (tfUid > 0 && rxBaseline >= 0) {
            long rx = TrafficStats.getUidRxBytes(tfUid) - rxBaseline;
            long tx = TrafficStats.getUidTxBytes(tfUid) - txBaseline;
            tvStatRx.setText(formatBytes(rx));
            tvStatTx.setText(formatBytes(tx));
        } else {
            tvStatRx.setText("—");
            tvStatTx.setText("—");
        }
    }

    private static String formatUptime(long ms) {
        if (ms < 0) return "—";
        long s  = ms / 1000L;
        long h  = s / 3600L;
        long m  = (s % 3600L) / 60L;
        long ss = s % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, ss);
        return String.format(Locale.US, "%02d:%02d", m, ss);
    }

    private static String formatBytes(long b) {
        if (b < 0) return "—";
        if (b < 1024L)            return b + " B";
        if (b < 1024L * 1024)     return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024)
                                  return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ── Clients enumeration (v1.2.44) ─────────────────────────────────────────────
    //
    // TetherFi exposes no IPC for its peer list. We harvest the same data the
    // framework already maintains:
    //   1. {@code dumpsys wifip2p} — the WifiP2pGroup that TetherFi owns lists
    //      every connected peer as a {@code WifiP2pDevice} with deviceAddress
    //      (MAC) and deviceName (the Wi-Fi Direct friendly name the peer
    //      announced during P2P provisioning).
    //   2. {@code /proc/net/arp} — the kernel ARP cache resolves MAC→IPv4 once
    //      the peer has spoken any IP traffic to the GO.
    // Per-client byte counters would require root (eBPF map per UID-tag), so
    // we display only name + IP.
    //
    // ADB shell (uid 2000) has android.permission.DUMP and read access to
    // /proc/net/arp, so both run from {@link AdbLocalClient}.

    private static final Pattern P_MAC =
            Pattern.compile("([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})");
    private static final Pattern P_NAME =
            Pattern.compile("deviceName[=:\\s]+([^,\\n\\r]+?)(?:,| deviceAddress| primary| status)");
    private static final Pattern P_NEWLINE  = Pattern.compile("\\r?\\n");
    private static final Pattern P_SPACES   = Pattern.compile("\\s+");
    private static final Pattern P_IP_ADDR  = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private static final class HClient {
        final String mac;
        final String name;   // may be null
        final String ip;     // may be null
        HClient(String mac, String name, String ip) {
            this.mac = mac; this.name = name; this.ip = ip;
        }
    }

    private void refreshClients() {
        // v1.2.44 — combine three reads in one ADB roundtrip; use sentinel
        // separators. `ip neigh show` is included as a third source because
        // some recent Android kernels surface neighbours there but not in
        // /proc/net/arp. We always use the union of what we find.
        final String cmd =
                "dumpsys wifip2p 2>/dev/null; " +
                "echo '===ARP==='; " +
                "cat /proc/net/arp 2>/dev/null; " +
                "echo '===NEIGH==='; " +
                "ip neigh show 2>/dev/null";
        ShellGateway.execShellWithResult(this, cmd,
                new AdbLocalClient.Callback() {
            @Override public void onSuccess(String out) {
                final List<HClient> list = parseClients(out);
                runOnUiThread(() -> renderClients(list));
            }
            @Override public void onError(String err) {
                // Keep last render on transient ADB errors.
            }
        });
    }

    /** Visible for parser unit testing — pure function, no Android deps. */
    static List<HClient> parseClients(String adbOut) {
        if (adbOut == null) return new ArrayList<>();
        final int sepArp   = adbOut.indexOf("===ARP===");
        final int sepNeigh = adbOut.indexOf("===NEIGH===");
        final String dumpsys = sepArp > 0 ? adbOut.substring(0, sepArp) : adbOut;
        final String arp;
        final String neigh;
        if (sepArp > 0 && sepNeigh > sepArp) {
            arp   = adbOut.substring(sepArp, sepNeigh);
            neigh = adbOut.substring(sepNeigh);
        } else if (sepArp > 0) {
            arp   = adbOut.substring(sepArp);
            neigh = "";
        } else {
            arp   = "";
            neigh = "";
        }

        // Build MAC → IP map from /proc/net/arp. Format:
        //   IP address       HW type     Flags       HW address            Mask     Device
        //   192.168.49.2     0x1         0x2         aa:bb:cc:dd:ee:ff     *        p2p-wlan0-0
        // v1.2.44 — also tag entries that sit on a p2p* / ap* / wlan*ap interface,
        // because those MACs are the *authoritative* list of connected hotspot
        // peers (dumpsys wifip2p does not always populate a client list on every
        // BYD ROM, see the empty-clients bug report).
        final Map<String, String> mac2ip = new HashMap<>();
        final LinkedHashMap<String, String> arpPeers = new LinkedHashMap<>();
        for (String line : P_NEWLINE.split(arp)) {
            String[] cols = P_SPACES.split(line.trim());
            if (cols.length < 6) continue;
            String ip    = cols[0];
            String flags = cols[2];
            String mac   = cols[3];
            String dev   = cols[5];
            if (!P_MAC.matcher(mac).matches()) continue;
            if ("00:00:00:00:00:00".equalsIgnoreCase(mac)) continue;
            if (!P_IP_ADDR.matcher(ip).matches()) continue;
            String mlow = mac.toLowerCase(Locale.US);
            mac2ip.put(mlow, ip);
            // Flags 0x0 == incomplete entry (no reply yet); 0x2 = complete (connected).
            boolean reachable = !"0x0".equalsIgnoreCase(flags);
            String dlow = dev == null ? "" : dev.toLowerCase(Locale.US);
            boolean onPeerInterface = dlow.startsWith("p2p-")
                    || dlow.startsWith("p2p")
                    || dlow.startsWith("ap")
                    || dlow.contains("ap0")
                    || dlow.contains("softap")
                    || dlow.endsWith("-ap");
            if (reachable && onPeerInterface) {
                arpPeers.put(mlow, ip);
            }
        }

        // Walk the dumpsys output looking for the WifiP2pGroup that belongs to
        // TetherFi. We're permissive: any "Client:" / "client list:" entry
        // containing a MAC, optionally preceded by a deviceName=... fragment
        // on the same logical record.
        final LinkedHashMap<String, HClient> byMac = new LinkedHashMap<>();
        boolean inClientList = false;
        String pendingName = null;
        for (String raw : P_NEWLINE.split(dumpsys)) {
            String line = raw.trim();
            String low  = line.toLowerCase(Locale.US);

            // Section gating: "Client list:" or "mClients:" headers, until a
            // blank line or a new section header. The framework dumps the GO's
            // own device separately under "Owner:" so we skip until we enter
            // a client section to avoid counting the local GO MAC.
            if (low.startsWith("client list") || low.startsWith("mclients")
                    || low.startsWith("clients:")) {
                inClientList = true;
                continue;
            }
            if (line.isEmpty()) { inClientList = false; pendingName = null; continue; }

            // Try to harvest a deviceName from the line for the next MAC found.
            Matcher nm = P_NAME.matcher(line);
            if (nm.find()) pendingName = nm.group(1).trim();

            // Most dumpsys variants prefix client lines with "Client:" or have
            // a single WifiP2pDevice per line with a MAC inside.
            boolean isClientLine = low.startsWith("client") || inClientList
                    || low.contains("wifip2pdevice");
            if (!isClientLine) continue;

            Matcher mm = P_MAC.matcher(line);
            while (mm.find()) {
                String mac = mm.group(1).toLowerCase(Locale.US);
                if (byMac.containsKey(mac)) continue;
                byMac.put(mac, new HClient(mac, pendingName,
                        mac2ip.get(mac)));
                pendingName = null;
            }
        }

        // v1.2.44 — ARP-on-peer-interface fallback. Add any MAC that is
        // demonstrably reachable on a p2p*/ap* interface but was missed by
        // the dumpsys parsing above. This is the authoritative source: if a
        // device has an IP on our tether interface, it IS a connected client.
        for (Map.Entry<String, String> e : arpPeers.entrySet()) {
            String mac = e.getKey();
            if (byMac.containsKey(mac)) continue;
            byMac.put(mac, new HClient(mac, null, e.getValue()));
        }

        // v1.2.44 — `ip neigh show` fallback. Format:
        //   192.168.49.2 dev p2p-wlan0-0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
        // Pick up STALE/DELAY/PROBE/REACHABLE on a peer interface; skip FAILED.
        for (String line : P_NEWLINE.split(neigh)) {
            String low = line.toLowerCase(Locale.US);
            if (low.isEmpty() || low.startsWith("==")) continue;
            if (low.contains(" failed") || low.contains(" incomplete")) continue;
            int devIdx = low.indexOf(" dev ");
            if (devIdx < 0) continue;
            String afterDev = low.substring(devIdx + 5).trim();
            String dev = P_SPACES.split(afterDev.trim(), 2)[0];
            boolean onPeerInterface = dev.startsWith("p2p-") || dev.startsWith("p2p")
                    || dev.startsWith("ap") || dev.contains("ap0")
                    || dev.contains("softap") || dev.endsWith("-ap");
            if (!onPeerInterface) continue;
            Matcher mm = P_MAC.matcher(line);
            if (!mm.find()) continue;
            String mac = mm.group(1).toLowerCase(Locale.US);
            if ("00:00:00:00:00:00".equalsIgnoreCase(mac)) continue;
            if (byMac.containsKey(mac)) continue;
            String ip = P_SPACES.split(line.trim(), 2)[0];
            if (!P_IP_ADDR.matcher(ip).matches()) ip = null;
            byMac.put(mac, new HClient(mac, null, ip));
        }
        return new ArrayList<>(byMac.values());
    }

    private void renderClients(List<HClient> clients) {
        if (llClientsList == null || tvClientsCount == null) return;
        tvClientsCount.setText(String.valueOf(clients.size()));
        llClientsList.removeAllViews();
        if (clients.isEmpty()) {
            if (tvClientsEmpty != null) tvClientsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (tvClientsEmpty != null) tvClientsEmpty.setVisibility(View.GONE);
        for (HClient c : clients) llClientsList.addView(buildClientRow(c));
    }

    private View buildClientRow(HClient c) {
        int padH = dp(4), padV = dp(8);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, padV, padH, padV);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconLp =
                new LinearLayout.LayoutParams(dp(20), dp(20));
        iconLp.setMarginEnd(dp(10));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(R.drawable.ic_devices);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.md_on_surface_variant));

        TextView tvName = new TextView(this);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvName.setLayoutParams(nameLp);
        tvName.setText(c.name != null && !c.name.isEmpty()
                ? c.name
                : friendlyMacName(c.mac));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvName.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface));
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView tvIp = new TextView(this);
        tvIp.setText(c.ip != null ? c.ip : c.mac);
        tvIp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvIp.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvIp.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant));

        row.addView(icon);
        row.addView(tvName);
        row.addView(tvIp);
        return row;
    }

    private String friendlyMacName(String mac) {
        // v1.2.56-beta — i18n via R.string.hotspot_client_default_name.
        // "aa:bb:cc:dd:ee:ff" → "Appareil ee:ff" / "Device ee:ff" / …
        String suffix = (mac != null && mac.length() >= 5)
                ? mac.substring(mac.length() - 5)
                : "";
        return getString(R.string.hotspot_client_default_name, suffix);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

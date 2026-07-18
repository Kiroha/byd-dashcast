package com.byd.dashcast.hud;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;
import com.byd.dashcast.proxy.ProxyClient;
import com.byd.dashcast.proxy.daemon.CanWriteVerbs;
import com.byd.dashcast.platform.Platform;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * HudController — navigation HUD orchestration singleton.
 *
 * <p>Receives parsed {@link HudNavigationData} from {@link MapNotificationListenerService}
 * and drives the BYD instrument cluster HUD via {@link CanBusController}. Mirrors
 * the OpenBYD 2.2 {@code HudController} logic:
 * <ul>
 *   <li>Deduplication: only writes to CAN when a value actually changed.</li>
 *   <li>Lifecycle management: {@link #ensureHudActive()} activates the cluster
 *       navigation lane on the first update of a session.</li>
 *   <li>AMap broadcast: emits {@code AUTONAVI_STANDARD_BROADCAST_SEND} so BYD's
 *       built-in cluster display layer also receives the navigation state.</li>
 *   <li>Full register clear on {@link #closeNavigation(Context)}.</li>
 *   <li>Staleness watchdog: clears the HUD after {@code STALE_MS} with no update so a
 *       "frozen arrow" (stale latched CAN guidance) cannot persist when nav updates stop
 *       — field report INC-20260715-125508.</li>
 * </ul>
 *
 * <p>All CAN writes are fire-and-forget: exceptions are caught and logged so a
 * failed daemon call never crashes the notification listener.
 */
public final class HudController {

    private static final String TAG = "HudController";

    public static final HudController INSTANCE = new HudController();

    // ─── Deduplication state ──────────────────────────────────────────────

    private boolean isHudActive;

    /**
     * Cached DiLink-3 gate. The windshield-HUD nav feature is DL3-only: DL3 is the
     * proven platform whose HUD MCU consumes our CAN guidance (video-confirmed across
     * SX245→SX326). DL5.1 uses a different HUD scheme and AAOS uses car_service, not
     * BYDAuto CAN — so we must never drive them from here. The platform is fixed at
     * boot, so resolve once. {@code null} = not yet resolved.
     */
    private Boolean isDl3Hud;

    private String  lastRoadName       = "";
    private int     lastIconId         = -1;
    private int     lastDistance       = -1;
    private int     lastSecondaryIconId  = -1;
    private int     lastSecondaryDistance = -1;
    private int     lastRestHour       = -1;
    private int     lastRestMinute     = -1;
    private long    lastRestMileage    = -1L;
    private int     lastEtaHour        = -1;
    private int     lastEtaMinute      = -1;

    // ─── Staleness watchdog ───────────────────────────────────────────────
    // The prod guidance path is on-change / dedup with NO periodic re-push, so if the nav app's
    // notifications stop flowing (nav paused/ended without a clean stop, a parse gap, Maps in the
    // background…) the last CAN arrow stays LATCHED on the HUD → a "frozen arrow" (field report
    // INC-20260715-125508). This watchdog clears the HUD after STALE_MS with no update — mirroring
    // OpenBYD's staleness-close. It ticks on a single daemon scheduler thread; all HudController
    // state is guarded by `this`, so the watchdog and the notification-writer thread never race.
    private static final long STALE_MS = 12_000L;
    private volatile long lastUpdateMs;
    private volatile Context appContext;   // process-scoped app context (safe to retain) for the watchdog
    private ScheduledExecutorService watchdog;
    private ScheduledFuture<?> watchdogTask;

    private HudController() {}

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Push a navigation update to the cluster HUD.
     *
     * <p>Activates the HUD on first call of a session, then applies deduplication
     * so we only write CAN registers whose value has changed. Sends the AMap
     * broadcast unconditionally (the BYD cluster compositor needs it every update).
     *
     * <p>If {@code data.distanceMeters} is negative the update is discarded
     * (invalid parse result from the notification listener).
     */
    public synchronized void updateNavigation(Context ctx, HudNavigationData data) {
        if (data.distanceMeters < 0) return;
        if (!isDiLink3Hud(ctx)) return;   // DL3-only feature (video-proven); DL5.1/AAOS excluded

        // Liveness for the staleness watchdog — refreshed on EVERY notification (even deduped
        // ones), so the watchdog fires only when nav updates truly STOP, not on an unchanged frame.
        appContext = ctx.getApplicationContext();
        lastUpdateMs = SystemClock.elapsedRealtime();

        ensureHudActive();

        // OEM parity (B2, ref. AmapService.sendNavigateInfoToCAN): the factory nav re-writes
        // INSTRUMENT_SEND_NAVI_STATUS=active on EVERY guidance frame — its only always-written
        // register. ensureHudActive() asserts it just once at nav-start; a cluster that reads it as a
        // liveness heartbeat can drop the guidance widget on a long step with no icon/distance change.
        // Re-assert it on every update (even deduped ones) so the widget survives. Best-effort.
        try {
            CanBusController.sendNaviStatusHeartbeat();
        } catch (ProxyClient.ProxyException e) {
            Log.w(TAG, "naviStatus heartbeat failed: " + e.getMessage());
        }

        // 1. Simple guidance (icon + distance).
        if (data.iconId != lastIconId || data.distanceMeters != lastDistance) {
            try {
                CanBusController.sendSimpleGuidance(data.iconId, data.distanceMeters);
                lastIconId    = data.iconId;
                lastDistance  = data.distanceMeters;
            } catch (ProxyClient.ProxyException e) {
                Log.w(TAG, "sendSimpleGuidance failed: " + e.getMessage());
            }
        }

        // 2. Secondary guidance — always clear (Google Maps / Waze don't send secondary).
        if (lastSecondaryIconId != -1) {
            try {
                CanBusController.sendSecondaryGuidance(-1, -1);
                lastSecondaryIconId    = -1;
                lastSecondaryDistance  = -1;
            } catch (ProxyClient.ProxyException e) {
                Log.w(TAG, "clearSecondary failed: " + e.getMessage());
            }
        }

        // 3. Road name.
        if (!data.roadName.isEmpty() && !data.roadName.equals(lastRoadName)) {
            try {
                CanBusController.sendNextStreetName(data.roadName);
                lastRoadName = data.roadName;
            } catch (ProxyClient.ProxyException e) {
                Log.w(TAG, "sendNextStreetName failed: " + e.getMessage());
            }
        }

        // 4. Remaining route (time + distance) with clamping.
        if (data.remainingDistanceMeters != null && data.remainingTimeSeconds != null) {
            int  totalMinutes = data.remainingTimeSeconds / 60;
            int  hours        = Math.min(totalMinutes / 60, 254);
            int  minutes      = Math.min(totalMinutes % 60, 59);
            long mileage      = Math.min((long) data.remainingDistanceMeters, 0xFFFFFFFEL);
            if (hours != lastRestHour || minutes != lastRestMinute || mileage != lastRestMileage) {
                try {
                    CanBusController.sendRestRoute(hours, minutes, mileage);
                    lastRestHour    = hours;
                    lastRestMinute  = minutes;
                    lastRestMileage = mileage;
                } catch (ProxyClient.ProxyException e) {
                    Log.w(TAG, "sendRestRoute failed: " + e.getMessage());
                }
            }
        }

        // 4b. Arrival wall-clock ETA (OEM EXPECTED_ARRIVE_* family) — "arrive at HH:MM". Distinct
        //     from the remaining DURATION above (step 4). Day-code = today (1): a Maps/Waze
        //     notification carries no day. Deduped on (hour, minute).
        if (data.etaHour != null && data.etaMinute != null
                && (data.etaHour != lastEtaHour || data.etaMinute != lastEtaMinute)) {
            try {
                CanBusController.sendExpectedArrival(1, data.etaHour, data.etaMinute);
                lastEtaHour   = data.etaHour;
                lastEtaMinute = data.etaMinute;
            } catch (ProxyClient.ProxyException e) {
                Log.w(TAG, "sendExpectedArrival failed: " + e.getMessage());
            }
        }

        // 5. AMap broadcast — unconditional, the cluster compositor needs it every step.
        sendAmapBroadcast(ctx, data);
    }

    /**
     * Stop navigation and reset the cluster HUD to its default state.
     *
     * <p>Clears all CAN registers (via {@link CanBusController#setNaviActive(boolean)}),
     * sends the AMap stop broadcast, and resets all deduplication state.
     */
    public synchronized void closeNavigation(Context ctx) {
        if (!isHudActive) return;
        try {
            CanBusController.setNaviActive(false);
        } catch (ProxyClient.ProxyException e) {
            Log.w(TAG, "setNaviActive(false) failed: " + e.getMessage());
        }
        isHudActive = false;
        stopWatchdog();
        sendAmapStopBroadcast(ctx);
        resetState();
    }

    /** Whether the HUD is currently active (i.e. the cluster nav lane is open). */
    public boolean isHudActive() {
        return isHudActive;
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private void ensureHudActive() {
        if (isHudActive) return;
        // Turn the windshield HUD ON (DL3 feature id SET_HUD_SWITCH=1) so nav shows even
        // if the user had the HUD switched off — matches the video-proven CAN→HUD bench.
        // Best-effort: a failure here must not block nav activation. DL3 gating is enforced
        // by the caller (isDiLink3Hud). We leave the HUD switch ON when nav ends (like the
        // bench) — closeNavigation only clears the nav registers, not the user's HUD switch.
        try {
            CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON);
        } catch (ProxyClient.ProxyException e) {
            Log.w(TAG, "SET_HUD_SWITCH on failed: " + e.getMessage());
        }
        try {
            CanBusController.setNaviActive(true);
            isHudActive = true;
            armWatchdog();
        } catch (ProxyClient.ProxyException e) {
            Log.w(TAG, "setNaviActive(true) failed: " + e.getMessage());
        }
    }

    // ─── Staleness watchdog impl ──────────────────────────────────────────

    /** Start the periodic staleness check once nav is active (idempotent). */
    private void armWatchdog() {
        if (watchdogTask != null) return;
        if (watchdog == null) {
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hud-nav-watchdog");
                t.setDaemon(true);
                return t;
            });
        }
        watchdogTask = watchdog.scheduleWithFixedDelay(
                this::closeIfStale, STALE_MS, STALE_MS / 2, TimeUnit.MILLISECONDS);
    }

    /** Cancel the staleness check AND shut down its scheduler thread (nav stopped). armWatchdog
     *  recreates the executor on the next nav session, so this owns the thread's lifetime — it no
     *  longer leaks a live daemon thread for the whole process on this static singleton, nor keeps
     *  firing after the listener service is torn down (nav-end / stale-close both route here). */
    private void stopWatchdog() {
        ScheduledFuture<?> t = watchdogTask;
        if (t != null) { t.cancel(false); watchdogTask = null; }
        if (watchdog != null) { watchdog.shutdown(); watchdog = null; }
    }

    /**
     * Watchdog tick: if the HUD is active but no nav update has arrived for {@link #STALE_MS},
     * the arrow is frozen — clear the HUD so a stale/wrong arrow does not persist. Synchronized on
     * {@code this} (like update/close) so it never races the notification-writer thread.
     */
    private synchronized void closeIfStale() {
        if (!isHudActive) return;
        if (SystemClock.elapsedRealtime() - lastUpdateMs < STALE_MS) return;
        Context ctx = appContext;
        if (ctx == null) return;
        Log.i(TAG, "nav stale >" + STALE_MS + "ms with no update — clearing frozen HUD");
        // Fail fast: this runs on the dedicated hud-nav-watchdog thread and holds `this`
        // (updateNavigation is also synchronized on this). A ~23s blocking daemon bootstrap
        // inside closeNavigation's CAN write would stall guidance frames — opt this thread out
        // of the blocking reconnect (mirrors F6). The watchdog thread does nothing else, so no
        // reset is needed.
        ProxyClient.setNonBlockingReconnect(true);
        closeNavigation(ctx);   // reentrant (same thread holds `this`); also stops the watchdog
    }

    /**
     * Whether this head unit is a DiLink 3 (and not Android Automotive). Cached — the
     * platform is fixed at boot. Fails safe to {@code false} (drive nothing) on any error
     * so an unknown platform is never hit with DL3-specific CAN writes.
     */
    private boolean isDiLink3Hud(Context ctx) {
        Boolean cached = isDl3Hud;
        if (cached != null) return cached;
        boolean dl3;
        try {
            dl3 = Platform.get().isDiLink3(ctx) && !AaosClusterProbe.INSTANCE.isAaos(ctx);
        } catch (Throwable t) {
            dl3 = false;
        }
        isDl3Hud = dl3;
        return dl3;
    }

    private void resetState() {
        lastRoadName          = "";
        lastIconId            = -1;
        lastDistance          = -1;
        lastSecondaryIconId   = -1;
        lastSecondaryDistance = -1;
        lastRestHour          = -1;
        lastRestMinute        = -1;
        lastRestMileage       = -1L;
        lastEtaHour           = -1;
        lastEtaMinute         = -1;
    }

    // ─── AMap broadcast ───────────────────────────────────────────────────

    /**
     * Sends {@code AUTONAVI_STANDARD_BROADCAST_SEND} (TYPE=8) matching the
     * OpenBYD 2.2 {@code sendStandardAmapBroadcast} implementation. This
     * intent is received by the BYD cluster compositor to update its own
     * navigation overlay independently of the raw CAN writes above.
     */
    private void sendAmapBroadcast(Context ctx, HudNavigationData d) {
        try {
            int amapIcon = mapToAmapIcon(d.iconId);
            Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
            intent.putExtra("KEY_TYPE",            10001);
            intent.putExtra("TYPE",                8);
            intent.putExtra("EXTRA_STATE",         8);
            intent.putExtra("EXTRA_IS_FOREGROUND", 0);
            intent.putExtra("IS_BYD_MAP",          true);
            intent.putExtra("IS_BYD_BAIDU_MAP",    false);
            intent.putExtra("NEW_ICON",            amapIcon);
            intent.putExtra("SEG_REMAIN_DIS",      d.distanceMeters);
            intent.putExtra("NEXT_ROAD_NAME",      d.roadName);
            intent.putExtra("ROUTE_REMAIN_DIS",  d.remainingDistanceMeters != null ? d.remainingDistanceMeters : -1);
            intent.putExtra("ROUTE_REMAIN_TIME", d.remainingTimeSeconds    != null ? d.remainingTimeSeconds    : -1);
            // NEXT_NEXT / secondary extras: only added when there is actual data (matching
            // OpenBYD sendStandardAmapBroadcast — the BYD compositor must not receive these
            // keys with value -1, only their absence signals "no secondary info").
            // Google Maps notifications never carry secondary guidance, so we never add them.
            // Human-readable distance strings.
            intent.putExtra("SEG_REMAIN_DIS_AUTO", formatMeters(d.distanceMeters));
            if (d.remainingDistanceMeters != null) {
                intent.putExtra("ROUTE_REMAIN_DIS_AUTO", formatMeters(d.remainingDistanceMeters));
            }
            if (d.remainingTimeSeconds != null) {
                intent.putExtra("ROUTE_REMAIN_TIME_AUTO",   formatSeconds(d.remainingTimeSeconds));
                intent.putExtra("ROUTE_REMAIN_TIME_STRING", formatSeconds(d.remainingTimeSeconds));
            }
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "AMap broadcast failed", e);
        }
    }

    /** Sends {@code AUTONAVI_STANDARD_BROADCAST_SEND} (TYPE=9) to stop navigation. */
    private void sendAmapStopBroadcast(Context ctx) {
        try {
            Intent intent = new Intent("AUTONAVI_STANDARD_BROADCAST_SEND");
            intent.putExtra("KEY_TYPE",            10001);
            intent.putExtra("TYPE",                9);
            intent.putExtra("EXTRA_STATE",         1);
            intent.putExtra("EXTRA_IS_FOREGROUND", 1);
            intent.putExtra("IS_BYD_MAP",          true);
            intent.putExtra("IS_BYD_BAIDU_MAP",    false);
            intent.putExtra("NEW_ICON",            -1);
            intent.putExtra("SEG_REMAIN_DIS",      -1);
            intent.putExtra("NEXT_ROAD_NAME",      "");
            intent.putExtra("ROUTE_REMAIN_DIS",    -1);
            intent.putExtra("ROUTE_REMAIN_TIME",   -1);
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "AMap stop broadcast failed", e);
        }
    }

    /**
     * Maps a BYD turn icon ID to the AMap broadcast icon value.
     * Based on OpenBYD 2.2 {@code mapTurnKindToAmapBroadcastIcon}:
     * destination (48) → 12; everything else → 2 (generic arrow).
     */
    private static int mapToAmapIcon(int bydIconId) {
        return bydIconId == CanBusController.ICON_DESTINATION ? 12 : 2;
    }

    private static String formatMeters(int meters) {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.1f km", meters / 1000.0f);
        }
        return meters + " m";
    }

    private static String formatSeconds(int totalSeconds) {
        int totalMinutes = totalSeconds / 60;
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        return h > 0 ? (h + "h " + m + "m") : (m + " min");
    }
}

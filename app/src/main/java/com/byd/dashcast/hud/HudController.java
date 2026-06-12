package com.byd.dashcast.hud;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.byd.dashcast.system.CanBusController;
import com.byd.dashcast.proxy.ProxyClient;

import java.util.Arrays;
import java.util.Locale;

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
    private String  lastRoadName       = "";
    private int     lastIconId         = -1;
    private int     lastDistance       = -1;
    private int     lastSecondaryIconId  = -1;
    private int     lastSecondaryDistance = -1;
    private int     lastRestHour       = -1;
    private int     lastRestMinute     = -1;
    private long    lastRestMileage    = -1L;

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
    public void updateNavigation(Context ctx, HudNavigationData data) {
        if (data.distanceMeters < 0) return;

        ensureHudActive();

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

        // 5. AMap broadcast — unconditional, the cluster compositor needs it every step.
        sendAmapBroadcast(ctx, data);
    }

    /**
     * Stop navigation and reset the cluster HUD to its default state.
     *
     * <p>Clears all CAN registers (via {@link CanBusController#setNaviActive(boolean)}),
     * sends the AMap stop broadcast, and resets all deduplication state.
     */
    public void closeNavigation(Context ctx) {
        if (!isHudActive) return;
        try {
            CanBusController.setNaviActive(false);
        } catch (ProxyClient.ProxyException e) {
            Log.w(TAG, "setNaviActive(false) failed: " + e.getMessage());
        }
        isHudActive = false;
        sendAmapStopBroadcast(ctx);
        resetState();
    }

    /** Whether the HUD is currently active (i.e. the cluster nav lane is open). */
    public boolean isHudActive() {
        return isHudActive;
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private void ensureHudActive() {
        if (!isHudActive) {
            try {
                CanBusController.setNaviActive(true);
                isHudActive = true;
            } catch (ProxyClient.ProxyException e) {
                Log.w(TAG, "setNaviActive(true) failed: " + e.getMessage());
            }
        }
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

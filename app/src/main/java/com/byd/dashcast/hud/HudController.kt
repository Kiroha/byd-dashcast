package com.byd.dashcast.hud

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

import com.byd.dashcast.platform.Platform
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.daemon.CanWriteVerbs
import com.byd.dashcast.system.CanBusController

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * HudController — navigation HUD orchestration singleton.
 *
 * Receives parsed [HudNavigationData] from [MapNotificationListenerService] and drives the BYD
 * instrument cluster HUD via [CanBusController]. Mirrors the OpenBYD 2.2 `HudController` logic:
 *  - Deduplication: only writes to CAN when a value actually changed.
 *  - Lifecycle management: [ensureHudActive] activates the cluster navigation lane on the first
 *    update of a session.
 *  - AMap broadcast: emits `AUTONAVI_STANDARD_BROADCAST_SEND` so BYD's built-in cluster display
 *    layer also receives the navigation state.
 *  - Full register clear on [closeNavigation].
 *  - Staleness watchdog: clears the HUD after `STALE_MS` with no update so a "frozen arrow"
 *    (stale latched CAN guidance) cannot persist when nav updates stop — field report
 *    INC-20260715-125508.
 *
 * All CAN writes are fire-and-forget: exceptions are caught and logged so a failed daemon call
 * never crashes the notification listener.
 *
 * Kotlin port note: this is an `object`, so `HudController.INSTANCE` is still the same public
 * static final field the Java singleton exposed, and the `@Synchronized` methods still lock that
 * same instance.
 */
object HudController {

    private const val TAG = "HudController"
    internal const val AMAP_BROADCAST_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    internal const val AMAP_RECEIVER_PACKAGE = "com.example.amapservice"

    // ─── Deduplication state ──────────────────────────────────────────────

    /**
     * Whether the HUD is currently active (i.e. the cluster nav lane is open).
     *
     * Volatile: [noteNavFrameSeen] reads it without the lock the writers hold.
     */
    @Volatile
    var isHudActive: Boolean = false
        private set

    /**
     * Whether the CAN side of activation was actually accepted.
     *
     * Separate from [isHudActive] on purpose, and the separation is the AUD-003 follow-up fix.
     * The two mean different things: isHudActive means "a nav session is open and something will
     * have to tear it down", which is true the moment we decide to open one; this means "the car
     * acknowledged SETTING_NAVI_SCREEN_STATUS", which may never become true on a car that refuses
     * the register. Conflating them left the close path unreachable on exactly those cars.
     */
    private var naviActiveAcked = false

    /**
     * When the last CAN activation attempt ran, so a refusing car is retried but not spammed.
     *
     * Initialised to [NEVER_ATTEMPTED] rather than 0. Zero is a real
     * [SystemClock.elapsedRealtime] value — the instant the head unit booted — so a 0 sentinel is
     * indistinguishable from an attempt made in the first milliseconds of uptime, and on a car
     * where that happened the first retry would have been silently delayed by a whole cadence.
     * Cheap to get right, and the unit test for the predicate is what surfaced it.
     */
    private var lastActivationAttemptMs = NEVER_ATTEMPTED

    /** Sentinel for "no activation attempt has run yet"; cannot collide with elapsedRealtime(). */
    internal const val NEVER_ATTEMPTED = Long.MIN_VALUE

    /** Retry cadence for a refused activation. Guidance frames arrive far faster than this. */
    private const val ACTIVATION_RETRY_MS = 5_000L

    /**
     * Cached DiLink-3 gate. The windshield-HUD nav feature is DL3-only: DL3 is the proven platform
     * whose HUD MCU consumes our CAN guidance (video-confirmed across SX245→SX326). DL5.1 uses a
     * different HUD scheme and AAOS uses car_service, not BYDAuto CAN — so we must never drive
     * them from here. The platform is fixed at boot, so resolve once. `null` = not yet resolved.
     */
    private var isDl3Hud: Boolean? = null

    private var lastRoadName = ""
    private var lastIconId = -1
    private var lastDistance = -1
    private var lastSecondaryIconId = -1
    private var lastSecondaryDistance = -1
    private var lastRestHour = -1
    private var lastRestMinute = -1
    private var lastRestMileage = -1L
    private var lastEtaHour = -1
    private var lastEtaMinute = -1

    // ─── Staleness watchdog ───────────────────────────────────────────────
    // The prod guidance path is on-change / dedup with NO periodic re-push, so if the nav app's
    // notifications stop flowing (nav paused/ended without a clean stop, a parse gap, Maps in the
    // background…) the last CAN arrow stays LATCHED on the HUD → a "frozen arrow" (field report
    // INC-20260715-125508). This watchdog clears the HUD after STALE_MS with no update — mirroring
    // OpenBYD's staleness-close. It ticks on a single daemon scheduler thread; all HudController
    // state is guarded by `this`, so the watchdog and the notification-writer thread never race.
    private const val STALE_MS = 12_000L

    @Volatile private var lastUpdateMs = 0L

    /** process-scoped app context (safe to retain) for the watchdog */
    @Volatile private var appContext: Context? = null

    private var watchdog: ScheduledExecutorService? = null
    private var watchdogTask: ScheduledFuture<*>? = null

    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Push a navigation update to the cluster HUD.
     *
     * Activates the HUD on first call of a session, then applies deduplication so we only write
     * CAN registers whose value has changed. Sends the AMap broadcast unconditionally (the BYD
     * cluster compositor needs it every update).
     *
     * If `data.distanceMeters` is negative the update is discarded (invalid parse result from the
     * notification listener).
     */
    @Synchronized
    fun updateNavigation(ctx: Context, data: HudNavigationData): Boolean {
        if (data.distanceMeters < 0) return false
        if (!isDiLink3Hud(ctx)) return false   // DL3-only feature (video-proven); DL5.1/AAOS excluded

        // Keep the process context before arming the watchdog, but advance its liveness timestamp
        // only after a guidance output confirms delivery below.
        appContext = ctx.applicationContext

        ensureHudActive()

        // OEM parity (B2, ref. AmapService.sendNavigateInfoToCAN): the factory nav re-writes
        // INSTRUMENT_SEND_NAVI_STATUS=active on EVERY guidance frame — its only always-written
        // register. ensureHudActive() asserts it just once at nav-start; a cluster that reads it as a
        // liveness heartbeat can drop the guidance widget on a long step with no icon/distance change.
        // Re-assert it on every update (even deduped ones) so the widget survives. Best-effort.
        try {
            CanBusController.sendNaviStatusHeartbeat()
        } catch (e: ProxyClient.ProxyException) {
            Log.w(TAG, "naviStatus heartbeat failed: " + e.message)
        }

        var canFrameDelivered = true

        // 1. Simple guidance (icon + distance).
        if (data.iconId != lastIconId || data.distanceMeters != lastDistance) {
            try {
                CanBusController.sendSimpleGuidance(data.iconId, data.distanceMeters)
                lastIconId = data.iconId
                lastDistance = data.distanceMeters
            } catch (e: ProxyClient.ProxyException) {
                canFrameDelivered = false
                Log.w(TAG, "sendSimpleGuidance failed: " + e.message)
            }
        }

        // 2. Secondary guidance — always clear (Google Maps / Waze don't send secondary).
        if (lastSecondaryIconId != -1) {
            try {
                CanBusController.sendSecondaryGuidance(-1, -1)
                lastSecondaryIconId = -1
                lastSecondaryDistance = -1
            } catch (e: ProxyClient.ProxyException) {
                canFrameDelivered = false
                Log.w(TAG, "clearSecondary failed: " + e.message)
            }
        }

        // 3. Road name.
        if (data.roadName.isNotEmpty() && data.roadName != lastRoadName) {
            try {
                CanBusController.sendNextStreetName(data.roadName)
                lastRoadName = data.roadName
            } catch (e: ProxyClient.ProxyException) {
                canFrameDelivered = false
                Log.w(TAG, "sendNextStreetName failed: " + e.message)
            }
        }

        // 4. Remaining route (time + distance) with clamping.
        val remainingDistance = data.remainingDistanceMeters
        val remainingTime = data.remainingTimeSeconds
        if (remainingDistance != null && remainingTime != null) {
            val totalMinutes = remainingTime / 60
            val hours = minOf(totalMinutes / 60, 254)
            val minutes = minOf(totalMinutes % 60, 59)
            val mileage = minOf(remainingDistance.toLong(), 0xFFFFFFFEL)
            if (hours != lastRestHour || minutes != lastRestMinute || mileage != lastRestMileage) {
                try {
                    CanBusController.sendRestRoute(hours, minutes, mileage)
                    lastRestHour = hours
                    lastRestMinute = minutes
                    lastRestMileage = mileage
                } catch (e: ProxyClient.ProxyException) {
                    canFrameDelivered = false
                    Log.w(TAG, "sendRestRoute failed: " + e.message)
                }
            }
        }

        // 4b. Arrival wall-clock ETA (OEM EXPECTED_ARRIVE_* family) — "arrive at HH:MM". Distinct
        //     from the remaining DURATION above (step 4). Day-code = today (1): a Maps/Waze
        //     notification carries no day. Deduped on (hour, minute).
        val etaHour = data.etaHour
        val etaMinute = data.etaMinute
        if (etaHour != null && etaMinute != null
                && (etaHour != lastEtaHour || etaMinute != lastEtaMinute)) {
            try {
                CanBusController.sendExpectedArrival(1, etaHour, etaMinute)
                lastEtaHour = etaHour
                lastEtaMinute = etaMinute
            } catch (e: ProxyClient.ProxyException) {
                canFrameDelivered = false
                Log.w(TAG, "sendExpectedArrival failed: " + e.message)
            }
        }

        // 4c. CLUSTER path — push the same maneuver through the OEM AutoContainer channel
        //     (sendInfo2(4, NaviInfo)). Independent of CAN: this is what lights the instrument
        //     cluster, and it is the only arrow source on cars with no windshield HUD. Sent every
        //     update like the OEM does; guarded, never throws.
        val clusterGuidanceDelivered = ClusterNavPusher.push(data)

        // 5. AMap broadcast — unconditional, the cluster compositor needs it every step.
        sendAmapBroadcast(ctx, data)
        val delivered = frameDelivered(canFrameDelivered, clusterGuidanceDelivered)
        if (delivered) lastUpdateMs = SystemClock.elapsedRealtime()
        return delivered
    }

    internal fun frameDelivered(allRequiredCanWritesSucceeded: Boolean,
                                clusterFrameDelivered: Boolean): Boolean =
            allRequiredCanWritesSucceeded || clusterFrameDelivered

    /**
     * Stop navigation and reset the cluster HUD to its default state.
     *
     * Clears all CAN registers (via [CanBusController.setNaviActive]), sends the AMap stop
     * broadcast, and resets all deduplication state.
     */
    @Synchronized
    fun closeNavigation(ctx: Context) {
        if (!isHudActive) return
        try {
            CanBusController.setNaviActive(false)
        } catch (e: ProxyClient.ProxyException) {
            Log.w(TAG, "setNaviActive(false) failed: " + e.message)
        }
        isHudActive = false
        naviActiveAcked = false
        stopWatchdog()
        ClusterNavPusher.stop()   // clear the cluster guidance too (best-effort)
        sendAmapStopBroadcast(ctx)
        resetState()
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private fun ensureHudActive() {
        // AUD-003 follow-up — the session opens HERE, not when the car says yes.
        //
        // isHudActive used to be assigned inside the try, after CanBusController.setNaviActive(true).
        // On a car that refuses that register the assignment never ran, and the consequences
        // compounded: armWatchdog() never ran either, closeNavigation() short-circuited forever on
        // `if (!isHudActive) return`, and the guidance writes further down updateNavigation() are
        // outside that guard so they kept going. The result was an arrow on the windshield that
        // nothing in the app could clear — not the end of the route, not onNotificationRemoved,
        // not the staleness watchdog, because none of them could get past the flag. On top of that
        // ensureHudActive() re-entered on every single guidance frame, re-issuing two CAN batches
        // per frame at nav cadence.
        //
        // So the flag now means what its name says — a session is open and something will have to
        // close it — and CAN acceptance is tracked separately in naviActiveAcked.
        if (isHudActive) {
            retryActivationIfRefused()
            return
        }
        isHudActive = true
        armWatchdog()

        // Turn the windshield HUD ON (DL3 feature id SET_HUD_SWITCH=1) so nav shows even
        // if the user had the HUD switched off — matches the video-proven CAN→HUD bench.
        // Best-effort: a failure here must not block nav activation. DL3 gating is enforced
        // by the caller (isDiLink3Hud). We leave the HUD switch ON when nav ends (like the
        // bench) — closeNavigation only clears the nav registers, not the user's HUD switch.
        try {
            CanBusController.setSettingFeature(CanWriteVerbs.SET_HUD_SWITCH, CanWriteVerbs.HUD_SWITCH_ON)
        } catch (e: ProxyClient.ProxyException) {
            Log.w(TAG, "SET_HUD_SWITCH on failed: " + e.message)
        }
        attemptCanActivation()
    }

    /**
     * The CAN half of activation, and the only place naviActiveAcked is set.
     *
     * ClusterNavPusher.enable() is the second output path — it switches the OEM container into
     * nav mode so the instrument CLUSTER accepts our NaviInfo frames, which is where cars without
     * a windshield HUD get their arrows. It only runs once the CAN register was accepted, exactly
     * as before; what changed is that its failure no longer takes the session flag down with it.
     */
    private fun attemptCanActivation() {
        lastActivationAttemptMs = SystemClock.elapsedRealtime()
        try {
            CanBusController.setNaviActive(true)
            naviActiveAcked = true
            ClusterNavPusher.enable()
        } catch (e: ProxyClient.ProxyException) {
            naviActiveAcked = false
            Log.w(TAG, "setNaviActive(true) failed: " + e.message)
        }
    }

    /**
     * Re-attempt activation on a car that refused it, at most once every [ACTIVATION_RETRY_MS].
     *
     * The old code retried on every guidance frame, which on a refusing car meant two CAN batches
     * per frame for the whole trip. Retrying still matters — a refusal can be transient, the
     * daemon may have been cold — but at nav cadence, not at frame cadence.
     */
    private fun retryActivationIfRefused() {
        if (!shouldRetryActivation(naviActiveAcked, SystemClock.elapsedRealtime(),
                        lastActivationAttemptMs, ACTIVATION_RETRY_MS)) {
            return
        }
        attemptCanActivation()
    }

    /**
     * The retry decision, pulled out as a pure function so it can be tested.
     *
     * Everything else on this path needs a uid-2000 daemon and a DiLink 3 car, which is why
     * `HudControllerLivenessTest` says what it says about the limits of testing here. This
     * predicate does not, and it is where the mistake would be made: an inverted comparison, or a
     * forgotten `acked` check, turns the fix back into two CAN batches per guidance frame.
     *
     * @param acked      whether the car has already accepted SETTING_NAVI_SCREEN_STATUS
     * @param nowMs      current [SystemClock.elapsedRealtime]
     * @param lastTryMs  when the last activation attempt ran, 0 if never
     * @param cadenceMs  minimum interval between attempts
     */
    internal fun shouldRetryActivation(acked: Boolean, nowMs: Long,
                                       lastTryMs: Long, cadenceMs: Long): Boolean {
        if (acked) return false
        // Explicit, not arithmetic: nowMs - Long.MIN_VALUE overflows.
        if (lastTryMs == NEVER_ATTEMPTED) return true
        return nowMs - lastTryMs >= cadenceMs
    }

    // ─── Staleness watchdog impl ──────────────────────────────────────────

    /** Start the periodic staleness check once nav is active (idempotent). */
    private fun armWatchdog() {
        if (watchdogTask != null) return
        var exec = watchdog
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor { r ->
                val t = Thread(r, "hud-nav-watchdog")
                t.isDaemon = true
                t
            }
            watchdog = exec
        }
        watchdogTask = exec.scheduleWithFixedDelay(
                { closeIfStale() }, STALE_MS, STALE_MS / 2, TimeUnit.MILLISECONDS)
    }

    /** Cancel the staleness check AND shut down its scheduler thread (nav stopped). armWatchdog
     *  recreates the executor on the next nav session, so this owns the thread's lifetime — it no
     *  longer leaks a live daemon thread for the whole process on this static singleton, nor keeps
     *  firing after the listener service is torn down (nav-end / stale-close both route here). */
    private fun stopWatchdog() {
        val t = watchdogTask
        if (t != null) { t.cancel(false); watchdogTask = null }
        val exec = watchdog
        if (exec != null) { exec.shutdown(); watchdog = null }
    }

    /**
     * Records that guidance is still live, without touching a single register — AUD-003.
     *
     * The watchdog's premise was that "no update in STALE_MS ms" means the arrow is frozen. That
     * is true when the nav app has stopped, and false in the one case that matters on a motorway:
     * Maps and Waze re-post the SAME notification while the distance to the next manoeuvre has not
     * changed, and the listener's content deduplication swallows those re-posts before
     * [updateNavigation] is ever reached. At 130 km/h a "40 km" frame stays identical for roughly
     * 28 seconds, so the watchdog fired at 12 and cleared a HUD that was perfectly correct. The
     * arrow blinked out mid-motorway and came back when the kilometre finally ticked.
     *
     * So liveness stops depending on content. The listener calls this when a nav app re-posts
     * exactly the frame that is already on the HUD — which means the displayed arrow is still the
     * right one, which is precisely what the watchdog wanted to know.
     *
     * Deliberately NOT synchronized and deliberately doing nothing else. It is called from the
     * notification dispatch thread on every re-post; taking the same lock as updateNavigation and
     * closeNavigation would put that thread behind a CAN write. Writing a volatile long is atomic,
     * and the worst interleaving with the watchdog is one tick's delay.
     *
     * Ignored when the HUD is not active: a re-post cannot keep alive something that was never lit.
     */
    fun noteNavFrameSeen() {
        if (!isHudActive) return
        lastUpdateMs = SystemClock.elapsedRealtime()
    }

    /**
     * Watchdog tick: if the HUD is active but no nav update has arrived for [STALE_MS], the arrow
     * is frozen — clear the HUD so a stale/wrong arrow does not persist. Synchronized on `this`
     * (like update/close) so it never races the notification-writer thread.
     */
    @Synchronized
    private fun closeIfStale() {
        if (!isHudActive) return
        if (SystemClock.elapsedRealtime() - lastUpdateMs < STALE_MS) return
        val ctx = appContext ?: return
        Log.i(TAG, "nav stale >" + STALE_MS + "ms with no update — clearing frozen HUD")
        // Fail fast: this runs on the dedicated hud-nav-watchdog thread and holds `this`
        // (updateNavigation is also synchronized on this). A ~23s blocking daemon bootstrap
        // inside closeNavigation's CAN write would stall guidance frames — opt this thread out
        // of the blocking reconnect (mirrors F6). The watchdog thread does nothing else, so no
        // reset is needed.
        ProxyClient.setNonBlockingReconnect(true)
        closeNavigation(ctx)   // reentrant (same thread holds `this`); also stops the watchdog
    }

    /**
     * Whether this head unit is a DiLink 3 (and not Android Automotive). Cached — the platform is
     * fixed at boot. Fails safe to `false` (drive nothing) on any error so an unknown platform is
     * never hit with DL3-specific CAN writes.
     */
    private fun isDiLink3Hud(ctx: Context): Boolean {
        isDl3Hud?.let { return it }
        val dl3 = try {
            Platform.get().isDiLink3(ctx) && !AaosClusterProbe.isAaos(ctx)
        } catch (t: Throwable) {
            false
        }
        isDl3Hud = dl3
        return dl3
    }

    private fun resetState() {
        lastRoadName = ""
        lastIconId = -1
        lastDistance = -1
        lastSecondaryIconId = -1
        lastSecondaryDistance = -1
        lastRestHour = -1
        lastRestMinute = -1
        lastRestMileage = -1L
        lastEtaHour = -1
        lastEtaMinute = -1
    }

    // ─── AMap broadcast ───────────────────────────────────────────────────

    /**
     * Sends `AUTONAVI_STANDARD_BROADCAST_SEND` (TYPE=8) matching the OpenBYD 2.2
     * `sendStandardAmapBroadcast` implementation. This intent is received by the BYD cluster
     * compositor to update its own navigation overlay independently of the raw CAN writes above.
     */
    private fun sendAmapBroadcast(ctx: Context, d: HudNavigationData) {
        try {
            val amapIcon = mapToAmapIcon(d.iconId)
            val intent = newAmapIntent()
            intent.putExtra("KEY_TYPE", 10001)
            intent.putExtra("TYPE", 8)
            intent.putExtra("EXTRA_STATE", 8)
            intent.putExtra("EXTRA_IS_FOREGROUND", 0)
            intent.putExtra("IS_BYD_MAP", true)
            intent.putExtra("IS_BYD_BAIDU_MAP", false)
            intent.putExtra("NEW_ICON", amapIcon)
            intent.putExtra("SEG_REMAIN_DIS", d.distanceMeters)
            intent.putExtra("NEXT_ROAD_NAME", d.roadName)
            intent.putExtra("ROUTE_REMAIN_DIS", d.remainingDistanceMeters ?: -1)
            intent.putExtra("ROUTE_REMAIN_TIME", d.remainingTimeSeconds ?: -1)
            // NEXT_NEXT / secondary extras: only added when there is actual data (matching
            // OpenBYD sendStandardAmapBroadcast — the BYD compositor must not receive these
            // keys with value -1, only their absence signals "no secondary info").
            // Google Maps notifications never carry secondary guidance, so we never add them.
            // Human-readable distance strings.
            intent.putExtra("SEG_REMAIN_DIS_AUTO", formatMeters(d.distanceMeters))
            val remainingDistance = d.remainingDistanceMeters
            if (remainingDistance != null) {
                intent.putExtra("ROUTE_REMAIN_DIS_AUTO", formatMeters(remainingDistance))
            }
            val remainingTime = d.remainingTimeSeconds
            if (remainingTime != null) {
                intent.putExtra("ROUTE_REMAIN_TIME_AUTO", formatSeconds(remainingTime))
                intent.putExtra("ROUTE_REMAIN_TIME_STRING", formatSeconds(remainingTime))
            }
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "AMap broadcast failed", e)
        }
    }

    /** Sends `AUTONAVI_STANDARD_BROADCAST_SEND` (TYPE=9) to stop navigation. */
    private fun sendAmapStopBroadcast(ctx: Context) {
        try {
            val intent = newAmapIntent()
            intent.putExtra("KEY_TYPE", 10001)
            intent.putExtra("TYPE", 9)
            intent.putExtra("EXTRA_STATE", 1)
            intent.putExtra("EXTRA_IS_FOREGROUND", 1)
            intent.putExtra("IS_BYD_MAP", true)
            intent.putExtra("IS_BYD_BAIDU_MAP", false)
            intent.putExtra("NEW_ICON", -1)
            intent.putExtra("SEG_REMAIN_DIS", -1)
            intent.putExtra("NEXT_ROAD_NAME", "")
            intent.putExtra("ROUTE_REMAIN_DIS", -1)
            intent.putExtra("ROUTE_REMAIN_TIME", -1)
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "AMap stop broadcast failed", e)
        }
    }

    /** Package verified from the OEM AmapService APK's dynamic receiver registration. */
    internal fun newAmapIntent(): Intent =
            Intent(AMAP_BROADCAST_ACTION).setPackage(AMAP_RECEIVER_PACKAGE)

    /**
     * Maps a BYD turn icon ID to the AMap `NEW_ICON` value used by the broadcast.
     *
     * This used to send `2` for every maneuver and `12` for the destination — but an on-car icon
     * sweep (29 photos, ids 0..28) decoded the real namespace: **2 is turn-LEFT** and **12 is a
     * roundabout**. So every straight/right maneuver was drawing a LEFT arrow and the arrival was
     * drawing a roundabout. Now shares the single verified table in [ClusterNavPusher.toAmapIcon]
     * with the cluster path.
     */
    private fun mapToAmapIcon(bydIconId: Int): Int = ClusterNavPusher.toAmapIcon(bydIconId)

    private fun formatMeters(meters: Int): String {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.1f km", meters / 1000.0f)
        }
        return meters.toString() + " m"
    }

    private fun formatSeconds(totalSeconds: Int): String {
        val totalMinutes = totalSeconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) (h.toString() + "h " + m + "m") else (m.toString() + " min")
    }
}

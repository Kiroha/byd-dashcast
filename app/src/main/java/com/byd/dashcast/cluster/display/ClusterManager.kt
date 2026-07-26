package com.byd.dashcast.cluster.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display

import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.util.AppLogger

import java.util.Locale

/**
 * ClusterManager — direct control of the BYD Seal cluster via the Binder service "AutoContainer".
 *
 * ARCHITECTURE (DiLink 3.0 / XDJA) :
 *   • The "AutoContainer" service (android.os.IAutoContainer) is registered in ServiceManager.
 *   • AutoContainerManager (getSystemService("auto_container")) checks the whitelist
 *     /system/etc/container_comm_cfg.json → only "com.xdja.clusterdemo" is allowed.
 *   • BUT: the direct Binder call bypasses this Java check (confirmed TEST 8 — returned 00000000).
 *   • Low level the Binder is reached via ServiceManager.getService("AutoContainer"). This class
 *     does NOT touch it directly: every sendInfo() below goes through AdbLocalClient.sendInfo,
 *     which uses the proxy daemon's typed transact first and the ADB shell `service call` relay
 *     as a fallback (both uid=2000).
 *
 * AIDL IAutoContainer (transactions) :
 *   #1 sendJson(int type, String json)
 *   #2 sendInfo(int type, int infoInt, String infoStr)  ← used here
 *   #3 sendInfo2(int type, byte[] data)
 *   #4 registerCallback(IAutoContainerCallback cb)
 *
 * CLUSTER COMMANDS (type=1000) — CONFIRMED IN CAR (13/04/2026 + 16/04/2026, BYD Seal EU) :
 *
 *   infoInt=30  → SWITCH TO Seal EU MODE (correct resolution): ONLY safe on the SLOW path (no VD).
 *                 Sending when a VD already exists recreates it, corrupting the ATM display registry.
 *   infoInt=16  → ENABLE fullscreen projection: the correct command to launch an app on display 1.
 *   infoInt=18  → CLOSE the projection: the correct restore command (cmd=0 alone is NOT enough).
 *   infoInt= 0  → refresh the Qt video stream (must follow sendInfo(18)).
 *   infoInt= 1  → disconnects Qt ENTIRELY → display 1 DISAPPEARS → DO NOT USE to launch apps.
 *   infoInt=12/13 → show/hide Adas — NO EFFECT on the 2D cluster Seal EU.
 */
class ClusterManager(context: Context) {

    /** Notified when the cluster VirtualDisplay becomes available (or on timeout). */
    interface DisplayReadyCallback {
        fun onDisplayReady(display: Display?, displayId: Int)
        fun onDisplayTimeout()

        /**
         * Called if the VirtualDisplay appears after the initial timeout fires.
         * Implementors must guard against stop() having been called.
         */
        fun onDisplayLateReady(display: Display?, displayId: Int) {}
    }

    private val mContext: Context = context.applicationContext
    private val mHandler = Handler(Looper.getMainLooper())

    // Reference to the active DisplayListener during activateClusterDisplay(), so that cancel()
    // can unregister it even if no display ever appeared.
    private var mActiveDisplayListener: DisplayManager.DisplayListener? = null
    private var mActiveDisplayManager: DisplayManager? = null

    // Late-arrival listener — registered after the 12s timeout to catch slow VD creation.
    // Kept separate from mActiveDisplayListener so cancel() clears both independently.
    private var mLateArrivalListener: DisplayManager.DisplayListener? = null
    private var mLateArrivalManager: DisplayManager? = null

    // ── Activation + waiting for VirtualDisplay ───────────────────────────────

    /**
     * Full activation sequence (CONFIRMED by logcat 03/05/2026, BYD Seal EU DiLink 3.0).
     * The VirtualDisplay does NOT exist at boot; it is created by the sequence below.
     *   True fast path (VD present AND sQtInProjectionMode): instant onDisplayReady.
     *   Warm path (VD present, native mode): sendInfo(30)→3s→sendInfo(16)→onDisplayReady.
     *   Slow path (no VD): sendInfo(30)→3s→sendInfo(16)→3s→sendInfo(35) → VD appears ~280ms later.
     *   Timeout: 12s. The callback is called on the main thread.
     */
    fun activateClusterDisplay(callback: DisplayReadyCallback) {
        // 1.2.29 — defensive cancel() at entry: on retry, re-entering registered a new listener
        // without unregistering the old, leaking listeners and double-launching on the cluster.
        cancel()

        val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // DiLink 5.0 short-circuit: PRESENTATION displays #3/#4 exist persistently. The full DL3
        // sequence (30→16→35) is replaced by a single sendInfo(16) on auto_container.
        if (isDiLink5Safe()) {
            AppLogger.i(TAG, "DL5 activation path: sendInfo(16) only on ${AdbLocalClient.autoContainerSvcName(mContext)}")
            // Some DL5 variants (DiLink50F_LC / 5.1, "1for2") register the service as
            // "AutoContainer" (PascalCase), not "auto_container" — proven by the D50F_LC bugreport
            // (20260702): service list had "AutoContainer" while `service call auto_container` returned
            // "does not exist". autoContainerSvcName() now probes the registered name; this callback
            // additionally self-corrects if the shell still reports the tried name absent (probe blocked).
            val svcRetried = java.util.concurrent.atomic.AtomicBoolean(false)
            val cb = object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i(TAG, "DL5 activation ADB(cmd=16): $out")
                    if (out != null && out.contains("does not exist") && svcRetried.compareAndSet(false, true)) {
                        val tried = AdbLocalClient.autoContainerSvcName(mContext)
                        AdbLocalClient.noteAutoContainerMissing(tried)
                        AppLogger.i(TAG, "DL5 activation: '$tried' absent → retry with " +
                                "'${AdbLocalClient.autoContainerSvcName(mContext)}'")
                        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "", this)
                        return
                    }
                    mHandler.postDelayed({ resolveDl5Display(dm, callback) }, 500)
                }

                override fun onError(err: String?) {
                    AppLogger.e(TAG, "DL5 activation ADB(cmd=16) ERROR: $err")
                    // Still attempt to resolve a display — they may be already up.
                    mHandler.postDelayed({ resolveDl5Display(dm, callback) }, 500)
                }
            }
            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "", cb)
            return
        }

        // DL3 single-OS fission: the cluster is rendered natively (Qt) and NO projectable
        // Android VirtualDisplay is ever created (AutoContainer has no native backend →
        // "no AutoContainerNative"). Running the full 30→16→35 sequence just times out after
        // 20s, every time, and the tester sees nothing with no explanation
        // (INC-20260715-140107 / -140551). Once single-OS is known — via the authoritative shell
        // getprop ro.build.system.fission_single_os, cached+persisted by ClusterService.onCreate —
        // skip straight to the timeout callback so ClusterService informs the user and stops
        // looping. STRICT: DL3 only — a 1-for-2 DL3 (prop=0, never flagged) and any DL5
        // (isDiLink3=false, and handled above) never reach here.
        if (Platform.get().isDiLink3(mContext) && Platform.isClusterSingleOs()) {
            AppLogger.w(TAG, "DL3 single-OS fission — no projectable cluster display; skipping activation")
            mHandler.post { callback.onDisplayTimeout() }
            return
        }

        // 1. First check if the cluster VirtualDisplay is already present.
        val found = findClusterDisplay(dm)
        if (found != null && sQtInProjectionMode) {
            // True fast path: VD up AND Qt already projecting — just hand the display back.
            AppLogger.i(TAG, "VD already present AND Qt still projecting — instant reconnect (id=${found.displayId})")
            mHandler.post { callback.onDisplayReady(found, found.displayId) }
            return
        }
        if (found != null) {
            val adasFix = ClusterPrefs.isAdasWindowFixEnabled(mContext)
            if (!adasFix) {
                // Default warm path: VD present, Qt in native mode → sendInfo(16) only.
                AppLogger.i(TAG, "VD present id=${found.displayId} but Qt in native mode — warm path (16 only)")
                sendWarmCmd16(found, callback)
                return
            }

            // ADAS fix ON + warm path: cmd 30 makes AutoDisplayService recreate the VD with a NEW
            // display ID. Arm a DisplayListener BEFORE cmd 30, catch onDisplayAdded(newId), wait 1s
            // for the ATM, then send cmd 16 on the correct new Display. Fallback to cmd 16 on the
            // original display after 4s if cmd 30 triggers no VD change.
            AppLogger.i(TAG, "VD present id=${found.displayId} — warm path ADAS (30 → new VD → 16)")

            val originalDisplay = found
            val adasRemapTimeoutMs = 4000L
            val atmStabilizeMs = 1000L

            val adasListenerHolder = arrayOfNulls<DisplayManager.DisplayListener>(1)

            val fallback = Runnable {
                dm.unregisterDisplayListener(adasListenerHolder[0])
                mActiveDisplayListener = null
                mActiveDisplayManager = null
                AppLogger.w(TAG, "ADAS warm path: no VD change after cmd 30 ($adasRemapTimeoutMs ms) — fallback cmd 16 on original id=${originalDisplay.displayId}")
                sendWarmCmd16(originalDisplay, callback)
            }

            adasListenerHolder[0] = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    val d = dm.getDisplay(displayId)
                    if (!isClusterDisplay(d)) return
                    // New cluster VD appeared after cmd 30 — this is the correct display.
                    mHandler.removeCallbacks(fallback)
                    dm.unregisterDisplayListener(adasListenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "ADAS warm path: new VD id=$displayId — stabilizing $atmStabilizeMs ms for ATM then cmd 16")
                    mHandler.postDelayed({ sendWarmCmd16(d, callback) }, atmStabilizeMs)
                }

                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {}
            }
            mActiveDisplayManager = dm
            mActiveDisplayListener = adasListenerHolder[0]
            dm.registerDisplayListener(adasListenerHolder[0], mHandler)
            mHandler.postDelayed(fallback, adasRemapTimeoutMs)

            // Arm listener, then fire cmd 30 — onDisplayAdded(newId) will follow.
            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        AppLogger.i(TAG, "ADAS warm path ADB(cmd=30): $out")
                    }

                    override fun onError(err: String?) {
                        AppLogger.e(TAG, "ADAS warm path ADB(cmd=30) ERROR: $err")
                        mHandler.removeCallbacks(fallback)
                        dm.unregisterDisplayListener(adasListenerHolder[0])
                        mActiveDisplayListener = null
                        mActiveDisplayManager = null
                        sendWarmCmd16(originalDisplay, callback)
                    }
                })
            return
        }

        // Display not found — send full sequence 30→3s→16→3s→35 to create the VirtualDisplay.
        AppLogger.w(TAG, "VirtualDisplay not found — sending full sequence (30→3s→16→3s→35) + polling")

        val timeoutMs = CLUSTER_DISPLAY_TIMEOUT_MS

        // Do not start AppStartManagement in foreground: it briefly opens a visible BYD app.
        AppLogger.i(TAG, "Starting activation sequence without foreground AppStartManagement launch")
        mHandler.postDelayed({ sendActivationSequence() }, 2000)

        // Listen for display additions + timeout.
        val pollCount = longArrayOf(0L)
        val listenerHolder = arrayOfNulls<DisplayManager.DisplayListener>(1)

        listenerHolder[0] = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                val d = dm.getDisplay(displayId)
                AppLogger.i(TAG, "onDisplayAdded id=$displayId display=$d")
                if (isClusterDisplay(d)) {
                    mHandler.removeCallbacksAndMessages(null)
                    dm.unregisterDisplayListener(listenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "VirtualDisplay cluster detected: id=$displayId")
                    notifyProjectionActive()
                    callback.onDisplayReady(d, displayId)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {}
        }
        mActiveDisplayManager = dm
        mActiveDisplayListener = listenerHolder[0]
        dm.registerDisplayListener(listenerHolder[0], mHandler)

        // Additional polling: onDisplayAdded is sometimes not triggered for cross-process VDs.
        scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, 0)

        // Global timeout for VirtualDisplay creation.
        mHandler.postDelayed({
            dm.unregisterDisplayListener(listenerHolder[0])
            mActiveDisplayListener = null
            mActiveDisplayManager = null
            mHandler.removeCallbacksAndMessages(null)
            AppLogger.w(TAG, "Timeout: cluster VirtualDisplay not detected after $timeoutMs ms")
            callback.onDisplayTimeout()
            // Keep watching quietly for slow firmware variants (e.g. DiLink 4.0).
            armLateArrivalWatch(dm, callback)
        }, timeoutMs)
    }

    private fun scheduleDisplayPoll(
        dm: DisplayManager,
        listenerHolder: Array<DisplayManager.DisplayListener?>,
        callback: DisplayReadyCallback,
        pollCount: LongArray,
        delayMs: Long
    ) {
        // Single Runnable per activation; reschedules itself via postDelayed(this, …).
        val pollRunnable = object : Runnable {
            override fun run() {
                pollCount[0]++
                if (pollCount[0] * POLL_INTERVAL_MS >= CLUSTER_DISPLAY_TIMEOUT_MS) return

                val found = findClusterDisplay(dm)
                if (found != null) {
                    mHandler.removeCallbacksAndMessages(null)
                    dm.unregisterDisplayListener(listenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "VirtualDisplay found by polling: id=${found.displayId}")
                    notifyProjectionActive()
                    callback.onDisplayReady(found, found.displayId)
                } else {
                    mHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        mHandler.postDelayed(pollRunnable, if (delayMs == 0L) POLL_INTERVAL_MS else delayMs)
    }

    // ── Late arrival watcher ──────────────────────────────────────────────────

    /**
     * Arms a background DisplayListener + polling loop after the initial timeout fires.
     * If the cluster VirtualDisplay eventually appears within LATE_ARRIVAL_GRACE_MS,
     * [DisplayReadyCallback.onDisplayLateReady] is called exactly once. Cleared by [cancel].
     */
    private fun armLateArrivalWatch(dm: DisplayManager, callback: DisplayReadyCallback) {
        // One-shot: prevents both the DisplayListener and the polling loop from firing.
        val consumed = booleanArrayOf(false)

        val expiry = Runnable {
            if (!consumed[0]) {
                consumed[0] = true
                val mgr = mLateArrivalManager
                val lst = mLateArrivalListener
                if (mgr != null && lst != null) {
                    mgr.unregisterDisplayListener(lst)
                    mLateArrivalListener = null
                    mLateArrivalManager = null
                }
                AppLogger.d(TAG, "Late arrival grace period expired — no VD appeared")
            }
        }

        val holder = arrayOfNulls<DisplayManager.DisplayListener>(1)
        holder[0] = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (consumed[0]) return
                val d = dm.getDisplay(displayId)
                if (!isClusterDisplay(d)) return
                consumed[0] = true
                mHandler.removeCallbacks(expiry)
                dm.unregisterDisplayListener(holder[0])
                mLateArrivalListener = null
                mLateArrivalManager = null
                AppLogger.i(TAG, "Late arrival: cluster VD appeared — id=$displayId")
                notifyProjectionActive()
                callback.onDisplayLateReady(d, displayId)
            }

            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {}
        }
        mLateArrivalManager = dm
        mLateArrivalListener = holder[0]
        dm.registerDisplayListener(holder[0], mHandler)

        // Actually schedule the expiry: without this the DisplayListener registered above
        // was never unregistered when the grace period lapsed with no VD (the poll loop just
        // stopped rescheduling), leaking a DisplayListener until the next cancel() — and a VD
        // that appeared minutes later still fired a stale onDisplayLateReady. expiry sets
        // consumed and unregisters; onDisplayAdded / the poll cancel it if a VD arrives first.
        mHandler.postDelayed(expiry, LATE_ARRIVAL_GRACE_MS)

        // Polling fallback: onDisplayAdded is not always fired for cross-process VDs.
        val deadline = SystemClock.uptimeMillis() + LATE_ARRIVAL_GRACE_MS
        mHandler.postDelayed(object : Runnable {
            override fun run() {
                if (consumed[0]) return
                val found = findClusterDisplay(dm)
                if (found != null) {
                    consumed[0] = true
                    mHandler.removeCallbacks(expiry)
                    val mgr = mLateArrivalManager
                    val lst = mLateArrivalListener
                    if (mgr != null && lst != null) {
                        mgr.unregisterDisplayListener(lst)
                        mLateArrivalListener = null
                        mLateArrivalManager = null
                    }
                    AppLogger.i(TAG, "Late arrival (poll): cluster VD id=${found.displayId}")
                    notifyProjectionActive()
                    callback.onDisplayLateReady(found, found.displayId)
                } else if (SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, 2000)
                }
            }
        }, 2000)

        AppLogger.i(TAG, "Late arrival watch armed (${LATE_ARRIVAL_GRACE_MS / 1000}s grace)")
    }

    // ── Warm path helper ──────────────────────────────────────────────────────

    /** Sends sendInfo(16) and notifies the callback when done (or on error). */
    private fun sendWarmCmd16(display: Display, callback: DisplayReadyCallback) {
        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i(TAG, "warm path ADB(cmd=16): $out")
                    notifyProjectionActive()
                    mHandler.post { callback.onDisplayReady(display, display.displayId) }
                }

                override fun onError(err: String?) {
                    AppLogger.e(TAG, "warm path ADB(cmd=16) ERROR: $err")
                    // Report display anyway — caller decides what to do.
                    mHandler.post { callback.onDisplayReady(display, display.displayId) }
                }
            })
    }

    // ── Activation sequence sendInfo(30 → 16 → 35) ─────────────────────────────

    /**
     * Full activation sequence to create the VirtualDisplay (slow path).
     * ADAS fix OFF (default): sendInfo(16) → 3s → sendInfo(35).
     * ADAS fix ON: sendInfo(30) → 3s → sendInfo(16) → 3s → sendInfo(35).
     * The DisplayReadyCallback is NOT called here: the DisplayListener / polling handles it.
     */
    private fun sendActivationSequence() {
        val adasFix = ClusterPrefs.isAdasWindowFixEnabled(mContext)
        AppLogger.i(TAG, "sendActivationSequence — ADAS fix " + (if (adasFix) "ON (30→3s→16→3s→35)" else "OFF (16→3s→35)"))

        if (adasFix) {
            // Original sequence: sendInfo(30) → 3s → sendInfo(16) → 3s → sendInfo(35).
            AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_SCREEN_SIZE_SEAL_EU, "",
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        AppLogger.i(TAG, "activation ADB(cmd=30): $out")
                        mHandler.postDelayed({ sendActivationCmd16ThenCmd35() }, 3000)
                    }

                    override fun onError(err: String?) {
                        AppLogger.e(TAG, "activation ADB(cmd=30) ERROR: $err")
                        // cmd=30 failed — still attempt 16 → 35.
                        sendActivationCmd16ThenCmd35()
                    }
                })
        } else {
            // Default: sendInfo(16) → 3s → sendInfo(35), no screen-size change.
            sendActivationCmd16ThenCmd35()
        }
    }

    /** Sends sendInfo(16) → 3s delay → sendInfo(35) (VirtualDisplay creation trigger). */
    private fun sendActivationCmd16ThenCmd35() {
        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_PROJECTION_ON, "",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    AppLogger.i(TAG, "activation ADB(cmd=16): $out")
                    mHandler.postDelayed({ sendActivationCmd35() }, 3000)
                }

                override fun onError(err: String?) {
                    AppLogger.e(TAG, "activation ADB(cmd=16) ERROR: $err")
                    // Still attempt sendInfo(35) even if cmd=16 failed.
                    mHandler.postDelayed({ sendActivationCmd35() }, 3000)
                }
            })
    }

    /** Sends sendInfo(35) — triggers Qt JNI → AutoDisplayService.createVirtualDisplay(). */
    private fun sendActivationCmd35() {
        AdbLocalClient.sendInfo(mContext, CLUSTER_TYPE, CMD_DI40_MODE, "",
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) { AppLogger.i(TAG, "activation ADB(cmd=35): $out") }
                override fun onError(err: String?) { AppLogger.e(TAG, "activation ADB(cmd=35) ERROR: $err") }
            })
    }

    // ── Cluster display detection ─────────────────────────────────────────

    private fun findClusterDisplay(dm: DisplayManager): Display? {
        // Strategy 1: PRESENTATION category — prefer displays with a known cluster name.
        val presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        var fallback: Display? = null
        if (presentations != null) {
            for (d in presentations) {
                if (d.displayId == 0) continue
                AppLogger.d(TAG, "PRESENTATION candidate: id=${d.displayId} name=${d.name}")
                if (isKnownClusterName(d.name)) return d
                if (fallback == null) fallback = d // unnamed non-zero: keep as fallback
            }
        }
        if (fallback != null) return fallback

        // Strategy 2: any non-default display — prefer named, fall back to first.
        val all = dm.displays
        if (all != null) {
            var anyNonDefault: Display? = null
            for (d in all) {
                if (d.displayId == 0) continue
                AppLogger.d(TAG, "Non-default candidate: id=${d.displayId} name=${d.name}")
                if (isKnownClusterName(d.name)) return d
                if (anyNonDefault == null) anyNonDefault = d
            }
            if (anyNonDefault != null) return anyNonDefault
        }
        return null
    }

    private fun isClusterDisplay(d: Display?): Boolean {
        // Cluster if it is not the primary display (id=0). Prefer known names, but accept any
        // non-zero id as a fallback to preserve behaviour on unknown hardware variants.
        return d != null && d.displayId != 0
    }

    // ── DiLink 5.0 helpers ─────────────────────────────────────────────────

    private fun isDiLink5Safe(): Boolean {
        return try {
            Platform.get().isDiLink5(mContext)
        } catch (ignore: Throwable) {
            false
        }
    }

    /**
     * DL5: pick the first PRESENTATION display (typically id=3) and notify. Falls back to any
     * non-default display, then to a short polling window if nothing is up yet.
     */
    private fun resolveDl5Display(dm: DisplayManager, callback: DisplayReadyCallback) {
        val d = findClusterDisplay(dm)
        if (d != null) {
            AppLogger.i(TAG, "DL5 cluster display ready: id=${d.displayId} name=${d.name}")
            notifyProjectionActive()
            mHandler.post { callback.onDisplayReady(d, d.displayId) }
            return
        }
        // Brief polling window (up to 3 s) — should never trigger on DL5 in practice.
        val deadline = SystemClock.uptimeMillis() + 3000
        mHandler.postDelayed(object : Runnable {
            override fun run() {
                val dd = findClusterDisplay(dm)
                if (dd != null) {
                    AppLogger.i(TAG, "DL5 cluster display (late) id=${dd.displayId}")
                    notifyProjectionActive()
                    callback.onDisplayReady(dd, dd.displayId)
                } else if (SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, 250)
                } else {
                    AppLogger.w(TAG, "DL5 cluster display not found after 3s — timeout")
                    callback.onDisplayTimeout()
                }
            }
        }, 250)
    }

    // ── Cancellation ──────────────────────────────────────────────────────────

    /**
     * Cancels all in-progress operations: Handler polls, timeout, and DisplayListener.
     * MUST be called by DashboardDisplayHelper.stop().
     */
    fun cancel() {
        mHandler.removeCallbacksAndMessages(null)
        val activeMgr = mActiveDisplayManager
        val activeLst = mActiveDisplayListener
        if (activeMgr != null && activeLst != null) {
            activeMgr.unregisterDisplayListener(activeLst)
            mActiveDisplayListener = null
            mActiveDisplayManager = null
        }
        val lateMgr = mLateArrivalManager
        val lateLst = mLateArrivalListener
        if (lateMgr != null && lateLst != null) {
            lateMgr.unregisterDisplayListener(lateLst)
            mLateArrivalListener = null
            mLateArrivalManager = null
        }
        AppLogger.d(TAG, "cancel() — Handler and DisplayListener cancelled")
    }

    companion object {
        private const val TAG = "ClusterManager"

        // Exact name in ServiceManager (case-sensitive, confirmed by `service list`).
        const val SERVICE_NAME = "AutoContainer"

        // Parameters sendInfo(type, infoInt, infoStr).
        const val CLUSTER_TYPE = 1000
        const val CMD_PROJECTION_ON = 16 // ENABLE projection (CONFIRMED 13/04/2026)
        const val CMD_STOP_PROJECTION = 18 // CLOSE the projection (CONFIRMED 13/04/2026)
        const val CMD_RESTORE_NATIVE = 0 // refresh Qt stream (after cmd 18)
        // CMD=1 : disconnects Qt completely — NEVER USE (destroys display 1).
        const val CMD_SCREEN_SIZE_SEAL_EU = 30 // BYD Seal EU (CONFIRMED 16/04/2026)
        const val CMD_DI40_MODE = 35 // Di4.0 mode — triggers VirtualDisplay creation (CONFIRMED 03/05/2026)

        // Timeout waiting for the VirtualDisplay after the sendInfo activation sequence.
        // Sized for DL3/DL5 (~8.3s) but 12s was ~1-2s too short for DiLink 4.0: cmd 35 creates
        // the VD there ~8-9s AFTER it is sent, i.e. ~13.4s after activation start — just past the
        // old window, so BOTH activation attempts timed out even though the VD DID appear
        // (INC-20260713-180803: fission_bg_xdjaVirtualSurface id=1 visible ~T+13.4s). 20s catches
        // it. DL3/DL5 detect their VD in <1s (present / warm path) so never approach this ceiling.
        private const val CLUSTER_DISPLAY_TIMEOUT_MS = 20000L
        // Polling interval to detect the virtual display.
        private const val POLL_INTERVAL_MS = 500L
        // Grace period after the 12s timeout during which we keep watching for slow VD creation.
        private const val LATE_ARRIVAL_GRACE_MS = 60_000L

        // ────────────────────────────────────────────────────────────────────────
        // v1.2.78 — Qt projection state tracker. After sendInfo(18) the cluster VirtualDisplay
        // (id=1) PERSISTS but Qt switches back to native rendering, so "fast path" based on the
        // mere existence of the VD is wrong. We track our own best-effort view of Qt's state:
        //   true  → a sendInfo(30)→sendInfo(16) sequence succeeded and no stop has been requested;
        //           re-binding to the existing display is enough (no need to re-issue 30/16).
        //   false → unknown / Qt is in native mode (default at app start, or after any restore);
        //           we MUST replay 30 → 3s → 16 to put Qt back into projection.
        // Volatile because notifyProjection*() may be called from any thread (AdbLocalClient callbacks).
        @Volatile
        private var sQtInProjectionMode = false

        /** Hooked by AdbLocalClient at the entry of restoreBydOnCluster() / restoreOriginCluster(). */
        @JvmStatic
        fun notifyProjectionStopped() {
            sQtInProjectionMode = false
        }

        /**
         * Called after a successful activation sequence so the next activate() can take the true
         * fast path. Public so manual recovery actions (SysInfo replay button) can sync the flag.
         */
        @JvmStatic
        fun notifyProjectionActive() {
            sQtInProjectionMode = true
        }

        @JvmStatic
        fun isQtInProjectionMode(): Boolean = sQtInProjectionMode

        /**
         * Returns true if [name] matches a known BYD cluster VirtualDisplay pattern.
         * DL3: "fission_bg_xdjaVirtualSurface", DL5: "XDJAScreenProjection_0/1".
         */
        private fun isKnownClusterName(name: String?): Boolean {
            if (name == null) return false
            val lower = name.lowercase(Locale.ROOT)
            return lower.contains("xdja") || lower.contains("fission")
        }
    }
}

package com.byd.dashcast.cluster.display

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display

import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.platform.Platform
import com.byd.dashcast.util.AppLogger

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

    // Late-arrival listener — registered after the activation timeout to catch slow VD creation.
    // Kept separate from mActiveDisplayListener so cancel() clears both independently.
    private var mLateArrivalListener: DisplayManager.DisplayListener? = null
    private var mLateArrivalManager: DisplayManager? = null

    // ── DiLink 4.0 daemon-side resolution state ───────────────────────────────
    // All of this is inert on DL3/DL5: the only writer of these fields is the DL4-gated
    // daemon poll, and the only reader of the verdict is the DL4-gated notification.

    /**
     * Bumped by [cancel] so an in-flight daemon probe from a previous activation is discarded,
     * and by every path that has just delivered `onDisplayReady` — otherwise an enumerate still
     * in flight passes the generation guard and fires a SECOND `onDisplayReady` (duplicate
     * `wm overscan reset`, duplicate notification update, duplicate MainActivity callback).
     */
    @Volatile private var mActivationGeneration = 0
    @Volatile private var mReadyGeneration = -1
    private lateinit var mProjectionSession: ProjectionCommandSequencer.Session

    /**
     * True once cmd 16 — the command that actually switches Qt from native rendering into
     * projection — has been dispatched (its AdbLocalClient callback fired, success or not), or
     * once the DL4 watchdog below gave up waiting for it.
     *
     * The DL4 daemon poll must not resolve before this. The display PRE-EXISTS activation there,
     * so the very first probe succeeds while the activation chain is still sitting in the handler
     * queue; resolving then would declare the cluster connected and launch the user's app onto a
     * display Qt is still rendering natively — the half-broken "app vanished, cluster unchanged"
     * state. Gating on cmd 16 (not on the last command, cmd 35) is deliberate: cmd 35 only asks
     * for a VirtualDisplay to be CREATED, and on DL4 it provably already exists.
     */
    @Volatile private var mActivationCmd16Dispatched = false

    /** Guards against overlapping shell round-trips. Main thread only. */
    private var mDl4ProbeInFlight = false

    /** [SystemClock.uptimeMillis] at which the in-flight probe was started — see the stuck reset. */
    private var mDl4ProbeStartedAt = 0L

    /**
     * Bumped when a wedged probe is force-released, so the late callback of that probe cannot
     * clear [mDl4ProbeInFlight] out from under its replacement. Same shape as the keep-alive
     * probe guard in HotspotKeeper.
     */
    @Volatile private var mDl4ProbeSeq = 0

    /** True once the daemon returned a PARSEABLE display list (i.e. the shell path works). */
    private var mDl4DumpParsed = false

    /** True once that list contained a cluster display. */
    private var mDl4SawCluster = false

    /** One "display resolution — app=[…] daemon=[…]" line per activation, not per probe. */
    private var mDl4ResolutionLogged = false

    /** One "rejected N non-default display(s)" line per activation, not per probe. */
    private var mDl4RejectionLogged = false

    // ── Activation + waiting for VirtualDisplay ───────────────────────────────

    /**
     * Full activation sequence (CONFIRMED by logcat 03/05/2026, BYD Seal EU DiLink 3.0).
     * The VirtualDisplay does NOT exist at boot; it is created by the sequence below.
     *   True fast path (VD present AND sQtInProjectionMode): instant onDisplayReady.
     *   Warm path (VD present, native mode): sendInfo(30)→3s→sendInfo(16)→onDisplayReady.
     *   Slow path (no VD): sendInfo(30)→3s→sendInfo(16)→3s→sendInfo(35) → VD appears ~280ms later.
     *   Timeout: CLUSTER_DISPLAY_TIMEOUT_MS. The callback is called on the main thread.
     *
     * On DiLink 4.0 the slow path additionally resolves the display through the uid-2000 daemon,
     * because the app process is forbidden from enumerating it at all — see
     * [scheduleDaemonDisplayPoll].
     */
    fun activateClusterDisplay(callback: DisplayReadyCallback) {
        activateClusterDisplay(ProjectionCommandBus.beginSession(), callback)
    }

    internal fun activateClusterDisplay(
        session: ProjectionCommandSequencer.Session,
        callback: DisplayReadyCallback,
    ) {
        // 1.2.29 — defensive cancel() at entry: on retry, re-entering registered a new listener
        // without unregistering the old, leaking listeners and double-launching on the cluster.
        cancel()
        mProjectionSession = session
        val gen = mActivationGeneration
        // A fresh attempt invalidates any previous DL4 "unsupported firmware" verdict. Reset
        // here rather than in cancel(): the verdict must survive from the timeout that produced
        // it until the UI/notification has had a chance to read it.
        sDl4ProjectionUnavailable = false

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
                    if (gen != mActivationGeneration) return
                    AppLogger.i(TAG, "DL5 activation ADB(cmd=16): $out")
                    if (out != null && out.contains("does not exist") && svcRetried.compareAndSet(false, true)) {
                        val tried = AdbLocalClient.autoContainerSvcName(mContext)
                        AdbLocalClient.noteAutoContainerMissing(tried)
                        AppLogger.i(TAG, "DL5 activation: '$tried' absent → retry with " +
                                "'${AdbLocalClient.autoContainerSvcName(mContext)}'")
                        sendProjectionInfo(CMD_PROJECTION_ON, this)
                        return
                    }
                    mHandler.postDelayed({
                        if (gen == mActivationGeneration) resolveDl5Display(dm, callback, gen)
                    }, 500)
                }

                override fun onError(err: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.e(TAG, "DL5 activation ADB(cmd=16) ERROR: $err")
                    // Still attempt to resolve a display — they may be already up.
                    mHandler.postDelayed({
                        if (gen == mActivationGeneration) resolveDl5Display(dm, callback, gen)
                    }, 500)
                }
            }
            sendProjectionInfo(CMD_PROJECTION_ON, cb)
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
            mHandler.post {
                if (isCurrentActivation(gen)) callback.onDisplayTimeout()
            }
            return
        }

        // Stamp the cluster geometry configuration on EVERY activation. Without this a report only
        // reveals the configured type if the tester also stopped projection (originCluster() is the
        // sole reader of getClusterType), and the observed "Cluster dimensions" is the geometry
        // AFTER the ADAS command has already switched it — so a reader who takes it for the panel's
        // own size concludes the wrong cluster model entirely. That misread happened on
        // INC-20260810-180902, on a 10.25" car whose cluster reads 1920x720 precisely because the
        // ADAS fix had switched it to the 12.3" shape in an earlier session.
        AppLogger.i(TAG, "cluster config — type=${ClusterPrefs.getClusterType(mContext)}"
                + " (29=8.8\" 30=12.3\" 31=10.25\"), ADAS window fix "
                + (if (ClusterPrefs.isAdasWindowFixEnabled(mContext)) "ON" else "OFF"))

        // 1. First check if the cluster VirtualDisplay is already present.
        val found = findClusterDisplay(dm)

        // Latch the panel size HERE, before a single command goes out — this is the only moment
        // it is guaranteed not to have been changed by us. v1.8.27 latched it from
        // ClusterService.onDashboardDisplayConnected instead, which runs AFTER activation, i.e.
        // after the ADAS fix may already have forced the panel to 1920x720: on exactly the 8.8"
        // cars the latch protects, it read the switched value and never fired. Proven on
        // INC-20260720-073031, where the app logged "real 1920 x 720" during projection while the
        // system's own DisplayDeviceInfo for the same surface reads 1280x480 once restored.
        // The persisted latch was right; its reading point was not.
        if (found != null) latchPanelGeometry(found)

        if (found != null && sQtInProjectionMode) {
            // True fast path: VD up AND Qt already projecting — just hand the display back.
            AppLogger.i(TAG, "VD already present AND Qt still projecting — instant reconnect (id=${found.displayId})")
            mHandler.post {
                if (!claimDisplayReady(gen)) return@post
                if (isCurrentActivation(gen)) callback.onDisplayReady(found, found.displayId)
            }
            return
        }
        if (found != null) {
            val adasFix = adasFixEffective(found)
            if (!adasFix) {
                // Default warm path: VD present, Qt in native mode → sendInfo(16) only.
                AppLogger.i(TAG, "VD present id=${found.displayId} but Qt in native mode — warm path (16 only)")
                sendWarmCmd16(found, callback, gen)
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
                if (gen != mActivationGeneration) return@Runnable
                dm.unregisterDisplayListener(adasListenerHolder[0])
                mActiveDisplayListener = null
                mActiveDisplayManager = null
                AppLogger.w(TAG, "ADAS warm path: no VD change after cmd 30 ($adasRemapTimeoutMs ms) — fallback cmd 16 on original id=${originalDisplay.displayId}")
                sendWarmCmd16(originalDisplay, callback, gen)
            }

            adasListenerHolder[0] = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    if (gen != mActivationGeneration) return
                    val d = dm.getDisplay(displayId)
                    if (!isClusterDisplay(d)) return
                    // New cluster VD appeared after cmd 30 — this is the correct display.
                    mHandler.removeCallbacks(fallback)
                    dm.unregisterDisplayListener(adasListenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "ADAS warm path: new VD id=$displayId — stabilizing $atmStabilizeMs ms for ATM then cmd 16")
                    mHandler.postDelayed({
                        if (gen == mActivationGeneration) sendWarmCmd16(d, callback, gen)
                    }, atmStabilizeMs)
                }

                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {}
            }
            mActiveDisplayManager = dm
            mActiveDisplayListener = adasListenerHolder[0]
            dm.registerDisplayListener(adasListenerHolder[0], mHandler)
            mHandler.postDelayed(fallback, adasRemapTimeoutMs)

            // Arm listener, then fire cmd 30 — onDisplayAdded(newId) will follow.
            sendProjectionInfo(CMD_SCREEN_SIZE_SEAL_EU,
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        if (gen != mActivationGeneration) return
                        AppLogger.i(TAG, "ADAS warm path ADB(cmd=30): $out")
                    }

                    override fun onError(err: String?) {
                        if (gen != mActivationGeneration) return
                        AppLogger.e(TAG, "ADAS warm path ADB(cmd=30) ERROR: $err")
                        mHandler.removeCallbacks(fallback)
                        dm.unregisterDisplayListener(adasListenerHolder[0])
                        mActiveDisplayListener = null
                        mActiveDisplayManager = null
                        sendWarmCmd16(originalDisplay, callback, gen)
                    }
                })
            return
        }

        // Display not found — send the activation sequence to create/enable the VirtualDisplay.
        // The sequence actually sent depends on the ADAS-window-fix pref (see
        // sendActivationSequence), so read it HERE too. This line used to hard-code
        // "30→3s→16→3s→35" while the DEFAULT (fix OFF) sends only 16→3s→35, and both lines land
        // in the journal ~2.5 s apart flatly contradicting each other — every triage of these
        // reports started by mis-reading which commands had actually been sent.
        val seq = if (adasFixQuiet()) "30→3s→16→3s→35" else "16→3s→35"
        AppLogger.w(TAG, "VirtualDisplay not found — sending activation sequence ($seq) + polling")

        val timeoutMs = CLUSTER_DISPLAY_TIMEOUT_MS

        // Captured so a stale sendInfo callback from a cancelled activation cannot flip
        // mActivationCmd16Dispatched for the CURRENT one.
        // Do not start AppStartManagement in foreground: it briefly opens a visible BYD app.
        AppLogger.i(TAG, "Starting activation sequence without foreground AppStartManagement launch")
        mHandler.postDelayed({ sendActivationSequence(gen) }, 2000)

        // Listen for display additions + timeout.
        val pollCount = longArrayOf(0L)
        val listenerHolder = arrayOfNulls<DisplayManager.DisplayListener>(1)

        listenerHolder[0] = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (gen != mActivationGeneration) return
                val d = dm.getDisplay(displayId)
                AppLogger.i(TAG, "onDisplayAdded id=$displayId display=$d")
                if (isClusterDisplay(d)) {
                    if (!claimDisplayReady(gen)) return
                    dm.unregisterDisplayListener(listenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "VirtualDisplay cluster detected: id=$displayId")
                    notifyProjectionActive()
                    if (isCurrentActivation(gen)) callback.onDisplayReady(d, displayId)
                }
            }

            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {}
        }
        mActiveDisplayManager = dm
        mActiveDisplayListener = listenerHolder[0]
        dm.registerDisplayListener(listenerHolder[0], mHandler)

        // Additional polling: onDisplayAdded is sometimes not triggered for cross-process VDs.
        scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, 0, gen)

        // Named (not an inline lambda) so the DL4 daemon resolution can cancel THIS message alone
        // instead of flushing the whole handler queue — see resolveViaDaemon.
        val timeoutRunnable = Runnable {
            if (!isCurrentActivation(gen) || mReadyGeneration == gen) return@Runnable
            dm.unregisterDisplayListener(listenerHolder[0])
            mActiveDisplayListener = null
            mActiveDisplayManager = null
            AppLogger.w(TAG, "Timeout: cluster VirtualDisplay not detected after $timeoutMs ms")
            if (isDiLink4Safe()) {
                // Honest verdict, recorded ONLY when we actually know: the daemon successfully
                // read and parsed `dumpsys display` (so the privileged path works) and that dump
                // contained no cluster display, while the app side found none either (we are in
                // the timeout handler, which by construction only runs when it found nothing).
                // A shell that never answered is a TRANSPORT failure, not a firmware verdict —
                // in that case mDl4DumpParsed stays false and we keep the generic message.
                sDl4ProjectionUnavailable = mDl4DumpParsed && !mDl4SawCluster
                if (sDl4ProjectionUnavailable) {
                    AppLogger.e(TAG, "DL4: neither the app process nor the uid-2000 daemon can see "
                            + "a cluster display — projection is unavailable on this firmware")
                }
            }
            if (isCurrentActivation(gen)) callback.onDisplayTimeout()
            // Keep watching quietly for slow firmware variants. NOT extended to the DL4 daemon
            // probe on purpose: this watch exists for SLOW VirtualDisplay CREATION, and on DL4
            // the display provably pre-exists activation (the display set DMS iterated was
            // identical before and after cmd 16/35 across 54.6 s), so a display the daemon could
            // not see during the main window will not materialise during the grace period.
            armLateArrivalWatch(dm, callback, gen)
        }

        // ── DiLink 4.0 ONLY — resolve through the uid-2000 daemon ──────────────────────
        // Everything above this line depends on DisplayManager.getDisplays(), which on DL4 the
        // OEM firmware answers with an empty id list for our uid (getDisplayIdsInternal
        // isPermittedApp:false / forbid for:10167 / reject for:10167 to get id — 252/378/126
        // hits, never one grant, INC-20260713-180803). So the listener and the poll above CANNOT
        // succeed there, and have not: 5 reports, 3 app generations, always the same timeout.
        // The daemon is not on that whitelist, so it becomes the source of truth. The app-side
        // listener and poll are deliberately LEFT RUNNING as a harmless secondary: they cost
        // nothing, and if the firmware whitelist is ever lifted DL4 simply resolves the normal
        // way. Strictly gated on isDiLink4() → DL3/DL5 never arm this.
        if (isDiLink4Safe()) {
            scheduleDaemonDisplayPoll(dm, listenerHolder, callback, gen, timeoutMs, timeoutRunnable)
        }

        // Global timeout for VirtualDisplay creation.
        mHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    /**
     * DiLink 4.0 only — polls the uid-2000 daemon for the cluster display.
     *
     * Runs entirely off the main thread for the actual work: [DaemonDisplayEnumerator.enumerate]
     * hands the command to the shell worker and calls back there; we hop straight back onto
     * [mHandler] before touching any activation state. Nothing here can block the UI thread.
     */
    private fun scheduleDaemonDisplayPoll(
        dm: DisplayManager,
        listenerHolder: Array<DisplayManager.DisplayListener?>,
        callback: DisplayReadyCallback,
        gen: Int,
        timeoutMs: Long,
        timeoutRunnable: Runnable
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        // Belt-and-braces release of the cmd-16 latch. It is normally set by the cmd-16 callback,
        // but AdbLocalClient.sendInfo can in principle never call back (wedged shell, or the slow
        // connect+shell fallback when the typed daemon call fails) — in which case DL4 would fall
        // all the way back to a guaranteed timeout. Posted here, inside the DL4 gate, so DL3/DL5
        // never even schedule it.
        //
        // The bound MUST cover the actual chain, which depends on the ADAS-window-fix pref:
        //   fix OFF (default) : T+2 s  → cmd 16              → latch
        //   fix ON            : T+2 s  → cmd 30 → +3 s → cmd 16 → latch
        // The earlier fixed 10 s assumed the OFF chain ("cmd 16 is dispatched at T+2 s with no
        // intervening delay") and was simply wrong with the fix ON, where a 6 s cmd-30 round trip
        // puts cmd 16 at T+11 s — past the release, so a probe resolving at T+10 s pre-empted the
        // sequence and Qt was never put into projection. Testers run with the fix ON.
        val dispatchMaxMs = if (adasFixQuiet())
                ACTIVATION_DISPATCH_MAX_MS_ADAS_ON else ACTIVATION_DISPATCH_MAX_MS_ADAS_OFF
        mHandler.postDelayed({
            if (gen == mActivationGeneration && !mActivationCmd16Dispatched) {
                // Loud, not debug: if a resolution now pre-empts the sequence this line is the
                // only explanation for a cluster that reports connected while Qt stayed native.
                AppLogger.e(TAG, "DL4: cmd 16 not dispatched within $dispatchMaxMs ms (ADAS fix "
                        + (if (adasFixQuiet()) "ON" else "OFF")
                        + ") — releasing the daemon probe anyway; a resolution from here on may "
                        + "run BEFORE Qt is switched into projection mode")
                mActivationCmd16Dispatched = true
            }
        }, dispatchMaxMs)

        val probe = object : Runnable {
            override fun run() {
                if (gen != mActivationGeneration || mReadyGeneration == gen) return
                if (SystemClock.uptimeMillis() >= deadline) return
                // Self-heal: AdbLocalClient.executeShellWithResultBlocking has no client-side
                // timeout, so a wedged ADB socket can swallow the enumerate callback entirely.
                // Without this, ONE lost callback pins mDl4ProbeInFlight true and kills every
                // further probe for the whole activation window (the loop keeps re-posting but
                // never probes again). Same shape as HotspotKeeper's stuck-probe reset.
                val now = SystemClock.uptimeMillis()
                if (mDl4ProbeInFlight && now - mDl4ProbeStartedAt > DAEMON_PROBE_STUCK_MS) {
                    AppLogger.w(TAG, "DL4: daemon display probe stuck ${now - mDl4ProbeStartedAt}"
                            + "ms — force-releasing the in-flight guard")
                    mDl4ProbeInFlight = false
                    mDl4ProbeSeq++ // the wedged callback, if it ever lands, is now superseded
                }
                // See mActivationCmd16Dispatched: resolving before cmd 16 has been sent would
                // declare the cluster connected while Qt is still rendering natively.
                if (!mActivationCmd16Dispatched || mDl4ProbeInFlight) {
                    mHandler.postDelayed(this, DAEMON_PROBE_INTERVAL_MS)
                    return
                }
                mDl4ProbeInFlight = true
                mDl4ProbeStartedAt = now
                val probeSeq = mDl4ProbeSeq
                // Re-post from HERE, on the dispatch path, not from the callback. The stuck-reset
                // above only runs when a probe message is actually queued, so re-posting solely
                // from the callback made it unreachable: a swallowed callback left the guard
                // pinned true with nothing scheduled to release it, which is precisely the
                // failure the reset exists for. Self-scheduling keeps exactly one message pending
                // (the mDl4ProbeInFlight guard above prevents overlapping round trips).
                mHandler.postDelayed(this, DAEMON_PROBE_INTERVAL_MS)
                DaemonDisplayEnumerator.enumerate(mContext) { displays, shellOk ->
                    // Delivered on the shell worker thread — hop to the main looper first.
                    mHandler.post {
                        if (gen != mActivationGeneration) return@post
                        if (probeSeq != mDl4ProbeSeq) return@post // superseded by a stuck-reset
                        mDl4ProbeInFlight = false
                        if (shellOk && displays.isNotEmpty()) mDl4DumpParsed = true
                        val cluster = DaemonDisplayEnumerator.pickCluster(displays)
                        logDisplayResolutionOnce(dm, displays, shellOk)
                        val pastDeadline = SystemClock.uptimeMillis() >= deadline
                        if (cluster == null) {
                            // "The daemon saw displays but none of them is ours" is a different
                            // story from "the daemon saw nothing", and it is the story behind the
                            // honest verdict — name it once rather than leaving daemon=[…] to be
                            // re-read by hand.
                            val rejected = DaemonDisplayEnumerator.rejectedCandidates(displays)
                            if (rejected.isNotEmpty() && !mDl4RejectionLogged) {
                                mDl4RejectionLogged = true
                                AppLogger.w(TAG, "DL4: no cluster display we may drive — rejected "
                                        + "${rejected.size} non-default display(s) as private / "
                                        + "third-party-owned / powered off: "
                                        + DaemonDisplayEnumerator.describe(rejected))
                            }
                            // No re-post here: the loop re-schedules itself on dispatch (see
                            // above). Past the deadline the pending message is a no-op anyway —
                            // its first guard returns on the deadline check.
                            return@post
                        }
                        if (!claimDisplayReady(gen)) return@post
                        mDl4SawCluster = true
                        // A probe still in flight when the timeout fired can land after the
                        // verdict was computed. Never leave a "firmware unsupported" claim
                        // standing once the daemon has actually seen the display.
                        sDl4ProjectionUnavailable = false
                        // ...and having cleared it, we MUST also deliver the display: dropping
                        // out here left the user with neither the connection nor the (now known
                        // false) honest message. onDisplayLateReady is the existing channel for
                        // "it turned up after onDisplayTimeout was delivered", and
                        // DashboardDisplayHelper already ignores it after a stop / once connected.
                        resolveViaDaemon(dm, listenerHolder, callback, cluster,
                                timeoutRunnable, late = pastDeadline)
                    }
                }
            }
        }
        mHandler.postDelayed(probe, DAEMON_PROBE_INTERVAL_MS)
    }

    /** Hands the daemon-resolved display to the caller and tears the app-side watch down. */
    private fun resolveViaDaemon(
        dm: DisplayManager,
        listenerHolder: Array<DisplayManager.DisplayListener?>,
        callback: DisplayReadyCallback,
        cluster: ClusterDisplayInfo,
        timeoutRunnable: Runnable,
        late: Boolean
    ) {
        // Publish BEFORE the callback: every consumer that can no longer obtain a Display object
        // (input forwarder, mirror, launch bounds, screenshot recorder) reads the registry, and
        // onDisplayReady synchronously reaches all of them.
        ClusterDisplayRegistry.set(cluster)
        // TARGETED cancel — NOT removeCallbacksAndMessages(null).
        //
        // Flushing the whole queue also destroyed the still-pending activation chain. With the
        // ADAS window fix ON that chain is T+2 s → cmd 30 → +3 s → cmd 16 → +3 s → cmd 35, so a
        // resolution landing between cmd 30 and cmd 16 silently deleted cmd 16 — the ONE command
        // that puts Qt into projection — and DashCast then launched the user's app onto a cluster
        // still rendering the native OEM UI, with no log line saying cmd 16 had been skipped.
        // Only the global timeout actually has to go: the app-side display poll self-terminates
        // on its poll count, the daemon probe stops after readiness, and the cmd-16 watchdog is
        // one-shot. The one-shot readiness claim prevents duplicate callbacks without cancelling
        // cmd 16/cmd 35 that still belong to this activation.
        mHandler.removeCallbacks(timeoutRunnable)
        try {
            dm.unregisterDisplayListener(listenerHolder[0])
        } catch (ignore: Throwable) {
            // Already unregistered by a racing path — nothing to do.
        }
        mActiveDisplayListener = null
        mActiveDisplayManager = null
        AppLogger.i(TAG, "Cluster display resolved through the uid-2000 daemon"
                + (if (late) " (LATE — after the activation window)" else "") + ": $cluster")
        notifyProjectionActive()
        // Still ASK for the object: it is null on DL4 today (same whitelist), but if the
        // firmware is ever fixed the pipeline gets the real Display and behaves like DL3.
        val real = try {
            dm.getDisplay(cluster.id)
        } catch (ignore: Throwable) {
            null
        }
        if (!ProjectionCommandBus.isCurrent(mProjectionSession)) return
        if (late) callback.onDisplayLateReady(real, cluster.id)
        else callback.onDisplayReady(real, cluster.id)
    }

    /**
     * One line per activation making the whole DL4 diagnosis readable from the journal alone,
     * e.g. `display resolution — shellOk=true app=[] daemon=[id=1
     * name=fission_bg_xdjaVirtualSurface 1920x720 layerStack=1 state=ON owner=…]`. An empty
     * `app=[]` next to a populated `daemon=[…]` IS the DisplayManagerService whitelist, with no
     * logcat coverage required.
     *
     * [shellOk] is printed because without it `daemon=[]` is ambiguous between the only two
     * conclusions this whole design turns on: `shellOk=true daemon=[]` means the firmware hides
     * the display from uid 2000 as well (a real verdict), `shellOk=false daemon=[]` means the
     * shell never answered (a transport failure that says nothing about the firmware).
     */
    private fun logDisplayResolutionOnce(
        dm: DisplayManager,
        daemonSide: List<ClusterDisplayInfo>,
        shellOk: Boolean
    ) {
        if (mDl4ResolutionLogged) return
        mDl4ResolutionLogged = true
        val appSide = try {
            dm.displays?.filter { it.displayId != 0 }
                ?.joinToString(", ") { "id=${it.displayId} name=${it.name}" } ?: ""
        } catch (ignore: Throwable) {
            ""
        }
        AppLogger.i(TAG, "display resolution — shellOk=$shellOk app=[$appSide] "
                + "daemon=[${DaemonDisplayEnumerator.describe(daemonSide)}]")
    }

    private fun scheduleDisplayPoll(
        dm: DisplayManager,
        listenerHolder: Array<DisplayManager.DisplayListener?>,
        callback: DisplayReadyCallback,
        pollCount: LongArray,
        delayMs: Long,
        gen: Int
    ) {
        // Single Runnable per activation; reschedules itself via postDelayed(this, …).
        val pollRunnable = object : Runnable {
            override fun run() {
                // Inert on DL3/DL5: nothing bumps the generation there except cancel(), which
                // already flushes this runnable off the handler. On DL4 it stops the poll from
                // delivering a second onDisplayReady after the daemon resolved the display.
                if (gen != mActivationGeneration || mReadyGeneration == gen) return
                pollCount[0]++
                if (pollCount[0] * POLL_INTERVAL_MS >= CLUSTER_DISPLAY_TIMEOUT_MS) return

                val found = findClusterDisplay(dm)
                if (found != null) {
                    if (!claimDisplayReady(gen)) return
                    dm.unregisterDisplayListener(listenerHolder[0])
                    mActiveDisplayListener = null
                    mActiveDisplayManager = null
                    AppLogger.i(TAG, "VirtualDisplay found by polling: id=${found.displayId}")
                    notifyProjectionActive()
                    if (isCurrentActivation(gen)) callback.onDisplayReady(found, found.displayId)
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
    private fun armLateArrivalWatch(
        dm: DisplayManager,
        callback: DisplayReadyCallback,
        gen: Int,
    ) {
        // One-shot: prevents both the DisplayListener and the polling loop from firing.
        val consumed = booleanArrayOf(false)

        val expiry = Runnable {
            if (!isCurrentActivation(gen)) return@Runnable
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
                if (consumed[0] || !isCurrentActivation(gen)) return
                val d = dm.getDisplay(displayId)
                if (!isClusterDisplay(d)) return
                consumed[0] = true
                mHandler.removeCallbacks(expiry)
                dm.unregisterDisplayListener(holder[0])
                mLateArrivalListener = null
                mLateArrivalManager = null
                AppLogger.i(TAG, "Late arrival: cluster VD appeared — id=$displayId")
                notifyProjectionActive()
                if (isCurrentActivation(gen)) callback.onDisplayLateReady(d, displayId)
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
                if (consumed[0] || !isCurrentActivation(gen)) return
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
                    if (isCurrentActivation(gen)) callback.onDisplayLateReady(found, found.displayId)
                } else if (SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, 2000)
                }
            }
        }, 2000)

        AppLogger.i(TAG, "Late arrival watch armed (${LATE_ARRIVAL_GRACE_MS / 1000}s grace)")
    }

    // ── Warm path helper ──────────────────────────────────────────────────────

    /** Sends sendInfo(16) and notifies the callback when done (or on error). */
    private fun sendWarmCmd16(display: Display, callback: DisplayReadyCallback, gen: Int) {
        sendProjectionInfo(CMD_PROJECTION_ON,
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.i(TAG, "warm path ADB(cmd=16): $out")
                    mHandler.post {
                        if (!claimDisplayReady(gen)) return@post
                        notifyProjectionActive()
                        if (isCurrentActivation(gen)) {
                            callback.onDisplayReady(display, display.displayId)
                        }
                    }
                }

                override fun onError(err: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.e(TAG, "warm path ADB(cmd=16) ERROR: $err")
                    mHandler.post {
                        if (isCurrentActivation(gen) && mReadyGeneration != gen) {
                            callback.onDisplayTimeout()
                        }
                    }
                }
            })
    }

    /**
     * Whether the ADAS window fix may send its geometry command on this car.
     *
     * The fix works by forcing the cluster into the 12.3" shape (cmd 30) — legitimate on a 12.3"
     * panel, and the whole point on a 10.25" one, where it is what removes the ADAS window bug.
     * On the 8.8" / 1280x480 clusters (Atto 3, Dolphin) it is not: owners have sent captures of
     * their instrument cluster dropping into its degraded "simple mode" after being forced to
     * 1920x720. That is a vehicle-level symptom — strictly worse than our projection not working —
     * so it is refused outright, and the car takes the plain no-geometry-change path instead.
     *
     * Checked on BOTH the configured type and, when a display is already up, its real geometry.
     * The type alone is not enough: [ClusterPrefs.CLUSTER_TYPE_DEFAULT] is 12.3", so an Atto 3
     * owner who never opened Settings sails straight past a type-only guard — which is the most
     * likely way those captures came to exist in the first place.
     *
     * Deliberately keyed to the ONE geometry proven to break (1280x480) rather than "anything
     * smaller than 12.3\"": the native resolution of a 10.25" cluster is not established, and a
     * width-based rule risks blocking the very car the fix is needed on.
     */
    /**
     * Records, permanently, that this car has the small 1280x480 panel. Reads the display we were
     * handed rather than re-querying, and is only ever called before a geometry command has been
     * sent this session — see the call site in [activateClusterDisplay].
     */
    private fun latchPanelGeometry(display: Display) {
        if (ClusterPrefs.isSmallClusterPanelLatched(mContext)) return
        val size = Point()
        try {
            @Suppress("DEPRECATION")
            display.getRealSize(size)
        } catch (t: Throwable) {
            return // Unreadable geometry latches nothing.
        }
        if (!ClusterGeometryPolicy.isSmallPanelGeometry(size.x, size.y)) return
        ClusterPrefs.latchSmallClusterPanel(mContext)
        AppLogger.i(TAG, "small cluster panel observed (${size.x}x${size.y}) before any command — "
                + "latched; shape presets 30/31 are refused on this car from now on")
    }

    private fun adasGeometryAllowed(current: Display?): Boolean {
        if (ClusterPrefs.getClusterType(mContext) == CMD_SCREEN_SIZE_ATTO3) return false
        if (current != null) {
            val size = Point()
            try {
                @Suppress("DEPRECATION")
                current.getRealSize(size)
            } catch (t: Throwable) {
                return true // Unreadable geometry is not evidence of a small panel — fail open.
            }
            if (size.x == ADAS_UNSAFE_W && size.y == ADAS_UNSAFE_H) return false
        }
        return true
    }

    /** Silent form, for the readers that only DESCRIBE the sequence or size a timeout for it. */
    private fun adasFixQuiet(): Boolean =
        ClusterPrefs.isAdasWindowFixEnabled(mContext) && adasGeometryAllowed(null)

    /**
     * The ADAS fix as it will ACTUALLY be applied: the user's preference narrowed by
     * [adasGeometryAllowed]. Every reader — the sequence that gets sent, the log lines describing
     * it, and the DL4 dispatch timeout sized for it — must use this or [adasFixQuiet] rather than
     * the raw preference, or they describe a sequence the car is never going to receive.
     */
    private fun adasFixEffective(current: Display? = null): Boolean {
        if (!ClusterPrefs.isAdasWindowFixEnabled(mContext)) return false
        if (adasGeometryAllowed(current)) return true
        AppLogger.w(TAG, "ADAS window fix is ON but this is an 8.8\" cluster — refusing the 12.3\" "
                + "switch (it drops that panel into its degraded simple mode); using the plain path")
        return false
    }

    // ── Activation sequence sendInfo(30 → 16 → 35) ─────────────────────────────

    /**
     * Full activation sequence to create the VirtualDisplay (slow path).
     * ADAS fix OFF (default): sendInfo(16) → 3s → sendInfo(35).
     * ADAS fix ON: sendInfo(30) → 3s → sendInfo(16) → 3s → sendInfo(35).
     * The DisplayReadyCallback is NOT called here: the DisplayListener / polling handles it.
     *
     * [gen] is the activation generation captured when this was scheduled; it is only used to
     * stamp [mActivationCmd16Dispatched], which only the DL4 daemon poll reads. Behaviour on
     * DL3/DL5 is unchanged — the value is carried and never acted upon.
     */
    private fun sendActivationSequence(gen: Int) {
        if (gen != mActivationGeneration) return
        // No display exists yet on this path, so the geometry half of the guard cannot run —
        // only the configured type is available to protect an 8.8" car here.
        val adasFix = adasFixEffective()
        AppLogger.i(TAG, "sendActivationSequence — ADAS fix " + (if (adasFix) "ON (30→3s→16→3s→35)" else "OFF (16→3s→35)"))

        if (adasFix) {
            // Original sequence: sendInfo(30) → 3s → sendInfo(16) → 3s → sendInfo(35).
            sendProjectionInfo(CMD_SCREEN_SIZE_SEAL_EU,
                object : AdbLocalClient.Callback {
                    override fun onSuccess(out: String?) {
                        if (gen != mActivationGeneration) return
                        AppLogger.i(TAG, "activation ADB(cmd=30): $out")
                        mHandler.postDelayed({
                            if (gen == mActivationGeneration) sendActivationCmd16ThenCmd35(gen)
                        }, 3000)
                    }

                    override fun onError(err: String?) {
                        if (gen != mActivationGeneration) return
                        AppLogger.e(TAG, "activation ADB(cmd=30) ERROR: $err")
                        // cmd=30 failed — still attempt 16 → 35.
                        sendActivationCmd16ThenCmd35(gen)
                    }
                })
        } else {
            // Default: sendInfo(16) → 3s → sendInfo(35), no screen-size change.
            sendActivationCmd16ThenCmd35(gen)
        }
    }

    /** Sends sendInfo(16) → 3s delay → sendInfo(35) (VirtualDisplay creation trigger). */
    private fun sendActivationCmd16ThenCmd35(gen: Int) {
        if (gen != mActivationGeneration) return
        sendProjectionInfo(CMD_PROJECTION_ON,
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.i(TAG, "activation ADB(cmd=16): $out")
                    markCmd16Dispatched(gen)
                    mHandler.postDelayed({
                        if (gen == mActivationGeneration) sendActivationCmd35(gen)
                    }, 3000)
                }

                override fun onError(err: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.e(TAG, "activation ADB(cmd=16) ERROR: $err")
                    // Dispatched-and-failed still counts: the latch only certifies that we are no
                    // longer waiting to SEND cmd 16, so the DL4 probe may resolve.
                    markCmd16Dispatched(gen)
                    // Still attempt sendInfo(35) even if cmd=16 failed.
                    mHandler.postDelayed({
                        if (gen == mActivationGeneration) sendActivationCmd35(gen)
                    }, 3000)
                }
            })
    }

    /** Sends sendInfo(35) — triggers Qt JNI → AutoDisplayService.createVirtualDisplay(). */
    private fun sendActivationCmd35(gen: Int) {
        if (gen != mActivationGeneration) return
        sendProjectionInfo(CMD_DI40_MODE,
            object : AdbLocalClient.Callback {
                override fun onSuccess(out: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.i(TAG, "activation ADB(cmd=35): $out")
                    // Defensive: cmd 35 can only run after cmd 16, so the latch is already set.
                    markCmd16Dispatched(gen)
                }
                override fun onError(err: String?) {
                    if (gen != mActivationGeneration) return
                    AppLogger.e(TAG, "activation ADB(cmd=35) ERROR: $err")
                    markCmd16Dispatched(gen)
                }
            })
    }

    /**
     * Marks cmd 16 as dispatched. Called from AdbLocalClient callbacks (background threads) —
     * hence the volatile field. Only the DL4 daemon poll reads this.
     */
    private fun markCmd16Dispatched(gen: Int) {
        if (gen == mActivationGeneration) mActivationCmd16Dispatched = true
    }

    private fun sendProjectionInfo(info: Int, callback: AdbLocalClient.Callback) {
        ProjectionCommandBus.sendInfo(
            mContext,
            mProjectionSession,
            CLUSTER_TYPE,
            info,
            "",
            callback,
        )
    }

    @Synchronized
    private fun claimDisplayReady(gen: Int): Boolean {
        if (!isCurrentActivation(gen) || mReadyGeneration == gen) return false
        mReadyGeneration = gen
        return true
    }

    private fun isCurrentActivation(gen: Int): Boolean =
        gen == mActivationGeneration &&
            this::mProjectionSession.isInitialized &&
            ProjectionCommandBus.isCurrent(mProjectionSession)

    // ── Cluster display detection ─────────────────────────────────────────

    private fun findClusterDisplay(dm: DisplayManager): Display? {
        // Strategy 1: PRESENTATION category — prefer displays with a known cluster name, and among
        // those prefer the OEM's production target (shared_..._0) over the debug one. See
        // ClusterDisplayNames.clusterNamePriority: self-gating, no-op off trinket.
        val presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        var fallback: Display? = null
        if (presentations != null) {
            var best: Display? = null
            for (d in presentations) {
                if (d.displayId == 0) continue
                AppLogger.d(TAG, "PRESENTATION candidate: id=${d.displayId} name=${d.name}")
                if (isKnownClusterName(d.name)) {
                    if (best == null || ClusterDisplayNames.clusterNamePriority(d.name)
                            < ClusterDisplayNames.clusterNamePriority(best.name)) {
                        best = d
                    }
                } else if (fallback == null) {
                    fallback = d // unnamed non-zero: keep as fallback
                }
            }
            if (best != null) {
                AppLogger.i(TAG, "cluster display picked: id=${best.displayId} name=${best.name}")
                return best
            }
        }
        if (fallback != null) return fallback

        // Strategy 2: any non-default display — prefer named (by the same priority), else first.
        val all = dm.displays
        if (all != null) {
            var best: Display? = null
            var anyNonDefault: Display? = null
            for (d in all) {
                if (d.displayId == 0) continue
                AppLogger.d(TAG, "Non-default candidate: id=${d.displayId} name=${d.name}")
                if (isKnownClusterName(d.name)) {
                    if (best == null || ClusterDisplayNames.clusterNamePriority(d.name)
                            < ClusterDisplayNames.clusterNamePriority(best.name)) {
                        best = d
                    }
                } else if (anyNonDefault == null) {
                    anyNonDefault = d
                }
            }
            if (best != null) {
                AppLogger.i(TAG, "cluster display picked: id=${best.displayId} name=${best.name}")
                return best
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
     * DiLink 4.0 gate for every daemon-side change in this class.
     *
     * [Platform.isDiLink4] is a cached, pure, boot-time verdict requiring an explicit
     * `dilink4`-family product/model/fingerprint AND API 28/29, so it cannot false-positive on
     * DL3 or DL5 — and `isDiLink3()` is DEFINED as "not DL2 and not DL4 and not DL5", so a
     * true here also guarantees every existing DL3 gate is false. Fail-closed on any throw.
     */
    private fun isDiLink4Safe(): Boolean {
        return try {
            Platform.get().isDiLink4(mContext)
        } catch (ignore: Throwable) {
            false
        }
    }

    /**
     * DL5: pick the first PRESENTATION display (typically id=3) and notify. Falls back to any
     * non-default display, then to a short polling window if nothing is up yet.
     */
    private fun resolveDl5Display(
        dm: DisplayManager,
        callback: DisplayReadyCallback,
        gen: Int,
    ) {
        val d = findClusterDisplay(dm)
        if (d != null) {
            if (!claimDisplayReady(gen)) return
            AppLogger.i(TAG, "DL5 cluster display ready: id=${d.displayId} name=${d.name}")
            notifyProjectionActive()
            mHandler.post {
                if (isCurrentActivation(gen) && mReadyGeneration == gen) {
                    callback.onDisplayReady(d, d.displayId)
                }
            }
            return
        }
        // Brief polling window (up to 3 s) — should never trigger on DL5 in practice.
        val deadline = SystemClock.uptimeMillis() + 3000
        mHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isCurrentActivation(gen)) return
                val dd = findClusterDisplay(dm)
                if (dd != null) {
                    if (!claimDisplayReady(gen)) return
                    AppLogger.i(TAG, "DL5 cluster display (late) id=${dd.displayId}")
                    notifyProjectionActive()
                    if (isCurrentActivation(gen)) callback.onDisplayReady(dd, dd.displayId)
                } else if (SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, 250)
                } else {
                    AppLogger.w(TAG, "DL5 cluster display not found after 3s — timeout")
                    if (isCurrentActivation(gen)) callback.onDisplayTimeout()
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
        // Invalidate any in-flight daemon probe / sendInfo callback from this activation: they
        // run on background threads and cannot be removed from the handler queue.
        mActivationGeneration++
        mReadyGeneration = -1
        mActivationCmd16Dispatched = false
        mDl4ProbeInFlight = false
        mDl4ProbeStartedAt = 0L
        mDl4ProbeSeq++
        mDl4DumpParsed = false
        mDl4SawCluster = false
        mDl4ResolutionLogged = false
        mDl4RejectionLogged = false
        // The projection session this geometry described is over (cancel() is reached from
        // DashboardDisplayHelper.stop()/stopWithoutAdb() and from a fresh activateClusterDisplay).
        // Leaving it populated let a later mirror attempt composite a stopped projection's
        // layerStack and arm touch injection on it. No-op on DL3/DL5 — never populated there.
        ClusterDisplayRegistry.clear()
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

    /** Cancels a directly-owned activation and relinquishes its process-wide command session. */
    fun abandon() {
        cancel()
        if (this::mProjectionSession.isInitialized) {
            ProjectionCommandBus.endSession(mProjectionSession)
        }
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

        /** 8.8" cluster preset (Atto 3, Dolphin…) — the panel the ADAS fix must never touch. */
        const val CMD_SCREEN_SIZE_ATTO3 = 29

        /** Native geometry of that 8.8" panel, the one shape proven to break under a forced 12.3". */
        private const val ADAS_UNSAFE_W = 1280
        private const val ADAS_UNSAFE_H = 480
        const val CMD_DI40_MODE = 35 // Di4.0 mode — triggers VirtualDisplay creation (CONFIRMED 03/05/2026)

        // Timeout waiting for the VirtualDisplay after the sendInfo activation sequence.
        //
        // WHAT IS ACTUALLY KNOWN (this comment previously asserted the opposite):
        //  • DL3/DL5 resolve their display in <1 s (already present / warm path), so they never
        //    approach this ceiling. The value only matters to firmwares that fail outright.
        //  • On DiLink 4.0 the cluster VirtualDisplay PRE-EXISTS activation. Across 54.6 s of
        //    INC-20260727-203241 the display set DisplayManagerService iterated never changed —
        //    identical before and after cmd 16/35 — and the uid-2000 dump lists
        //    `fission_bg_xdjaVirtualSurface, 1920x720, layerStack 1, state ON` throughout.
        //    The earlier claim that "cmd 35 creates the VD ~8-9 s after it is sent" was a
        //    mis-inference from a timestamp in INC-20260713-180803, and the 12s→20s bump rested
        //    on it. DL4 never timed out because the window was short: it timed out because the
        //    app process is FORBIDDEN from enumerating the display at all (the OEM
        //    DisplayManagerService whitelist), which no timeout can fix. Do not raise this: it
        //    was already proven pointless across 5 reports and 3 app generations.
        //  • Nothing here is asserted about DL3 VD creation timing — that has not been measured
        //    the same way, and the DL3 slow path is not what these reports exercise.
        private const val CLUSTER_DISPLAY_TIMEOUT_MS = 20000L
        // Polling interval to detect the virtual display.
        private const val POLL_INTERVAL_MS = 500L
        // Grace period after the activation timeout during which we keep watching for slow VD
        // creation. Not used by the DL4 daemon path — see the timeout handler for why.
        private const val LATE_ARRIVAL_GRACE_MS = 60_000L

        // ── DiLink 4.0 daemon probe tuning ────────────────────────────────────────────
        // 2 s cadence: each probe is a shell round-trip (`dumpsys display | grep`), so polling it
        // at the app-side 500 ms rate would queue round-trips on the single shell worker for no
        // benefit — the display pre-exists, one successful probe is enough.
        private const val DAEMON_PROBE_INTERVAL_MS = 2000L
        // A probe whose shell round trip has not answered within this is treated as wedged and
        // its in-flight claim is force-released (AdbLocalClient's blocking shell has no
        // client-side timeout). 3x the probe cadence — a healthy `dumpsys display | grep`
        // round trip is well under a second.
        private const val DAEMON_PROBE_STUCK_MS = 6000L
        // Worst case for cmd 16 — the command that switches Qt into projection — to finish
        // DISPATCHING. This is the ONLY thing the DL4 probe must wait for; cmd 35 merely asks
        // for a VirtualDisplay that provably already exists on DL4.
        //   ADAS fix OFF (default): 2 s initial delay + 8 s allowance for one sendInfo round
        //     trip (the typed daemon call can fall back to a slow connect+shell path) = 10 s,
        //     leaving 10 s / 5 probes inside the 20 s activation window. Kept at the original
        //     10 s deliberately: releasing EARLY risks resolving before Qt is switched into
        //     projection (a cluster that reports connected but never projects), which is a far
        //     worse outcome than spending one probe slot, and 5 probes is ample.
        //   ADAS fix ON: 2 s + 6 s (cmd 30 round trip) + 3 s (fixed 30→16 gap) + 2 s margin
        //     = 13 s, leaving 7 s / 3 probes. Testers run with the fix ON, and the previous
        //     single 10 s value was computed for the OFF chain only — with the fix ON it expired
        //     BEFORE cmd 16 was even sent.
        private const val ACTIVATION_DISPATCH_MAX_MS_ADAS_OFF = 10000L
        private const val ACTIVATION_DISPATCH_MAX_MS_ADAS_ON = 13000L

        /**
         * DiLink 4.0 verdict: the daemon successfully read `dumpsys display` and it contained no
         * cluster display, while the app process saw none either. Set at the activation timeout,
         * read by ClusterService/MainActivity to say "unavailable on this firmware" instead of
         * the generic "cluster disconnected". Stays false when the shell simply failed to answer.
         */
        @Volatile
        private var sDl4ProjectionUnavailable = false

        @JvmStatic
        fun isDl4ProjectionUnavailable(): Boolean = sDl4ProjectionUnavailable

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
         *
         * Delegates to [ClusterDisplayNames] so the app-side and the daemon-side lookups use
         * ONE implementation and cannot drift. Behaviour is byte-identical to the inlined
         * version this replaced.
         */
        private fun isKnownClusterName(name: String?): Boolean =
            ClusterDisplayNames.isKnownClusterName(name)
    }
}

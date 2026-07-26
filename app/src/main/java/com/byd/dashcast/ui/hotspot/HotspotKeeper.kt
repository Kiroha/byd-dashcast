package com.byd.dashcast.ui.hotspot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.proxy.ProxyWatchdog
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.ui.settings.SettingsActivity
import com.byd.dashcast.util.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HotspotKeeper — the single owner of the "start the TetherFi tile" action.
 *
 * The in-Activity watchdog ([HotspotActivity]) only ran while the Hotspot page was resumed, so the
 * "always on" toggle silently STOPPED keeping the hotspot alive the moment the user left the page
 * (INC-20260705-195419). This runs the probe→restart loop from the always-on
 * [com.byd.dashcast.proxy.ProxyKeeperService] heartbeat instead, so it survives page and app close.
 *
 * Two entry points, and only two:
 *  - [maybeKeepAlive] — the CONTINUOUS keeper, gated on `PREF_HOTSPOT_WATCHDOG`, called from the
 *    heartbeat: probe every [PROBE_INTERVAL_MS], launch when TetherFi is DOWN.
 *  - [runImmediatePass] — ONE probe-then-start pass right now, used by `BootReceiver` (gated on its
 *    own, independent `PREF_HOTSPOT_AUTOSTART_BOOT`) and by the Hotspot page's switch.
 *
 * v1.6.148 fixes BUG 2 (proven, INC-20260721-184844): TWO uncoordinated dispatchers each launched
 * the tile at DashCast start — this keeper's first heartbeat pass at ~T+0.8 s and BootReceiver's
 * own BLIND `postDelayed(7 s)` `startActivity`, which never checked whether the hotspot was already
 * on. Two popups, 13.9 s apart. BootReceiver now delegates here and ONE dedupe rule on DISPATCHES
 * ([claimDispatch]) makes the two passes safe.
 *
 * The car-off failure (~185 dispatches over 13.8 h parked, hotspot never up) is NOT root-caused. It
 * was ASSUMED to be Android 10's background-activity-start restriction, but no "Background activity
 * start blocked" line exists in any capture and Android 10 logs that unconditionally — so the
 * Activity may have been launching all along, with TetherFi declining to raise the AP while parked.
 * The uid-2000 routes ([Route.DAEMON], [Route.ADB]) are a CHEAP HEDGE against that hypothesis,
 * nothing more; the in-app route is still tried, and the launch evidence in [reportConfirmation]
 * (a process snapshot taken before the dispatch, diffed against the one the +8 s confirmation
 * re-probe carries) is what will settle it — the AP probe alone cannot distinguish "the tile ran
 * but TetherFi did not raise the AP" from "the tile never launched".
 *
 * Note: on BYD DiLink ROMs the OS may not start DashCast at boot at all unless the app is
 * whitelisted for auto-start ("self-start management") — no code path can bypass that.
 */
object HotspotKeeper {

    private const val TAG = "HotspotKeeper"
    private const val TF_PKG = "com.pyamsoft.tetherfi"
    private const val TF_TILE_CLS = "com.pyamsoft.tetherfi.tile.ProxyTileActivity"
    private const val TF_KEY_ACTION = "key_action"
    private const val TF_ACTION_START = "START"

    /** `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_NO_HISTORY`, for `am start -f`, so a shell launch
     *  has the same semantics as the in-app Intent (the tile must not linger in Recents). */
    private const val TF_TILE_FLAGS_HEX = "0x50000000"

    /** Is TetherFi's foreground proxy service running, and what is its process id right now? The
     *  same AP probe the Hotspot page uses, so both read the same truth. `DOWN` does not contain
     *  `UP`, so `contains("UP")` is sound, and a pid list is digits and spaces so it can never
     *  spell UP either. The `pid=` line rides along on the SAME round trip and is what makes the
     *  launch evidence possible without a probe of its own: the payload of the probe that precedes
     *  a dispatch is its BEFORE snapshot, the +8 s confirmation re-probe is the AFTER one (see
     *  [HotspotKeeperPolicy.classifyLaunch]). */
    private const val PROBE_CMD =
        "dumpsys activity services $TF_PKG" +
            " 2>/dev/null | grep -q ProxyForegroundService && echo UP || echo DOWN; " +
            "echo \"pid=\$(pidof $TF_PKG 2>/dev/null)\""

    /** The validated uid-2000 launch command. `2>&1` because `am` writes `Error:` to stderr. Not
     *  `am start -W`: that blocks until idle, and the re-probe below is a better verdict anyway. */
    private const val AM_START_TILE_CMD =
        "am start -f $TF_TILE_FLAGS_HEX -n $TF_PKG/$TF_TILE_CLS" +
            " --es $TF_KEY_ACTION $TF_ACTION_START 2>&1"

    /** Probe cadence. Raised from the old 20 s: we are only ever willing to ACT once per retry rung
     *  (≥ 60 s), so probing faster buys nothing but shell traffic. */
    private const val PROBE_INTERVAL_MS = 30_000L

    /** A probe whose callback never fired (gateway executor wedged, or an Error escaped
     *  AdbLocalClient) is force-reset this far past due, so [probeInFlight] cannot stick true and
     *  silently kill the keep-alive. */
    private const val PROBE_STUCK_MS = PROBE_INTERVAL_MS * 3

    /** ONE confirmation re-probe after a dispatch, measured from it. 8 s covers even a slow cold
     *  start; it only ever produces a log verdict, never a control decision, so a single late
     *  answer is strictly better than an early one plus a retry. It refreshes `lastProbeMs`, so it
     *  REPLACES the next cadence probe instead of adding to it. */
    private const val CONFIRM_DELAY_MS = 8_000L

    /** Safety release for [runImmediatePass]: BootReceiver holds a `goAsync()` token while we probe
     *  and launch, and it must be released even if a shell callback is lost. The pass is armed 3 s
     *  into onReceive, so the worst case is 15 s — far inside the ~60 s a background broadcast
     *  gets. */
    private const val PASS_TIMEOUT_MS = 12_000L

    /**
     * The dedupe window — see [claimDispatch]. 20 s, because the two passes that raced are ≤ ~5 s
     * apart BY CONSTRUCTION (heartbeat at ~T+0.8 s of process start, boot arm at T+3 s) plus a few
     * seconds of shell round trip each, and the field capture showed the two popups 13.9 s apart;
     * and because 20 s is well UNDER the first retry rung (60 s), so it can never suppress a
     * legitimate retry — it only ever collapses two views of the same event.
     */
    private const val DEDUPE_MS = 20_000L

    /**
     * A launch claim ([claimDispatch]) older than this is treated as STUCK and can be re-taken —
     * the same guard [PROBE_STUCK_MS] gives the probe, and for the same reason: the claim is
     * released in the launch thread's `finally`, so a thread that never returns would pin it true
     * for the life of the process and silently kill the keep-alive forever. `ProxyClient.runShell`
     * has no client-side timeout, so a daemon whose binder pool is exhausted or frozen is exactly
     * that thread.
     *
     * 5 min, from the real BOUNDED worst case of one launch sequence:
     *   daemon 30 s (the daemon's own shell cap, once a binder thread picks the call up)
     *   + ADB 30 s handshake gate (`HANDSHAKE_GATE_WAIT_MS`) + 60 s idle timeout
     *     (`AdbLocalClient.SHELL_IDLE_TIMEOUT_MS`, plus one 400 ms handshake retry)
     *   + the in-app route (immediate)  ≈ 120 s.
     * 5 min is >2× that, so a reclaim never fires on a merely SLOW sequence. It says nothing about
     * the WEDGED one it exists for: that thread keeps running, and if its binder later drains it
     * would resume its route loop next to the sequence that replaced it — two launches seconds
     * apart, the double popup straight back. What actually prevents that is the claim re-check at
     * the top of every route iteration in [launch]; this constant only bounds the silence.
     * It equals the last rung of the retry ladder, so a wedged claim costs at most one rung.
     */
    private const val LAUNCH_STUCK_MS = 300_000L

    /** "No launch sequence in flight" — see [launchClaimMs]. A real claim is stamped with
     *  `elapsedRealtime` coerced to ≥ 1, so it can never be mistaken for this. */
    private const val NO_CLAIM = 0L

    /** Where one launch attempt sends the tile Intent. Tried in declaration order, once each. */
    private enum class Route(val tag: String) {
        /** uid-2000 `am start` through the proxy daemon. */
        DAEMON("daemon"),

        /** The same `am start` through AdbLocalClient's blocking local ADB shell (also uid 2000). */
        ADB("adb"),

        /** `Context.startActivity` from the DashCast process. */
        APP("app")
    }

    /** What one route did with the tile Intent — see [attemptRoute]. */
    private enum class Outcome {
        /** The Intent was accepted: stop here and let the confirmation re-probe judge it. */
        STARTED,

        /** It provably never reached the activity manager (route unavailable, or `am` answered an
         *  explicit `Error:`): the next route may safely re-issue the same command. */
        FAILED,

        /** It MAY have run — the command was sent and the reply was lost. Re-issuing it on the next
         *  route would draw a second ProxyTileActivity, so the sequence stops instead. */
        UNCERTAIN
    }

    @Volatile private var lastProbeMs = 0L
    @Volatile private var probeInFlight = false

    /** When the last launch sequence was CLAIMED — i.e. the last ATTEMPT, whether or not any route
     *  went on to report success. Drives both the retry cadence and the dedupe window. */
    @Volatile private var lastAttemptMs = 0L

    /** TetherFi's process id(s) as of the last probe that could read them, or
     *  [HotspotKeeperPolicy.PID_UNKNOWN] when the last probe failed (a stale pid must never be
     *  passed off as a fresh measurement). This is the BEFORE half of the launch evidence. */
    @Volatile private var lastProbePid = HotspotKeeperPolicy.PID_UNKNOWN

    /** 0 = no launch sequence in flight; otherwise the `elapsedRealtime` at which the running one
     *  was claimed, so a claim that never gets released can be spotted and re-taken. Written only
     *  by CAS, so a stuck thread that finally wakes up cannot release someone else's claim. */
    private val launchClaimMs = AtomicLong(NO_CLAIM)

    /** Consecutive ATTEMPTS never CONFIRMED by a re-probe. Drives the retry ladder; it gates
     *  nothing, so the keeper can never give up. Reset on a confirmed launch, on an UP probe, and
     *  on every boot / ACC-on / user pass. Atomic because it is written both from the shell-gateway
     *  callback thread and from the `hotspot-tile-launch` thread. */
    private val unconfirmedStreak = AtomicInteger(0)

    /** Rate limiter for the "no shell" warning — see the call site. On a unit with neither a daemon
     *  binder nor a reachable ADB transport this condition holds on EVERY cadence probe, and an
     *  unthrottled line there (~120/h) drowns the launch verdicts that are the point of this build.
     *  [noShellStreak] keeps the true count so nothing is hidden, only de-duplicated. */
    private const val NO_SHELL_LOG_MS = 15 * 60_000L
    @Volatile private var lastNoShellLogMs = 0L
    private val noShellStreak = AtomicInteger(0)

    /** Total START dispatches since process start — shown on the Hotspot page as "relances". */
    private val dispatched = AtomicInteger(0)

    /** Monotonic probe id, so a stale/late probe callback is ignored instead of acting on
     *  out-of-date state. Atomic: bumped from the heartbeat thread, from MAIN, and from the
     *  `hotspot-keeper` scheduler, and `++` on a volatile is a read-modify-write. */
    private val probeGeneration = AtomicInteger(0)

    /** Monotonic launch id, so a confirmation belonging to a superseded dispatch is dropped
     *  instead of logging a bogus verdict. Atomic for the same reason. */
    private val dispatchGeneration = AtomicInteger(0)

    /** Single daemon thread owning only the delayed confirmation re-probes and the safety
     *  release. Created on first use. */
    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hotspot-keeper").apply { isDaemon = true }
        }
    }

    /** Shared no-op token for passes nobody is waiting on (heartbeat, confirmation re-probes). */
    private val NO_TOKEN = OneShot(null)

    /** True if the user enabled the CONTINUOUS keep-alive ("watchdog / always on"). The boot/ACC-on
     *  one-shot is a separate, independent pref owned by `BootReceiver`, and the Hotspot page binds
     *  each switch to its own pref — so this is nobody's business but ours. */
    private fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.PREF_HOTSPOT_WATCHDOG, false)

    /** START dispatches since process start (the Hotspot page shows it as the "relances" count). */
    fun dispatchedStartCount(): Int = dispatched.get()

    /** Milliseconds until the keeper is allowed to attempt another start (0 = right now). Measured
     *  from the last ATTEMPT, so the Hotspot page shows a real countdown even on a unit where no
     *  route has ever succeeded — it used to read "relance #1, due now" permanently there. */
    fun msUntilNextAttempt(): Long = HotspotKeeperPolicy.msUntilNextRestart(
        SystemClock.elapsedRealtime(), lastAttemptMs,
        HotspotKeeperPolicy.retryIntervalMs(unconfirmedStreak.get()))

    /** Called from the ProxyKeeperService heartbeat. Cheap no-op when the pref is off, throttled,
     *  a probe is in flight, or TetherFi is not installed. */
    @JvmStatic
    fun maybeKeepAlive(ctx: Context) {
        pass(ctx, reason = null, done = NO_TOKEN)
    }

    /**
     * Run ONE keep-alive pass right now: "probe now, start only if DOWN". Skips the probe cadence
     * and the retry cadence — ACC-on must not wait up to 60 s — but never the dedupe rule.
     *
     * Contract: [onDone] runs EXACTLY ONCE, on whichever path finishes first (TetherFi missing,
     * probe verdict, launch finished, internal failure) or after [PASS_TIMEOUT_MS] — so a caller
     * holding a `BroadcastReceiver.goAsync()` token can never leak it. Never throws.
     */
    fun runImmediatePass(ctx: Context, reason: String, onDone: Runnable? = null) {
        val done = OneShot(onDone)
        var safetyArmed = false
        try {
            safetyArmed = armSafetyRelease(done)
            pass(ctx, reason, done)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "$reason: keep-alive pass failed: ${t.message}")
            done.run()
        } finally {
            // No safety timer means nothing else is guaranteed to fire: release now rather than
            // risk never releasing the caller's token.
            if (!safetyArmed) done.run()
        }
    }

    /**
     * One keep-alive pass. [reason] is the single switch between the two entry points: `null` =
     * routine heartbeat (gated on the watchdog pref, throttled), non-null = immediate
     * boot / ACC-on / user pass (never throttled, always logs its outcome).
     */
    private fun pass(ctx: Context, reason: String?, done: OneShot) {
        val forced = reason != null
        if (!forced && !isEnabled(ctx)) {
            done.run()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!forced) {
            if (probeInFlight && now - lastProbeMs > PROBE_STUCK_MS) {
                AppLogger.w(TAG, "keep-alive probe stuck ${now - lastProbeMs}ms — force-reset")
                probeInFlight = false
                probeGeneration.incrementAndGet() // invalidate the stuck probe's callback
            }
            if (probeInFlight ||
                !HotspotKeeperPolicy.isProbeDue(now, lastProbeMs, PROBE_INTERVAL_MS)) {
                done.run()
                return
            }
        }
        val app = ctx.applicationContext
        if (!tetherFiInstalled(app)) {
            if (forced) AppLogger.i(TAG, "$reason: TetherFi is not installed — nothing to start")
            done.run()
            return
        }
        if (forced) {
            // A boot / ACC-on / user pass is a fresh start: back to the first rung of the ladder,
            // so the next failure retries in 60 s and not in 5 min.
            unconfirmedStreak.set(0)
            AppLogger.i(TAG, "$reason: immediate keep-alive pass")
        }
        runProbe(app, reason, done, null)
    }

    /**
     * One `dumpsys` round trip. [confirm] non-null marks it as a post-dispatch confirmation
     * re-probe: same command, same bookkeeping, but its result produces a verdict and never
     * launches anything itself.
     */
    private fun runProbe(app: Context, reason: String?, done: OneShot, confirm: ConfirmContext?) {
        lastProbeMs = SystemClock.elapsedRealtime()
        probeInFlight = true
        val gen = probeGeneration.incrementAndGet()
        ShellGateway.execShellWithResult(app, PROBE_CMD, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                if (gen != probeGeneration.get()) { // superseded by a newer probe / a stuck-reset
                    done.run()
                    return
                }
                probeInFlight = false
                // The shell answered: arm the rate limiter afresh so a LATER outage is reported
                // immediately instead of being swallowed by the previous outage's window.
                if (noShellStreak.get() != 0) {
                    AppLogger.i(TAG, "probe available again after ${noShellStreak.get()} "
                        + "shell-less probe(s)")
                    noShellStreak.set(0)
                    lastNoShellLogMs = 0L
                }
                val up = out != null && out.contains("UP")
                // Same round trip, no extra cost: the process snapshot this probe carries is the
                // BEFORE half of the next dispatch's evidence, and the AFTER half of the dispatch
                // a confirmation re-probe belongs to.
                val pid = HotspotKeeperPolicy.pidSnapshot(out)
                lastProbePid = pid
                if (confirm != null) {
                    reportConfirmation(confirm, up, pid, null)
                    done.run()
                    return
                }
                if (up) {
                    unconfirmedStreak.set(0) // healthy: a later failure starts from rung 0
                    if (reason != null) AppLogger.i(TAG, "$reason: TetherFi already UP — no launch")
                    done.run()
                    return
                }
                tryLaunch(app, reason, routesFor(app), done)
            }

            override fun onError(err: String?) {
                if (gen != probeGeneration.get()) { // superseded by a newer probe / a stuck-reset
                    done.run()
                    return
                }
                probeInFlight = false
                // Nothing was measured: forget the previous snapshot rather than let a pid read
                // minutes ago masquerade as this dispatch's baseline.
                lastProbePid = HotspotKeeperPolicy.PID_UNKNOWN
                if (confirm != null) {
                    reportConfirmation(confirm, null, HotspotKeeperPolicy.PID_UNKNOWN, err)
                    done.run()
                    return
                }
                if (reason == null) {
                    // Rate-limited: on a shell-less unit this fires on EVERY cadence probe
                    // (~120/h) and would bury the launch verdicts this build exists to produce
                    // in the very journal the bug report ships. One line per NO_SHELL_LOG_MS is
                    // enough to establish the state; the count says how long it has lasted.
                    val now = SystemClock.elapsedRealtime()
                    val since = now - lastNoShellLogMs
                    val n = noShellStreak.incrementAndGet()
                    if (lastNoShellLogMs == 0L || since >= NO_SHELL_LOG_MS) {
                        lastNoShellLogMs = now
                        AppLogger.w(TAG, "probe unavailable (no shell) — cannot determine TetherFi "
                            + "state, retrying on the normal cadence (occurrence #$n): $err")
                    }
                    done.run()
                    return
                }
                // No daemon binder AND no reachable ADB transport (a documented DL5.1 state).
                // Launch anyway, in-app and blind: that is EXACTLY what BootReceiver's deleted
                // +7 s startActivity did, it needs no shell at all, and at this one moment a
                // redundant start is far cheaper than no hotspot. Routine passes never come here.
                AppLogger.w(TAG, "$reason: probe unavailable (no shell) — blind in-app START, "
                    + "state unknown: $err")
                tryLaunch(app, reason, listOf(Route.APP), done)
            }
        })
    }

    /**
     * The launch decision — the ONE place the dedupe rule and the retry cadence are applied, for
     * BOTH entry points. Runs on the `shell-gateway` callback thread, never on main.
     */
    private fun tryLaunch(app: Context, reason: String?, routes: List<Route>, done: OneShot) {
        // The pref can flip between a probe being issued and its verdict landing (~1 s of shell
        // round trip). Never pop the tile for a user who just switched the keeper off.
        if (reason == null && !isEnabled(app)) {
            done.run()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // A forced pass bypasses the retry CADENCE. It never bypasses the dedupe below.
        if (reason == null && !HotspotKeeperPolicy.isRestartDue(now, lastAttemptMs,
                HotspotKeeperPolicy.retryIntervalMs(unconfirmedStreak.get()))) {
            done.run()
            return
        }
        val claim = claimDispatch(now)
        if (claim == NO_CLAIM) {
            // Which half refused it: "in flight" carries no useful timestamp, and `now - 0` would
            // print the time since boot.
            val why = if (HotspotKeeperPolicy.isDedupeClear(now, lastAttemptMs, DEDUPE_MS)) {
                "a launch is already in flight"
            } else {
                "one was issued ${now - lastAttemptMs}ms ago"
            }
            AppLogger.i(TAG, "${label(reason)}: TetherFi DOWN but $why — skipped (dedupe)")
            done.run()
            return
        }
        launch(app, reason, routes, claim, done)
    }

    /**
     * THE dedupe rule: "do not launch the tile if a launch was already issued less than
     * [DEDUPE_MS] ago, or is being issued right now". One rule, one place, both entry points.
     *
     * It is expressed on ATTEMPTS, not on arms — dedupe'ing arms is what let a cold-process boot
     * produce dispatch #1 from the heartbeat and dispatch #2 from the boot arm 2 s later. The claim
     * is what makes an attempt visible: [lastAttemptMs] is stamped HERE, the moment the keeper
     * commits to launching, not when a route finally reports success seconds later.
     *
     * Returns the claim token (the stamp), or [NO_CLAIM] when the launch is refused. The token is
     * what [launch] gives back, by CAS, so a stuck thread waking up long after its claim was
     * re-taken cannot release the claim of the sequence that replaced it.
     *
     * Both timelines it must hold for:
     *
     * (a) COLD PROCESS at ACC-on — the case the old code got wrong. `lastAttemptMs == 0`.
     *     T+0.8 s heartbeat pass → probe → DOWN → cadence due (lastAttemptMs 0) → dedupe clear →
     *              claim taken, `lastAttemptMs` stamped → launch sequence starts.
     *     T+3.0 s BootReceiver pass → probe → DOWN (the tile has not raised the AP yet) → forced,
     *              so no cadence check → claimDispatch: `lastAttemptMs` is ~2 s old (and the claim
     *              is still held) → REFUSED, logged.
     *     ⇒ ONE popup. Under the old code: two, 13.9 s apart.
     *
     * (b) ALREADY-RUNNING PROCESS at ACC-on, hotspot up all night. `lastAttemptMs` is STILL 0
     *     (nothing ever needed a restart) — which is exactly why an arm-based dedupe could not
     *     help here either.
     *     heartbeat pass → probe → UP → streak reset, no launch.
     *     T+3.0 s BootReceiver pass → probe → UP → "TetherFi already UP — no launch".
     *     ⇒ ZERO popups, the owner's literal requirement (check, start only if OFF).
     */
    private fun claimDispatch(now: Long): Long {
        if (!HotspotKeeperPolicy.isDedupeClear(now, lastAttemptMs, DEDUPE_MS)) return NO_CLAIM
        val stamp = now.coerceAtLeast(1L) // 0 means "free"; elapsedRealtime can be 0 at boot
        val held = launchClaimMs.get()
        if (held != NO_CLAIM) {
            if (now - held < LAUNCH_STUCK_MS) return NO_CLAIM
            // The launch thread never came back (see [LAUNCH_STUCK_MS]). Re-take the claim rather
            // than let one wedged shell call end the keep-alive for the life of the process.
            AppLogger.w(TAG, "keep-alive launch claim stuck ${now - held}ms — force-reclaimed "
                + "(a launch thread never returned; the keeper would otherwise stay silent)")
            if (!launchClaimMs.compareAndSet(held, stamp)) return NO_CLAIM
        } else if (!launchClaimMs.compareAndSet(NO_CLAIM, stamp)) {
            return NO_CLAIM
        }
        // An ATTEMPT, committed here and not on success: a unit where no route ever succeeds must
        // still climb the retry ladder instead of re-attempting on every 30 s probe.
        lastAttemptMs = stamp
        unconfirmedStreak.incrementAndGet()
        return stamp
    }

    /**
     * Try [routes] in order, each AT MOST ONCE, stopping at the first that reports success. Runs on
     * its own short-lived thread: both shell routes block, and the caller is ShellGateway's single
     * `shell-gateway` worker, shared with the cluster hot path (overscan / pidof) where a
     * multi-second stall is a real DiLink 3 regression risk.
     */
    private fun launch(app: Context, reason: String?, routes: List<Route>, claim: Long,
                       done: OneShot) {
        // Read on the probe-callback thread, microseconds after the DOWN verdict that sent us here:
        // the process snapshot that probe carried is this dispatch's BEFORE half.
        val pidBefore = lastProbePid
        val body = Runnable {
            try {
                for (route in routes) {
                    // A wedged thread whose claim was force-reclaimed ([LAUNCH_STUCK_MS]) must not
                    // keep launching: the replacement sequence has already dispatched, so issuing
                    // the next route here would draw a second tile seconds after the first — the
                    // double popup, back through the one door the reclaim opened. Re-checked every
                    // iteration because the wedge can end at any point in the loop.
                    if (launchClaimMs.get() != claim) {
                        AppLogger.w(TAG, "keep-alive launch sequence superseded while blocked "
                            + "(claim reclaimed) — abandoning before route=${route.tag}")
                        return@Runnable
                    }
                    when (attemptRoute(app, route)) {
                        Outcome.STARTED -> {
                            armConfirmation(app, commitDispatch(reason, route, pidBefore))
                            return@Runnable
                        }
                        // May already have drawn the tile: re-issuing the same non-idempotent
                        // `am start` on the next route is precisely the double popup coming back.
                        // One rung of the ladder (60 s) is the cheaper mistake.
                        Outcome.UNCERTAIN -> return@Runnable
                        // Provably never reached the activity manager: the next route is safe.
                        Outcome.FAILED -> Unit
                    }
                }
                AppLogger.w(TAG, "TetherFi START: every launch route failed (tried "
                    + routes.joinToString("/") { it.tag } + ")"
                    + (if (routes.contains(Route.APP)) "" else " — the in-app route was not "
                        + "offered: grant 'Display over other apps' or open the Hotspot page")
                    + " — retrying on the normal cadence")
            } finally {
                // CAS, not set(): if this thread wedged long enough for its claim to be re-taken
                // ([LAUNCH_STUCK_MS]), it must not release the sequence that replaced it.
                launchClaimMs.compareAndSet(claim, NO_CLAIM)
                done.run()
            }
        }
        try {
            Thread(body, "hotspot-tile-launch").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "TetherFi START thread not started: ${t.message}")
            launchClaimMs.compareAndSet(claim, NO_CLAIM)
            done.run()
        }
    }

    /** What this route did with the tile Intent. Never throws. */
    private fun attemptRoute(app: Context, route: Route): Outcome = when (route) {
        // Called DIRECTLY (as UpdateChecker's `am start` already is), never through ShellGateway:
        // the gateway re-runs the identical command over legacy ADB whenever the daemon call
        // throws, and an `am start` that draws a ProxyTileActivity is NOT idempotent — that replay
        // is one attempt and two popups.
        Route.DAEMON -> amStart(route) {
            // ThreadLocal opt-out, on THIS short-lived thread: turns callWithRetry's
            // reconnect-and-replay into a fail-fast, the last path that could run it twice.
            ProxyClient.setNonBlockingReconnect(true)
            if (!ProxyClient.isConnected()) throw NotSentException("daemon not connected")
            ProxyClient.runShell(AM_START_TILE_CMD)
        }

        // The same uid-2000 command over local ADB, so a unit whose daemon is down but whose ADB
        // works still starts the hotspot. Its own single attempt — never a gateway retry.
        Route.ADB -> amStart(route) {
            if (AdbLocalClient.isAdbTransportUnreachable()) {
                throw NotSentException(AdbLocalClient.adbTransportDiagnosis())
            }
            AdbLocalClient.executeShellWithResultBlocking(app, AM_START_TILE_CMD)
        }

        Route.APP -> try {
            app.startActivity(tileIntent())
            Outcome.STARTED
        } catch (t: Throwable) {
            // startActivity throwing means the Intent was never handed over: nothing was drawn.
            AppLogger.w(TAG, "TetherFi START route=app failed: ${t.message}")
            Outcome.FAILED
        }
    }

    /**
     * Runs one `am start` variant and grades it. The grading is what closes the last double-popup
     * door: only a failure that PROVABLY sent nothing lets the sequence fall through to the next
     * route. A [NotSentException] is thrown by the route itself before any command leaves the
     * process (no daemon binder / no ADB transport — the COMMON failures), whereas a call that
     * threw after the command went out may well have drawn the tile already, and re-issuing it on
     * the next route would draw a second one ~1 s later.
     *
     * The residual costs, both deliberate and both bounded:
     *  - a failure that in truth sent nothing (an IO error mid-connect that the transport pre-check
     *    did not catch) is graded [Outcome.UNCERTAIN] too, so that ONE attempt loses its remaining
     *    routes; the ladder retries 60 s later, by which time the pre-check sees the dead transport
     *    and the sequence falls through normally.
     *  - the in-app route can still be suppressed SILENTLY (Android 10 aborts a background activity
     *    start without throwing), so [Outcome.STARTED] from `route=app` means "handed over", not
     *    "drawn" — which is precisely what the launch evidence in [reportConfirmation] measures.
     */
    private fun amStart(route: Route, exec: () -> String?): Outcome {
        val out = try {
            exec()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            val msg = t.message ?: t.javaClass.simpleName
            if (t is NotSentException) {
                AppLogger.w(TAG, "TetherFi START route=${route.tag} unavailable: $msg")
                return Outcome.FAILED
            }
            AppLogger.w(TAG, "TetherFi START route=${route.tag} failed with the command already "
                + "sent — the tile MAY have been drawn, so no other route is tried for this "
                + "attempt; retrying on the normal cadence: $msg")
            return Outcome.UNCERTAIN
        }
        if (HotspotKeeperPolicy.amStartFailed(out)) {
            // `am` answered: the command ran and refused to start anything. Nothing was drawn.
            AppLogger.w(TAG, "TetherFi START route=${route.tag} refused by am: ${oneLine(out)}")
            return Outcome.FAILED
        }
        return Outcome.STARTED
    }

    /**
     * The in-app route is offered only when it can plausibly work: Android's
     * background-activity-start restriction lets it through with the overlay permission, and just
     * as validly with a resumed DashCast Activity — the "open the Hotspot page once" recovery the
     * deleted in-page watchdog provided. The uid-2000 routes need neither.
     */
    private fun routesFor(app: Context): List<Route> {
        val overlay = try {
            Settings.canDrawOverlays(app)
        } catch (t: Throwable) {
            false
        }
        val foreground = try {
            ProxyWatchdog.isAppForeground()
        } catch (t: Throwable) {
            false
        }
        return if (overlay || foreground) listOf(Route.DAEMON, Route.ADB, Route.APP)
        else listOf(Route.DAEMON, Route.ADB)
    }

    /**
     * The SUCCESS half of the bookkeeping: the "relance #N" counter the Hotspot page shows and the
     * confirmation context, committed only once a route actually reported the Intent accepted — a
     * launch that never happened must not be counted as one to the user.
     *
     * The CADENCE half ([lastAttemptMs], the retry streak) is deliberately NOT here: it is stamped
     * at claim time in [claimDispatch], because a unit where every route fails must still climb the
     * ladder. Committing both on success is what pinned the ladder at rung 0 and turned a
     * total-failure unit into 120 launch sequences an hour.
     */
    private fun commitDispatch(reason: String?, route: Route, pidBefore: String): ConfirmContext {
        AppLogger.i(TAG, "TetherFi DOWN → START dispatched (route=${route.tag}, "
            + "${label(reason)}, relance #${dispatched.incrementAndGet()})")
        return ConfirmContext(route.tag, dispatchGeneration.incrementAndGet(), pidBefore)
    }

    /** Schedule the "did it actually work?" re-probe. */
    private fun armConfirmation(app: Context, confirm: ConfirmContext) {
        try {
            scheduler.schedule({
                // Superseded by a newer dispatch: stay silent rather than log a verdict about a
                // dead attempt. Deliberately NOT gated on isEnabled — a boot/ACC-on pass runs on
                // the autostart pref alone, and that is precisely the case we need evidence for.
                if (confirm.gen == dispatchGeneration.get()) {
                    runProbe(app, null, NO_TOKEN, confirm)
                }
            }, CONFIRM_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "confirmation re-probe not scheduled: ${t.message}")
        }
    }

    /**
     * The verdict for the confirmation re-probe. [up] is `null` when the re-probe itself could not
     * run, which is a third, distinct outcome: not "it failed", but "we still don't know".
     *
     * THE diagnostic lives here. The confirmation re-probe is the SAME "is the AP up" dumpsys, so
     * "NOT confirmed" is byte-identical whether (i) the tile Activity never launched or (ii) it
     * launched fine and TetherFi declined to raise the AP while the car is parked — and that is the
     * question the field failure turns on. [HotspotKeeperPolicy.classifyLaunch] answers it from the
     * process snapshot the two probes already carry (no probe of its own), and says INCONCLUSIVE
     * out loud whenever it cannot: an over-confident verdict here would exonerate the launch path
     * on a car where it is in fact the culprit.
     */
    private fun reportConfirmation(confirm: ConfirmContext, up: Boolean?, pidAfter: String,
                                   err: String?) {
        if (confirm.gen != dispatchGeneration.get()) return
        val at = CONFIRM_DELAY_MS / 1000
        if (up == true) {
            unconfirmedStreak.set(0) // a confirmed launch resets the retry ladder
            AppLogger.i(TAG, "tile-launch confirmed (route=${confirm.route}) at +${at}s")
            return
        }
        if (up == null) {
            AppLogger.w(TAG, "tile-launch NOT confirmed (route=${confirm.route}) at +${at}s "
                + "— state unknown — no shell: $err")
            return
        }
        val evidence = when (HotspotKeeperPolicy.classifyLaunch(confirm.pidBefore, pidAfter)) {
            HotspotKeeperPolicy.LaunchEvidence.PROCESS_STARTED ->
                "tile DID run (TetherFi's process was created by this dispatch), " +
                    "TetherFi did not raise the AP"

            HotspotKeeperPolicy.LaunchEvidence.NO_PROCESS ->
                "tile did NOT run — no TetherFi process before or after (launch suppressed, " +
                    "unless TetherFi started and died inside ${at}s)"

            HotspotKeeperPolicy.LaunchEvidence.PROCESS_PREDATES ->
                "INCONCLUSIVE — TetherFi's process was alive since BEFORE this dispatch, so its " +
                    "presence proves nothing, and a NO_HISTORY tile leaves no record by +${at}s"

            HotspotKeeperPolicy.LaunchEvidence.NO_SNAPSHOT ->
                "INCONCLUSIVE — no usable process snapshot for this dispatch"
        }
        AppLogger.w(TAG, "tile-launch NOT confirmed (route=${confirm.route}) at +${at}s — "
            + "$evidence [pid before=${pidLabel(confirm.pidBefore)} after=${pidLabel(pidAfter)}]")
    }

    /** Renders a pid snapshot for a log line, so "" is not read as a missing field. */
    private fun pidLabel(pid: String): String = when {
        pid == HotspotKeeperPolicy.PID_UNKNOWN -> "not-measured"
        pid.isEmpty() -> "none"
        else -> pid
    }

    /** Release [done] even if every callback is lost. Returns false when nothing was scheduled. */
    private fun armSafetyRelease(done: OneShot): Boolean = try {
        scheduler.schedule({ done.run() }, PASS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        true
    } catch (t: Throwable) {
        AppLogger.w(TAG, "keep-alive pass safety release not scheduled: ${t.message}")
        false
    }

    private fun tileIntent(): Intent = Intent().apply {
        setClassName(TF_PKG, TF_TILE_CLS)
        putExtra(TF_KEY_ACTION, TF_ACTION_START)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
    }

    @Suppress("DEPRECATION")
    private fun tetherFiInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TF_PKG, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun label(reason: String?): String = reason ?: "keep-alive"

    /** Keeps `am` output to one readable, bounded log line. */
    private fun oneLine(out: String?): String {
        val s = out?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
        return if (s.length <= 160) s else s.substring(0, 160) + "…"
    }

    /** One dispatch's identity plus the process snapshot taken just BEFORE it — the baseline the
     *  +8 s re-probe is diffed against ([HotspotKeeperPolicy.classifyLaunch]). */
    private data class ConfirmContext(val route: String, val gen: Int, val pidBefore: String)

    /** Thrown by a route BEFORE anything leaves the process, so [amStart] can tell "nothing was
     *  sent, the next route may safely re-issue" from "sent, reply lost". */
    private class NotSentException(message: String) : IllegalStateException(message)

    /** Releases a caller's completion token exactly once, whichever async path finishes first. */
    private class OneShot(private val target: Runnable?) {
        private val fired = AtomicBoolean(false)
        fun run() {
            val t = target ?: return
            if (!fired.compareAndSet(false, true)) return
            try {
                t.run()
            } catch (ignore: Throwable) {
            }
        }
    }
}

package com.byd.dashcast.proxy

import android.content.Context
import android.os.SystemClock

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger
import com.byd.dashcast.util.concurrent.BoundedSerialExecutor

import java.util.concurrent.RejectedExecutionException
import java.util.regex.Pattern

/**
 * ShellGateway — drop-in replacement for [AdbLocalClient.executeShell] and
 * [AdbLocalClient.executeShellWithResult] that routes a shell command through the uid-2000 proxy
 * daemon when one is usable.
 *
 * This header described a different program until the audit's second-opinion pass. It named a
 * `beta_proxy_enabled` flag that exists nowhere in this codebase, pointed at a
 * `DaemonEngineGateway#safeCall` that was deleted in 4a36a748 ("promote proxy daemon to main path
 * — de-beta"), and promised a flag-off branch "bit-for-bit identical to the v1.0.1 behaviour". A
 * reader therefore believed in a kill switch, on the layer every shell command in this app passes
 * through. Nothing in the code answers to that name.
 *
 * Routing is decided by [ShellGatewayRoutingPolicy.select], per call, twice — once before queueing
 * and again on the worker, because the daemon can connect or die in between:
 *  - **PROXY** — the legacy path is not forced AND [ProxyClient.isConnected]. Tries a typed
 *    Phase-4 verb first when the command matches a known pattern (`wm overscan L,T,R,B -d N` or
 *    `wm overscan reset -d N`), which returns in single-digit ms; otherwise
 *    [ProxyClient.runShell]; and [AdbLocalClient] as the last-resort fallback inside the same
 *    worker.
 *  - **LEGACY** — [AdbLocalClient] directly. Selected when [DaemonConfig.isLegacyPathEnabled]
 *    forces it, or simply when no daemon is connected. This is the branch the old text called
 *    "flag OFF"; the flag it named is not what chooses it.
 *  - **FAIL_FAST** — no daemon AND the ADB transport is already classified unreachable. The
 *    callback gets [AdbLocalClient.adbTransportDiagnosis] instead of queueing behind a wedged
 *    worker. The old text did not mention this state at all.
 *
 * Two guards run BEFORE any routing and refuse the command outright — neither was documented here
 * either: [AdbLocalClient.blockDiLink2Resize] (a `wm` resize that would shrink the head unit's own
 * UI on DiLink 2), and a `wm` command explicitly targeting display 0.
 *
 * Migration target: any production call site of `AdbLocalClient.executeShell*` that runs on the
 * cluster hot path (overscan, pidof polling, app launch helpers). Diagnostic / test / settings
 * call sites keep using [AdbLocalClient] directly, because they need to exercise the legacy path.
 *
 * Kotlin port note: every regex below ends in a `\$` ANCHOR. In Kotlin a bare `$` starts a string
 * template, so each one is escaped. Worth stating because the usual "does this file contain a
 * dollar trap" grep — `$` followed by an identifier character — reports ZERO here: these dollars
 * are followed by a closing quote, not by a name.
 *
 * @since v1.1.9 build 172 — phase 3 (call-site migration).
 * @since v1.1.9 build 174 — phase 4a (typed `wm overscan` interception).
 */
object ShellGateway {

    private const val TAG = "ShellGateway"

    /** Dedicated bounded serial executor: preserves order without retaining unlimited stale work. */
    private const val SHELL_QUEUE_CAPACITY = 64
    private val sExecutor = BoundedSerialExecutor(SHELL_QUEUE_CAPACITY) { r ->
        val t = Thread(r, "shell-gateway")
        t.isDaemon = true
        t
    }

    /**
     * Matches `wm overscan L,T,R,B -d N` with optional spaces. Capture groups: 1=L, 2=T, 3=R,
     * 4=B, 5=displayId. Anchored on both ends so a compound command (e.g.
     * `wm overscan … && wm size …`) falls through to the generic shell path.
     */
    private val WM_OVERSCAN: Pattern = Pattern.compile(
            "^\\s*wm\\s+overscan\\s+(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s+-d\\s+(\\d+)\\s*\$")

    /** Matches `wm overscan reset -d N`. Capture group 1 = displayId. */
    private val WM_OVERSCAN_RESET: Pattern = Pattern.compile(
            "^\\s*wm\\s+overscan\\s+reset\\s+-d\\s+(\\d+)\\s*\$")

    /**
     * HARD GUARD — matches any `wm overscan|size|density ... -d 0` (or `-d 0` anywhere in a `wm`
     * command). Display 0 is the head unit and must NEVER be resized by this app (would shrink
     * the main UI, field-reported on DL2 22/05/2026, mirror risk on DL3/DL5 if a caller
     * accidentally passes the wrong displayId). Blocked at the gateway so neither the proxy path
     * nor the legacy path can reach the system.
     */
    private val WM_DISPLAY_ZERO: Pattern = Pattern.compile(
            "^\\s*wm\\s+(?:overscan|size|density)\\b.*\\s-d\\s+0\\b.*\$")

    /**
     * Matches `pidof <packageName>` — capture group 1 = package. Anchored so multi-word
     * invocations (e.g. `pidof a b c`) fall through to shell. The package character class
     * matches Android package names and binary names (letters, digits, dot, underscore, colon,
     * dash).
     */
    private val PIDOF: Pattern = Pattern.compile(
            "^\\s*pidof\\s+([A-Za-z0-9._:\\-]+)\\s*\$")

    /** Fire-and-forget shell. Mirrors [AdbLocalClient.executeShell]. */
    @JvmStatic
    fun execShell(ctx: Context?, cmd: String?) {
        execShellWithResult(ctx, cmd, null)
    }

    /**
     * Shell with callback. Mirrors [AdbLocalClient.executeShellWithResult].
     * The callback is invoked on a background thread (same as the legacy method).
     */
    @JvmStatic
    fun execShellWithResult(ctx: Context?, cmd: String?, cb: AdbLocalClient.Callback?) {
        if (ctx == null || cmd == null) {
            cb?.onError("null ctx/cmd")
            return
        }
        // DL2 SAFETY GUARD — must be checked BEFORE the proxy path, which bypasses
        // AdbLocalClient.executeShell* and would otherwise let a `wm overscan/size/density`
        // through to the BYD MTK ROM (which silently applies it to display 0 and shrinks the
        // main UI). See AdbLocalClient.blockDiLink2Resize.
        if (AdbLocalClient.blockDiLink2Resize(ctx, cmd)) {
            cb?.onError("blocked on DiLink 2: no cluster display (would shrink main screen)")
            return
        }
        // HARD GUARD — refuse any wm verb explicitly targeting display 0 (head unit).
        // Defence in depth on top of the call-site `clusterId > 0` checks.
        if (WM_DISPLAY_ZERO.matcher(cmd).matches()) {
            AppLogger.e(TAG, "BLOCKED wm verb on display 0 (head unit): " + cmd)
            cb?.onError("blocked: wm command targets display 0 (head unit)")
            return
        }
        // The legacy path still runs on the same bounded worker. Handing it to AdbLocalClient's
        // async pool here would drain this queue into that pool's unbounded work queue.
        val legacyPath = DaemonConfig.isLegacyPathEnabled(ctx)
        val initialRoute = ShellGatewayRoutingPolicy.select(
                legacyPath,
                !legacyPath && ProxyClient.isConnected(),
                AdbLocalClient.isAdbTransportUnreachable())
        if (initialRoute == ShellGatewayRoutingPolicy.Route.FAIL_FAST) {
            deliverError(cb, AdbLocalClient.adbTransportDiagnosis())
            return
        }
        val operation = Runnable {
            val route = ShellGatewayRoutingPolicy.select(
                    legacyPath,
                    !legacyPath && ProxyClient.isConnected(),
                    AdbLocalClient.isAdbTransportUnreachable())
            if (route != ShellGatewayRoutingPolicy.Route.PROXY) {
                runLegacyOrFailFast(ctx, cmd, cb)
                return@Runnable
            }
            // This dedicated single thread has its own legacy fallback (below), so it must never
            // pay the ~23s blocking daemon bootstrap inside callWithRetry when a binder dies
            // mid-transact — that would stall every queued overscan/pidof op. Opt out: the
            // reconnect is kicked async and the verb fails fast into the legacy fallback.
            ProxyClient.setNonBlockingReconnect(true)
            val t0 = SystemClock.elapsedRealtime()
            try {
                // Phase 4a/4b: try typed verb first. If it matches AND succeeds, we skip the
                // shell entirely. If the parse fails OR the typed call throws, we fall through
                // to runShell (then legacy).
                val typed = tryTypedVerb(cmd, t0)
                if (typed != null) {
                    deliverSuccess(cb, typed)
                    return@Runnable
                }
                val out = ProxyClient.runShell(cmd)
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.d(TAG, "beta runShell ok (" + dt + "ms): " + cmd)
                // ProxyProcessVerbs.runShell never returns null (it throws instead), which the
                // Kotlin signature now states — the old null branch was unreachable.
                deliverSuccess(cb, out.trim())
            } catch (t: Throwable) {
                if (t is InterruptedException) Thread.currentThread().interrupt()
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.w(TAG, "beta runShell failed after " + dt + "ms, fallback legacy: "
                        + t.message + " [cmd=" + cmd + "]")
                runLegacyOrFailFast(ctx, cmd, cb)
            }
        }
        try {
            sExecutor.execute(operation)
        } catch (queueFull: RejectedExecutionException) {
            AppLogger.e(TAG, "shell queue full (capacity=" + SHELL_QUEUE_CAPACITY
                    + "), rejecting cmd=" + cmd)
            deliverError(cb, "shell queue full")
        }
    }

    private fun runLegacyOrFailFast(ctx: Context, cmd: String, cb: AdbLocalClient.Callback?) {
        // A healthy Binder does not depend on the local ADB socket after startup. Only stop at
        // the point where a command would actually fall back to that classified-dead transport.
        // ProxyKeeper owns periodic rechecks and clears the classification after recovery.
        if (AdbLocalClient.isAdbTransportUnreachable()) {
            deliverError(cb, AdbLocalClient.adbTransportDiagnosis())
            return
        }
        runLegacyBlocking(ctx, cmd, cb)
    }

    private fun runLegacyBlocking(ctx: Context, cmd: String, cb: AdbLocalClient.Callback?) {
        try {
            deliverSuccess(cb, AdbLocalClient.executeShellWithResultBlocking(ctx, cmd))
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            val message = t.message ?: t.javaClass.simpleName
            AppLogger.e(TAG, "legacy shell failed: " + message + " [cmd=" + cmd + "]")
            deliverError(cb, message)
        }
    }

    private fun deliverSuccess(cb: AdbLocalClient.Callback?, output: String?) {
        if (cb == null) return
        try { cb.onSuccess(output) }
        catch (callbackError: Throwable) {
            AppLogger.e(TAG, "shell success callback failed", callbackError)
        }
    }

    private fun deliverError(cb: AdbLocalClient.Callback?, error: String?) {
        if (cb == null) return
        try { cb.onError(error) }
        catch (callbackError: Throwable) {
            AppLogger.e(TAG, "shell error callback failed", callbackError)
        }
    }

    /**
     * Inspect `cmd` and, if it matches a Phase 4 verb pattern, route it through the typed binder
     * call instead of the shell. Returns the stdout payload to hand to the caller's callback on
     * success, or `null` when the command didn't match any pattern OR the typed call failed —
     * callers fall through to the generic `runShell` path on `null`.
     *
     * Currently handles:
     *  - `wm overscan L,T,R,B -d N` → [ProxyClient.setOverscan] (payload: `""`)
     *  - `wm overscan reset -d N`   → [ProxyClient.setOverscan](N,0,0,0,0) (payload: `""`)
     *  - `pidof <pkg>`              → [ProxyClient.getPidsByPackage] (payload: space-separated PIDs)
     */
    private fun tryTypedVerb(cmd: String, t0: Long): String? {
        val m = WM_OVERSCAN.matcher(cmd)
        if (m.matches()) {
            try {
                val l = m.group(1)!!.toInt()
                val t = m.group(2)!!.toInt()
                val r = m.group(3)!!.toInt()
                val b = m.group(4)!!.toInt()
                val d = m.group(5)!!.toInt()
                ProxyClient.setOverscan(d, l, t, r, b)
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.d(TAG, "beta setOverscan typed ok (" + dt + "ms): d=" + d
                        + " " + l + "," + t + "," + r + "," + b)
                return ""
            } catch (th: Throwable) {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.w(TAG, "beta setOverscan typed failed after " + dt
                        + "ms, falling through to runShell: " + th.message)
                return null
            }
        }
        val mr = WM_OVERSCAN_RESET.matcher(cmd)
        if (mr.matches()) {
            try {
                val d = mr.group(1)!!.toInt()
                // `wm overscan reset` clears overscan = setOverscan(d, 0, 0, 0, 0).
                ProxyClient.setOverscan(d, 0, 0, 0, 0)
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.d(TAG, "beta setOverscan(reset) typed ok (" + dt + "ms): d=" + d)
                return ""
            } catch (th: Throwable) {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.w(TAG, "beta setOverscan(reset) typed failed after " + dt
                        + "ms, falling through to runShell: " + th.message)
                return null
            }
        }
        val mp = PIDOF.matcher(cmd)
        if (mp.matches()) {
            try {
                val pkg = mp.group(1)
                // Success is not logged: pidof is fired by the 5 s display-state poll, and a log
                // line per call kept the AppLogger buffer dirty (see DisplayStatePollCoordinator).
                // Failures below stay logged.
                return ProxyClient.getPidsByPackage(pkg)
            } catch (th: Throwable) {
                val dt = SystemClock.elapsedRealtime() - t0
                AppLogger.w(TAG, "beta pidof typed failed after " + dt
                        + "ms, falling through to runShell: " + th.message)
                return null
            }
        }
        return null
    }
}

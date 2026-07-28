package com.byd.dashcast.cluster.display

import android.content.Context

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.proxy.ShellGateway
import com.byd.dashcast.util.AppLogger

/**
 * Enumerates the LOGICAL displays through the uid-2000 daemon instead of the app process.
 *
 * WHY — DiLink 4.0 only (see [ClusterDisplayInfo] for the full evidence).
 * The OEM patched `DisplayManagerService.getDisplayIdsInternal` with an app whitelist that
 * DashCast is not on, so `DisplayManager.getDisplays()` in OUR process returns nothing: 68
 * `findClusterDisplay` calls over 80 s logged ZERO candidates in INC-20260727-203241 while a
 * uid-2000 shell dump 29 s later listed `Display 1: fission_bg_xdjaVirtualSurface`. The daemon
 * is demonstrably not gated — it is what produces the `--- DISPLAYS ---` section of every bug
 * report — so on DL4 it is the source of truth.
 *
 * DELIBERATELY NO NEW BINDER VERB. This reuses the existing [ShellGateway] path (typed proxy
 * verb → daemon `runShell` → legacy ADB relay), so an OLDER daemon left over from a previous
 * APK keeps working unchanged: `dumpsys display` is a plain shell command, not a new protocol.
 *
 * Threading: [enumerate] never blocks the caller. The callback is delivered on the shell
 * worker thread — hop to your own looper before touching activation state.
 */
object DaemonDisplayEnumerator {

    private const val TAG = "DaemonDisplayEnum"

    /**
     * One `DisplayInfo{...}` line per logical display (DMS prints mBaseDisplayInfo then
     * mOverrideDisplayInfo). Grepping keeps the payload at ~2 lines instead of the whole
     * multi-kB `dumpsys display`, which matters because this runs on the activation hot path.
     * `DisplayDeviceInfo{` does NOT contain the substring `DisplayInfo{`, so physical-device
     * records are filtered out by the grep itself.
     */
    private const val DUMP_CMD = "dumpsys display 2>/dev/null | grep 'DisplayInfo{'"

    /** Separator inside the quoted header: `"fission_bg_xdjaVirtualSurface, displayId 1"`. */
    private const val DISPLAY_ID_SEP = ", displayId "

    /** `, real 1920 x 720` — anchored on ", real " so `app`/`largest app` sizes are not matched. */
    private val REAL_SIZE = Regex(", real (\\d+) x (\\d+)")

    /** `, layerStack 1` — the value SurfaceDaemon needs to composite this display. */
    private val LAYER_STACK = Regex(", layerStack (\\d+)")

    /** `, state ON` / `, state OFF` — DMS power state of the logical display. */
    private val STATE = Regex(", state (\\w+)")

    /**
     * `owner com.dudu.autoui (uid 10071)` — printed for VIRTUAL displays only. Physical
     * displays carry no owner, which is why [ClusterDisplayInfo.OWNER_UID_UNKNOWN] exists.
     */
    private val OWNER = Regex("owner (\\S+) \\(uid (\\d+)\\)")

    /** Marker for a VirtualDisplay that only accepts its own owner's windows. */
    private const val FLAG_PRIVATE = "FLAG_PRIVATE"

    /**
     * Result of one enumeration.
     *
     * [shellOk] distinguishes "the daemon answered and the firmware has no cluster display"
     * from "the shell never answered". Only the former is evidence about the firmware; the
     * caller must not turn a transport failure into a "projection unsupported" verdict.
     */
    fun interface Callback {
        fun onResult(displays: List<ClusterDisplayInfo>, shellOk: Boolean)
    }

    /**
     * Asks the daemon for the logical display list. Fire-and-forget; [cb] runs on the shell
     * worker thread. Any failure (queue full, transport down, unparseable output) is reported
     * as an empty list so callers simply fall back to their existing behaviour.
     */
    @JvmStatic
    fun enumerate(ctx: Context, cb: Callback) {
        ShellGateway.execShellWithResult(ctx, DUMP_CMD, object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) {
                val displays = parseDisplayDump(out)
                AppLogger.d(TAG, "daemon enumeration → ${displays.size} logical display(s): $displays")
                cb.onResult(displays, true)
            }

            override fun onError(err: String?) {
                // NOT a firmware verdict — the shell itself failed (transport classified dead,
                // queue full, daemon cold). Reported as shellOk=false so the caller keeps
                // treating the outcome as "unknown" rather than "unsupported".
                AppLogger.w(TAG, "daemon enumeration failed: $err")
                cb.onResult(emptyList(), false)
            }
        })
    }

    /**
     * Parses the grepped `dumpsys display` output into one entry per logical display.
     *
     * Pure function (unit-tested): no Android types, no I/O. Unparseable input yields an empty
     * list, which every caller treats as "not found".
     */
    @JvmStatic
    fun parseDisplayDump(dump: String?): List<ClusterDisplayInfo> {
        if (dump.isNullOrEmpty()) return emptyList()
        val out = ArrayList<ClusterDisplayInfo>()
        val seen = HashSet<Int>()
        for (line in dump.lineSequence()) {
            val infoAt = line.indexOf("DisplayInfo{")
            if (infoAt < 0) continue
            val q0 = line.indexOf('"', infoAt)
            if (q0 < 0) continue
            val q1 = line.indexOf('"', q0 + 1)
            if (q1 < 0) continue
            // Header is `<name>, displayId <n>`. lastIndexOf because a display NAME may itself
            // contain a comma; the id suffix is always last.
            val header = line.substring(q0 + 1, q1)
            val sep = header.lastIndexOf(DISPLAY_ID_SEP)
            if (sep < 0) continue
            val id = header.substring(sep + DISPLAY_ID_SEP.length).trim().toIntOrNull() ?: continue
            // DMS prints mBaseDisplayInfo BEFORE mOverrideDisplayInfo for the same id. Keep the
            // first: the override carries app-visible (rotated / inset) values, the base carries
            // the panel's real geometry, which is what the mirror and the launch bounds want.
            if (!seen.add(id)) continue
            val name = header.substring(0, sep)
            val size = REAL_SIZE.find(line)
            val width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
            // Fallback layerStack = displayId: the same convention already used by
            // ClusterMirrorManager and ClusterShotRecorder when the hidden getter is unavailable.
            val layerStack = LAYER_STACK.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: id
            // Ownership / privacy / power state — DISCARDING these is what let a foreign app's
            // private PiP surface be mistaken for the cluster; see pickCluster.
            val owner = OWNER.find(line)
            val ownerPackage = owner?.groupValues?.get(1)
            val ownerUid = owner?.groupValues?.get(2)?.toIntOrNull()
                    ?: ClusterDisplayInfo.OWNER_UID_UNKNOWN
            out.add(ClusterDisplayInfo(
                id, name, width, height, layerStack,
                isPrivate = line.contains(FLAG_PRIVATE),
                ownerUid = ownerUid,
                ownerPackage = ownerPackage,
                state = STATE.find(line)?.groupValues?.get(1) ?: ""
            ))
        }
        return out
    }

    /**
     * Picks the cluster out of [displays].
     *
     * The NAMING rule is the app-side one (`ClusterManager.findClusterDisplay`: skip display 0,
     * prefer a known cluster name, else the first non-default display) — but the INPUT is not the
     * same, and that difference is a real hazard:
     *
     *  • `DisplayManager.getDisplays()` in the app process never exposes another app's PRIVATE
     *    VirtualDisplay, so the app-side "first non-default display" fallback can only ever land
     *    on something we are allowed to drive.
     *  • `dumpsys display` as uid 2000 lists EVERY logical display, foreign private ones included.
     *
     * Proven topology (INC-20260619-222520 / -222619): the ONLY non-default logical display was
     * `dudu-auto-ui-pip-…, real 1284x990, layerStack 1, state OFF,
     * owner com.dudu.autoui (uid 10071), FLAG_PRIVATE`. The naive fallback returned it — i.e. on
     * exactly the "this firmware has no cluster display" case, DashCast would have run
     * `wm overscan reset -d 1` on a foreign app's private display, scaled touch injection to
     * 1284x990, mirrored a powered-OFF surface and force-launched the user's app onto it, while
     * the honest "unavailable on this firmware" verdict never fired because a display was
     * "found". `com.dudu.autoui` appears in four tester reports.
     *
     * So the daemon side filters what the app side gets for free:
     *  1. never display 0;
     *  2. never FLAG_PRIVATE (a private VD only accepts its owner's windows — it can never show
     *     the user's app, so "found" would be a lie in every case);
     *  3. never owned by a third-party uid (>= 10000). The BYD cluster is owned by
     *     `com.xdja.containerservice (uid 1000)` on every capture we hold;
     *  4. a known cluster name (xdja / fission) wins outright;
     *  5. otherwise the first eligible non-default display is accepted only if DMS did not report
     *     it as powered OFF.
     *
     * Deliberately conservative: returning null here yields the honest "no cluster display on
     * this firmware" message, which is strictly better than driving somebody else's surface.
     *
     * Pure function (unit-tested): no Android types, no logging, no I/O. What was rejected and
     * why is readable from the `display resolution — … daemon=[…]` line, which renders every
     * display's state / owner / PRIVATE flag via [ClusterDisplayInfo.toString].
     */
    @JvmStatic
    fun pickCluster(displays: List<ClusterDisplayInfo>): ClusterDisplayInfo? {
        var fallback: ClusterDisplayInfo? = null
        for (d in displays) {
            if (d.id == 0) continue
            if (d.isPrivate || d.isThirdPartyOwned()) continue // not ours to drive
            if (ClusterDisplayNames.isKnownClusterName(d.name)) return d
            if (fallback == null && !d.isStateOff()) fallback = d
        }
        return fallback
    }

    /**
     * Pure companion to [pickCluster]: the non-default displays that exist in the dump but were
     * rejected as "not a cluster we may drive". Non-empty here with a null [pickCluster] is the
     * interesting case — the firmware has no cluster for us, but it is not an empty machine.
     */
    @JvmStatic
    fun rejectedCandidates(displays: List<ClusterDisplayInfo>): List<ClusterDisplayInfo> =
        displays.filter { it.id != 0 && (it.isPrivate || it.isThirdPartyOwned() || it.isStateOff()) }

    /** Compact one-line rendering for the activation "display resolution" log. */
    @JvmStatic
    fun describe(displays: List<ClusterDisplayInfo>): String =
        displays.joinToString(", ") { it.toString() }
}

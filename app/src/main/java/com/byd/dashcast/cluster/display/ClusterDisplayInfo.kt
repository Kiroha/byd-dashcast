package com.byd.dashcast.cluster.display

import java.util.Locale

/**
 * A cluster display described by plain values instead of an [android.view.Display] object.
 *
 * WHY THIS EXISTS — DiLink 4.0 only.
 * On DL4 the OEM patched {@code DisplayManagerService.getDisplayIdsInternal} with a per-app
 * whitelist and DashCast is not on it. The car logs, for our uid, only refusals:
 * {@code getDisplayIdsInternal isPermittedApp:false} (252x), {@code forbid for:10167} (378x),
 * {@code getDisplayIdsInternal reject for:10167 to get id} (126x) — never a single grant
 * (INC-20260713-180803). The id list itself is withheld, so in the app process
 * {@code DisplayManager.getDisplays()} yields nothing and {@code dm.getDisplay(1)} returns null,
 * even though the cluster VirtualDisplay exists, is ON and is idle:
 * {@code Display 1: fission_bg_xdjaVirtualSurface, 1920x720, layerStack 1,
 * owner com.xdja.containerservice (uid 1000), state ON}.
 *
 * The uid-2000 daemon is NOT subject to that whitelist — it produces the whole
 * `dumpsys display` section of every bug report. So on DL4 the cluster is described by these
 * four numbers, read through the daemon, and every consumer downstream works from them
 * (SurfaceDaemon, the daemon launch verb and the input forwarder all take plain ints anyway).
 *
 * Never populated on DL3/DL5: there the real [android.view.Display] is always obtainable.
 */
data class ClusterDisplayInfo(
    /** Logical display id (`mDisplayId` / ATM `displayId`). */
    val id: Int,
    /** Display name as reported by DMS, e.g. `fission_bg_xdjaVirtualSurface`. */
    val name: String,
    /** `real` width in px. 0 when the dump did not carry one. */
    val width: Int,
    /** `real` height in px. 0 when the dump did not carry one. */
    val height: Int,
    /** SurfaceFlinger layer stack — what SurfaceDaemon needs to composite this display. */
    val layerStack: Int,
    /**
     * `FLAG_PRIVATE` was present on the `DisplayInfo{…}` line.
     *
     * A private VirtualDisplay only accepts windows from the app that created it, so it is never
     * a cluster we may drive — and unlike `DisplayManager.getDisplays()` (which never shows
     * another app's private display at all) the uid-2000 `dumpsys display` lists every one of
     * them, so the daemon path MUST filter them itself. See [DaemonDisplayEnumerator.pickCluster].
     */
    val isPrivate: Boolean = false,
    /** `owner <pkg> (uid N)` uid, or [OWNER_UID_UNKNOWN] when the dump carried no owner. */
    val ownerUid: Int = OWNER_UID_UNKNOWN,
    /** `owner <pkg> (uid N)` package, or null when the dump carried no owner. */
    val ownerPackage: String? = null,
    /** `state ON` / `state OFF` as printed by DMS; empty when the dump carried no state. */
    val state: String = ""
) {
    /**
     * True when the display belongs to an ordinary third-party app (uid >= 10000).
     *
     * The BYD cluster VirtualDisplay is owned by `com.xdja.containerservice (uid 1000)` on every
     * capture; a `com.dudu.autoui (uid 10071)` PiP surface is not ours to touch.
     */
    fun isThirdPartyOwned(): Boolean =
        ownerUid != OWNER_UID_UNKNOWN && ownerUid >= FIRST_APPLICATION_UID

    /** True when DMS explicitly reported the display as powered OFF. */
    fun isStateOff(): Boolean = state.equals("OFF", ignoreCase = true)

    override fun toString(): String {
        val sb = StringBuilder("id=$id name=$name ${width}x$height layerStack=$layerStack")
        if (state.isNotEmpty()) sb.append(" state=").append(state)
        if (ownerPackage != null) sb.append(" owner=").append(ownerPackage).append('/').append(ownerUid)
        if (isPrivate) sb.append(" PRIVATE")
        return sb.toString()
    }

    companion object {
        /** Sentinel for "the dump carried no `owner …` field" (physical displays never do). */
        const val OWNER_UID_UNKNOWN = -1

        /** `android.os.Process.FIRST_APPLICATION_UID`, inlined to keep this file Android-free. */
        const val FIRST_APPLICATION_UID = 10000
    }
}

/**
 * Single source of truth for the cluster-display naming heuristic.
 *
 * Extracted so the app-side [ClusterManager.findClusterDisplay] and the daemon-side
 * [DaemonDisplayEnumerator] cannot drift apart: the brief requires the daemon path to reuse
 * the EXISTING matching rather than invent new criteria.
 */
object ClusterDisplayNames {

    /**
     * Returns true if [name] matches a known BYD cluster VirtualDisplay pattern.
     * DL3/DL4: "fission_bg_xdjaVirtualSurface", DL5: "XDJAScreenProjection_0/1".
     */
    @JvmStatic
    fun isKnownClusterName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("xdja") || lower.contains("fission")
    }
}

/**
 * Process-wide holder for the cluster geometry resolved through the uid-2000 daemon.
 *
 * WHY A REGISTRY RATHER THAN THREADING THE VALUE THROUGH.
 * The Display object is consumed in six places spread across Java and Kotlin, and every one of
 * those signatures ALREADY accepts a nullable Display. Widening six signatures would touch the
 * DL3/DL5 call paths; a registry that only DL4 ever writes leaves them reading `null` and
 * therefore behaving bit-for-bit as today. Read it as "extra information available when, and
 * only when, the Display object could not be obtained".
 *
 * Written on the main thread (ClusterManager's activation handler), read from the touch
 * dispatcher / mirror / launch threads — hence @Volatile.
 */
object ClusterDisplayRegistry {

    @Volatile
    private var sInfo: ClusterDisplayInfo? = null

    /** Publishes the geometry resolved through the daemon. DL4 activation is the only writer. */
    @JvmStatic
    fun set(info: ClusterDisplayInfo?) {
        sInfo = info
    }

    /** Last resolved cluster geometry, or null (always null on DL3/DL5). */
    @JvmStatic
    fun get(): ClusterDisplayInfo? = sInfo

    /**
     * Same as [get] but only when the entry actually describes [displayId]. Guards against a
     * stale entry from a previous activation being applied to a different display.
     */
    @JvmStatic
    fun forDisplayId(displayId: Int): ClusterDisplayInfo? {
        val info = sInfo
        return if (info != null && info.id == displayId) info else null
    }

    /** Cleared when projection goes down, so nothing downstream reuses a dead display. */
    @JvmStatic
    fun clear() {
        sInfo = null
    }
}

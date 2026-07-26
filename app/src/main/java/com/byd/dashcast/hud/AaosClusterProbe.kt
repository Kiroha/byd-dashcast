package com.byd.dashcast.hud

import android.content.Context

/**
 * AAOS-only experiments to reach the instrument cluster on Android Automotive BYD
 * head units (DX_BYD_AUTO), where a standard app launch reaches the *logical*
 * cluster display (Display 1) but not the *physical* panel — only the OEM nav does.
 *
 * Two levers, both observed in the car's own data:
 *  1. **Vendor VHAL command props** (`INS_*` / `INSTRUMENT_*`, suffix `_S`, access
 *     WRITE) — likely how the OEM nav switches the cluster mode. Written via
 *     `CarPropertyManager.setIntProperty`.
 *  2. **Automotive display-proxy HAL** (`IAutomotiveDisplayProxyService`) — owns the
 *     physical cluster panel surface; probe whether we can even reach it.
 *
 * EVERYTHING here is reflection on `android.car.*` / `android.frameworks.automotive.*`,
 * which **do not exist on DiLink 3 / 5** — `Class.forName` throws there and is caught,
 * so these are pure no-ops on DiLink (and are only ever invoked behind the
 * FEATURE_AUTOMOTIVE-gated diagnostic bench). No daemon / no behaviour change.
 *
 * Credit: cluster API direction informed by Open-BYD (MIT) — https://github.com/SergioRt1/Open-BYD
 */
object AaosClusterProbe {

    /** Candidate writable cluster-control VHAL properties seen on DX_BYD_AUTO (areaId 0 = global). */
    val CANDIDATES: List<Pair<String, Int>> = listOf(
        "INS_NAV_DISPLAY_MODE_S"     to 0x2140396e,
        "INSTRUMENT_MENU_SHOW_STATE" to 0x2140601a,
        "INS_CENTER_DISPLAYS"        to 0x21403973,
        "SWITCH_DISPLAY_MODE"        to 0x21406f21
    )

    fun isAaos(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature("android.hardware.type.automotive")

    /**
     * Write a BYD vendor VHAL int property via CarPropertyManager (reflection).
     * Creates a short-lived Car connection; returns a human-readable result/error.
     */
    fun setCarIntProperty(ctx: Context, propId: Int, areaId: Int, value: Int): String {
        return try {
            val carClass = Class.forName("android.car.Car")
            val car = carClass.getMethod("createCar", Context::class.java).invoke(null, ctx)
                ?: return "createCar() returned null — car service unavailable"
            try {
                val cpm = carClass.getMethod("getCarManager", String::class.java).invoke(car, "property")
                    ?: return "getCarManager(\"property\") returned null"
                val setIntProperty = cpm.javaClass.getMethod(
                    "setIntProperty",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                setIntProperty.invoke(cpm, propId, areaId, value)
                "setIntProperty(0x%08X, area=%d, %d) → OK".format(propId, areaId, value)
            } finally {
                try { carClass.getMethod("disconnect").invoke(car) } catch (_: Throwable) { /* ignore */ }
            }
        } catch (t: Throwable) {
            val c = t.cause ?: t
            "setIntProperty(0x%08X, %d) FAILED: %s: %s".format(propId, value, c.javaClass.simpleName, c.message)
        }
    }

    /** Probe whether we can reach the automotive display-proxy HAL (owns the cluster panel). */
    fun probeDisplayProxy(): String {
        val sb = StringBuilder()
        val cn = "android.frameworks.automotive.display.V1_0.IAutomotiveDisplayProxyService"
        sb.appendLine("probe $cn")
        try {
            val cls = Class.forName(cn)
            sb.appendLine("  class present ✓")
            val svc = try {
                cls.getMethod("getService").invoke(null)
            } catch (t: Throwable) {
                val c = t.cause ?: t
                sb.appendLine("  getService() FAILED: ${c.javaClass.simpleName}: ${c.message}")
                null
            }
            if (svc != null) {
                sb.appendLine("  getService() ✓ → $svc")
                for (m in listOf("getDisplayIdList", "getDisplayInfoList")) {
                    try {
                        val r = svc.javaClass.getMethod(m).invoke(svc)
                        sb.appendLine("  $m() → $r")
                    } catch (t: Throwable) {
                        sb.appendLine("  $m() n/a: ${(t.cause ?: t).message}")
                    }
                }
            }
        } catch (t: Throwable) {
            sb.appendLine("  class NOT present: ${t.javaClass.simpleName} (expected on DiLink)")
        }
        return sb.toString()
    }
}

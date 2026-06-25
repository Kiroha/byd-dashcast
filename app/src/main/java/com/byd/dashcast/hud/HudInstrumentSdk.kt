package com.byd.dashcast.hud

import android.content.Context
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager

/**
 * In-app reflection wrapper for the **dedicated high-level SDK methods** of
 * `android.hardware.bydauto.instrument.BYDAutoInstrumentDevice`
 * (`sendAutoNaviStatus`, `sendSimpleGuidanceInfo`, `sendNextPathName`,
 * `sendRestRouteInfo`).
 *
 * These are distinct from the raw feature-ID `set(int[], BYDAutoEventValue)` path
 * DashCast uses elsewhere. Open-BYD's working DL5.1 recipe calls BOTH the register
 * write AND `device.sendAutoNaviStatus(2)` — the latter is what we never did, and is
 * the prime suspect for "writes accepted (rc=0) but cluster renders nothing".
 *
 * Runs in-app (DashCast holds BYDAUTO_INSTRUMENT_COMMON); hidden-API access is
 * exempted first via [ClusterMirrorManager.unlockHiddenApis] (same as the scraper).
 */
object HudInstrumentSdk {

    private const val DEVICE_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

    private fun device(ctx: Context): Any {
        try { ClusterMirrorManager.unlockHiddenApis() } catch (_: Throwable) { /* best effort */ }
        val cls = Class.forName(DEVICE_CLASS)
        return cls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
            ?: throw IllegalStateException("BYDAutoInstrumentDevice.getInstance() returned null")
    }

    fun sendAutoNaviStatus(ctx: Context, status: Int): Int {
        val d = device(ctx)
        return d.javaClass.getMethod("sendAutoNaviStatus", Int::class.javaPrimitiveType)
            .invoke(d, status) as Int
    }

    fun sendSimpleGuidanceInfo(ctx: Context, simpleType: Int, distance: Int): Int {
        val d = device(ctx)
        return d.javaClass.getMethod("sendSimpleGuidanceInfo",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(d, simpleType, distance) as Int
    }

    fun sendNextPathName(ctx: Context, name: String): Int {
        val d = device(ctx)
        return d.javaClass.getMethod("sendNextPathName", String::class.java)
            .invoke(d, name) as Int
    }

    fun sendRestRouteInfo(ctx: Context, restHour: Int, restMinute: Int, restMileage: Long): Int {
        val d = device(ctx)
        return d.javaClass.getMethod("sendRestRouteInfo",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(d, restHour, restMinute, restMileage) as Int
    }
}

package com.byd.dashcast.hud

import android.content.Context
import com.byd.dashcast.cluster.mirror.ClusterMirrorManager

/**
 * Reads BYD instrument/setting feature values back (the `get` counterpart of the
 * `set` we use elsewhere) — to learn the value the OEM applies for HUD nav.
 *
 * The exact read API isn't documented and the framework stubs aren't in the APK, so
 * this is best-effort + self-describing: it looks for a `get(int[])` method on the
 * device, reads the returned `BYDAutoEventValue[].intValue`, and — if `get(int[])`
 * isn't found — lists the available `get*` method signatures so the on-car run tells
 * us the real API.
 */
object HudStateReader {

    const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"
    const val INSTRUMENT_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"

    private fun device(ctx: Context, cls: String): Any {
        try { ClusterMirrorManager.unlockHiddenApis() } catch (_: Throwable) {}
        return Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, ctx)
            ?: throw IllegalStateException("$cls.getInstance() returned null")
    }

    /** Reads [ids] from [cls] and returns a human-readable report (values or the discovered API). */
    fun read(ctx: Context, cls: String, ids: IntArray): String {
        val sb = StringBuilder()
        try {
            val d = device(ctx, cls)
            val getter = d.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterTypes.size == 1 && it.parameterTypes[0] == IntArray::class.java
            }
            if (getter == null) {
                sb.append("no get(int[]) on ${cls.substringAfterLast('.')} — get* methods: ")
                sb.append(d.javaClass.methods
                    .filter { it.name.startsWith("get") }
                    .joinToString(", ") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
                    .take(900))
                return sb.toString()
            }
            val res = getter.invoke(d, ids)
            if (res is Array<*>) {
                val intField = res.firstOrNull { it != null }?.javaClass?.getField("intValue")
                for (i in ids.indices) {
                    val ev = res.getOrNull(i)
                    val v = try { intField?.getInt(ev) } catch (_: Throwable) { null }
                    sb.append("0x%08X = %s\n".format(ids[i], v?.toString() ?: "?"))
                }
            } else {
                sb.append("get(int[]) returned ${res?.javaClass?.name}: $res\n")
            }
        } catch (t: Throwable) {
            sb.append("${cls.substringAfterLast('.')} read error: ${t.javaClass.simpleName}: ${t.message}\n")
        }
        return sb.toString()
    }
}

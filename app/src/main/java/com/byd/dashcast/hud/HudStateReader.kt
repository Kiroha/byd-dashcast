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
    const val SETTING_LISTENER = "android.hardware.bydauto.setting.AbsBYDAutoSettingListener"
    const val INSTRUMENT_LISTENER = "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener"

    /**
     * Reflects a class STRUCTURE only (no instance, no method call) — so it needs NO BYD
     * permission (unlike [read]/[describeReadApi], which call getInstance and hit the
     * BYDAUTO_*_COMMON SecurityException). Used to discover the push-feedback LISTENER API:
     * is the AbsBYDAuto*Listener abstract-with-mandatory-methods? does it expose
     * onFeatureChanged / onHUD* / a no-arg ctor? what is registerListener's param type?
     */
    fun describeClass(name: String, keywords: List<String>): String {
        return try {
            val c = Class.forName(name)
            val sb = StringBuilder()
            sb.append("$name\n")
            sb.append("  super=${c.superclass?.name} abstract=${java.lang.reflect.Modifier.isAbstract(c.modifiers)}\n")
            sb.append("  ctors: ").append(
                c.declaredConstructors.joinToString { ct -> "(" + ct.parameterTypes.joinToString { it.simpleName } + ")" }
            ).append('\n')
            val abstracts = c.declaredMethods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            sb.append("  ABSTRACT methods (${abstracts.size}): ").append(
                abstracts.joinToString { m -> m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" }.take(900)
            ).append('\n')
            val rel = c.declaredMethods.filter { m -> keywords.any { m.name.contains(it, ignoreCase = true) } }
            sb.append("  matching [${keywords.joinToString()}]: ").append(
                rel.joinToString { m -> m.name + "(" + m.parameterTypes.joinToString { it.simpleName } + ")" }.take(1200)
            ).append('\n')
            sb.toString()
        } catch (t: Throwable) {
            "$name describe-class error: ${t.javaClass.name}: ${t.message}\n"
        }
    }

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
            try {
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
            } catch (ite: java.lang.reflect.InvocationTargetException) {
                // The real reason is the CAUSE (the wrapped exception), not the ITE itself.
                val c = ite.cause
                sb.append("get(int[]) threw ${c?.javaClass?.name ?: "?"}: ${c?.message}\n")
            }
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            sb.append("${cls.substringAfterLast('.')} read error: ${c.javaClass.name}: ${c.message}\n")
        }
        return sb.toString()
    }

    /** Lists the device's `get*` method signatures + its granted-permission-relevant info — once. */
    fun describeReadApi(ctx: Context, cls: String): String {
        return try {
            val d = device(ctx, cls)
            "${cls.substringAfterLast('.')} get* methods: " +
                d.javaClass.methods.filter { it.name.startsWith("get") }
                    .joinToString(", ") { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" }
                    .take(1200)
        } catch (t: Throwable) {
            val c = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            "${cls.substringAfterLast('.')} describe error: ${c.javaClass.name}: ${c.message}"
        }
    }
}

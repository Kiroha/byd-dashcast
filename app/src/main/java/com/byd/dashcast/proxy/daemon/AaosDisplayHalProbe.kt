package com.byd.dashcast.proxy.daemon

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * AaosDisplayHalProbe — the definitive test of whether app windows can be drawn to the AAOS
 * instrument-cluster panel via the automotive display proxy HAL
 * (`android.frameworks.automotive.display@1.0/1.1::IAutomotiveDisplayProxyService`).
 *
 * On DX_BYD_AUTO the cluster panel is NOT a scan-out of logical display 1 — it is rendered by
 * the AAOS cluster pipeline, and the ONLY way to draw arbitrary content to it is the HAL's
 * `IGraphicBufferProducer`. This probe checks, step by step, whether we can reach it:
 *  1. is the HIDL Java interface even present?
 *  2. `getService()` — can we obtain the HAL binder? (SELinux / hwservicemanager gate)
 *  3. `getHGraphicBufferProducer(displayId)` — can we get a buffer producer for the panel?
 *
 * A valid producer ⇒ projection is technically feasible (drawing is then "just" Surface work).
 * Any failure (absent / null / denied) ⇒ definitively closed.
 *
 * Pure reflection (no compile-time deps) so it runs both in-app and in the daemon (uid 2000)
 * for comparison. READ-ONLY: it never calls `showWindow` / posts buffers, so it cannot
 * disrupt the OEM cluster nav. Safe no-op on non-AAOS (the HIDL class is simply ABSENT).
 */
object AaosDisplayHalProbe {

    @JvmStatic
    fun probe(): String {
        val sb = StringBuilder()
        sb.append("uid=").append(android.os.Process.myUid())
            .append(" pid=").append(android.os.Process.myPid()).append('\n')
        for (ver in arrayOf("V1_1", "V1_0")) {
            val cn = "android.frameworks.automotive.display." + ver + ".IAutomotiveDisplayProxyService"
            sb.append("== ").append(cn).append(" ==\n")
            val c: Class<*> = try {
                Class.forName(cn)
            } catch (t: Throwable) {
                sb.append("  class: ABSENT (").append(t.javaClass.simpleName).append(")\n")
                continue
            }
            sb.append("  class: present\n")
            val svc: Any? = try {
                getService(c)
            } catch (t: Throwable) {
                sb.append("  getService: FAILED ").append(root(t)).append('\n')
                continue
            }
            if (svc == null) {
                sb.append("  getService: null (HAL not registered, or SELinux-denied)\n")
                continue
            }
            sb.append("  getService: OK → ").append(svc.javaClass.name).append('\n')
            val gp: Method? = try {
                c.getMethod("getHGraphicBufferProducer", Long::class.javaPrimitiveType)
            } catch (ignore: Throwable) {
                null
            }
            if (gp == null) {
                sb.append("  getHGraphicBufferProducer(long): method ABSENT\n")
            } else {
                for (did in longArrayOf(1L, 0L, 2L)) {
                    try {
                        val prod = gp.invoke(svc, did)
                        sb.append("  getHGraphicBufferProducer(").append(did).append("): ")
                            .append(
                                if (prod == null)
                                    "null"
                                else
                                    ("OK → " + prod.javaClass.name
                                        + "  ⇒ a buffer producer to the panel EXISTS (projection feasible)")
                            )
                            .append('\n')
                    } catch (t: Throwable) {
                        sb.append("  getHGraphicBufferProducer(").append(did).append("): FAILED ")
                            .append(root(t)).append('\n')
                    }
                }
            }
        }
        return sb.toString()
    }

    /** Tries the common HIDL `getService` overloads; lets the real invocation error propagate. */
    @Throws(Throwable::class)
    private fun getService(c: Class<*>): Any? {
        val sigs: Array<Array<Class<*>?>> = arrayOf(
            arrayOf(),
            arrayOf(Boolean::class.javaPrimitiveType),
            arrayOf(String::class.java),
            arrayOf(String::class.java, Boolean::class.javaPrimitiveType)
        )
        for (params in sigs) {
            val m: Method = try {
                c.getMethod("getService", *params)
            } catch (nsme: NoSuchMethodException) {
                continue
            }
            val args = arrayOfNulls<Any>(params.size)
            for (i in params.indices) {
                args[i] = if (params[i] == String::class.java) "default" else true
            }
            return m.invoke(null, *args)
        }
        throw NoSuchMethodException("no getService overload on " + c.name)
    }

    private fun root(t: Throwable): String {
        val r: Throwable = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return r.javaClass.name + ": " + r.message
    }
}

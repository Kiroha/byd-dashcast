package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.Display
import android.view.Surface

import com.byd.dashcast.util.concurrent.DeathLease

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase4DisplayVerbs — display-management verbs that run inside the daemon process (uid 2000).
 *
 * Covers three concern groups:
 *  1. **IWindowManager** — overscan insets via the `window` service.
 *  2. **VirtualDisplay** — create / release VDs on behalf of the app (Phase 5).
 *  3. **ATM display policy** — `setDisplayToSingleTaskInstance`,
 *     `isActivityStartAllowedOnDisplay`.
 *
 * @see Phase4ProcessVerbs
 * @see Phase4TaskVerbs
 * @since v1.1.9 build 174 — split from Phase4Verbs in v1.4.4-beta.
 */
object Phase4DisplayVerbs {

    // ─── IWindowManager cache ─────────────────────────────────────────────

    @Volatile private var sWindowManager: Any? = null
    @Volatile private var sWindowManagerBinder: IBinder? = null
    @Volatile private var sSetOverscan: Method? = null

    private val sWindowManagerDeath = IBinder.DeathRecipient {
        synchronized(Phase4DisplayVerbs::class.java) {
            sWindowManager = null
            sWindowManagerBinder = null
        }
    }

    @Throws(Throwable::class)
    private fun windowManager(): Any? {
        var cached = sWindowManagerBinder
        if (cached != null && cached.isBinderAlive) return sWindowManager
        synchronized(Phase4DisplayVerbs::class.java) {
            cached = sWindowManagerBinder
            val alive = cached
            if (alive != null && alive.isBinderAlive) return sWindowManager
            val sm = Class.forName("android.os.ServiceManager")
            val b = sm.getMethod("getService", String::class.java)
                    .invoke(null, "window") as IBinder?
                ?: throw IllegalStateException("no 'window' service")
            // \$ escape, not a Kotlin template: "IWindowManager$Stub" is a JVM nested-class
            // binary name and "Stub" is a valid Kotlin identifier, so an unescaped version
            // would try to interpolate it.
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val wm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, b)
                ?: throw IllegalStateException("IWindowManager.asInterface returned null")
            try { b.linkToDeath(sWindowManagerDeath, 0) }
            catch (t: Throwable) { return wm }
            sWindowManagerBinder = b
            sWindowManager = wm
            return wm
        }
    }

    @Throws(Throwable::class)
    private fun setOverscanMethod(): Method {
        var m = sSetOverscan
        if (m != null) return m
        synchronized(Phase4DisplayVerbs::class.java) {
            m = sSetOverscan
            val cached = m
            if (cached != null) return cached
            val iface = Class.forName("android.view.IWindowManager")
            val resolved = iface.getMethod("setOverscan",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType)
            sSetOverscan = resolved
            return resolved
        }
    }

    // ─── VirtualDisplay map ───────────────────────────────────────────────

    private val sVirtualDisplays = ConcurrentHashMap<Int, VirtualDisplayRecord>()

    private class VirtualDisplayRecord(display: VirtualDisplay) {
        private var display: VirtualDisplay? = display
        private var ownerLease: DeathLease? = null
        private var released = false

        @Synchronized
        fun setOwnerLease(lease: DeathLease) {
            if (released) lease.close() else ownerLease = lease
        }

        @Synchronized
        fun release() {
            if (released) return
            released = true
            val lease = ownerLease
            ownerLease = null
            lease?.close()
            val current = display
            display = null
            if (current != null) {
                try { current.release() } catch (ignore: Throwable) {}
            }
        }
    }

    // ─── Verbs ────────────────────────────────────────────────────────────

    /**
     * Equivalent of `wm overscan L,T,R,B -d displayId`.
     * Probe P1 (build 173) confirmed viable from uid 2000 on BYD Seal EU.
     */
    @JvmStatic
    @Throws(Throwable::class)
    fun setOverscan(displayId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val wm = windowManager()
        val m = setOverscanMethod()
        m.invoke(wm, displayId, left, top, right, bottom)
    }

    /**
     * Create a VirtualDisplay on behalf of the calling app and return its display id.
     * The daemon runs as uid 2000 (shell) — uses a `com.android.shell` package context so
     * DisplayManagerService's uid-package check passes.
     *
     * Callers MUST eventually call [releaseVirtualDisplay]; Java GC does NOT release the
     * underlying IVirtualDisplay token.
     */
    @JvmStatic
    fun createVirtualDisplay(sysCtx: Context?,
                             name: String?, width: Int, height: Int, dpi: Int,
                             surface: Surface?, flags: Int,
                             owner: IBinder?): Int {
        if (sysCtx == null) throw IllegalStateException("system context null")
        var vdName = name
        if (vdName == null || vdName.isEmpty()) vdName = "DashCast_VD"
        if (width <= 0 || height <= 0 || dpi <= 0) {
            throw IllegalArgumentException("bad geometry " + width + "x" + height + "@" + dpi)
        }
        if (surface == null || !surface.isValid) {
            throw IllegalArgumentException("surface null or invalid")
        }
        val shellCtx: Context
        try {
            shellCtx = sysCtx.createPackageContext("com.android.shell",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        } catch (nnfe: PackageManager.NameNotFoundException) {
            throw IllegalStateException("com.android.shell context unavailable", nnfe)
        }
        val dm = shellCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
            ?: throw IllegalStateException("DisplayManager null")
        val vd = dm.createVirtualDisplay(vdName, width, height, dpi, surface, flags)
            ?: throw IllegalStateException("createVirtualDisplay returned null")
        val d: Display? = vd.display
        if (d == null) {
            try { vd.release() } catch (ignore: Throwable) {}
            throw IllegalStateException("VirtualDisplay.getDisplay() null")
        }
        val id = d.displayId
        val record = VirtualDisplayRecord(vd)
        val replaced = sVirtualDisplays.put(id, record)
        replaced?.release()
        if (owner != null) {
            try {
                val lease = DeathLease.attach(BinderDeathOwner(owner)) {
                    if (sVirtualDisplays.remove(id, record)) record.release()
                }
                record.setOwnerLease(lease)
            } catch (t: Throwable) {
                sVirtualDisplays.remove(id, record)
                record.release()
                throw IllegalStateException("cannot link VirtualDisplay owner", t)
            }
        }
        return id
    }

    /** Release a VD previously created via [createVirtualDisplay]. No-op if unknown. */
    @JvmStatic
    fun releaseVirtualDisplay(displayId: Int) {
        sVirtualDisplays.remove(displayId)?.release()
    }

    /** Release every VD held by the daemon. Best-effort cleanup. */
    @JvmStatic
    fun releaseAllVirtualDisplays() {
        for (e in sVirtualDisplays.entries) {
            if (sVirtualDisplays.remove(e.key, e.value)) e.value.release()
        }
    }

    /**
     * Mark a display as single-task instance. BYD/AOSP side-effect: the display becomes
     * "system-trusted" for activity launch, bypassing the secondary-display gate that bounces
     * apps like Waze back to display 0.
     */
    @JvmStatic
    fun setDisplayToSingleTaskInstance(displayId: Int): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)
            val m = iAtm!!.javaClass.getMethod("setDisplayToSingleTaskInstance",
                    Int::class.javaPrimitiveType)
            m.invoke(iAtm, displayId)
            "OK setDisplayToSingleTaskInstance($displayId)"
        } catch (t: Throwable) {
            val c = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
            "ERR setDisplayToSingleTaskInstance: " + c.javaClass.simpleName + " — " + c.message
        }
    }

    /** Diagnostic: ask ATM if `packageName` is allowed to start on `displayId`. */
    @JvmStatic
    fun isActivityStartAllowedOnDisplay(displayId: Int, packageName: String?): String {
        return try {
            val atmCls = Class.forName("android.app.ActivityTaskManager")
            val iAtm = atmCls.getMethod("getService").invoke(null)
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setPackage(packageName)
            val m = iAtm!!.javaClass.getMethod("isActivityStartAllowedOnDisplay",
                    Int::class.javaPrimitiveType, Intent::class.java, String::class.java,
                    Int::class.javaPrimitiveType)
            val res = m.invoke(iAtm, displayId, intent, null as String?, 0)
            "isActivityStartAllowedOnDisplay($displayId,$packageName) = $res"
        } catch (t: Throwable) {
            val c = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
            "ERR isActivityStartAllowedOnDisplay: " + c.javaClass.simpleName + " — " + c.message
        }
    }
}

package com.byd.dashcast.proxy.daemon

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.os.Parcel

import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.util.Arrays

/**
 * Phase4Probes — feasibility tests for replacing {@code sh -c "wm ..." | "am ..."}
 * with direct typed Binder calls (Phase 4).
 *
 * <p>Each probe attempts a single native AOSP API call from the daemon process
 * (uid 2000, SELinux domain {@code shell}, no system signature). It returns a
 * structured status so the app side can decide which Phase 4 verbs are safe
 * to ship and which must stay on the shell path.
 *
 * <p>Status taxonomy:
 * <ul>
 *   <li>{@code PASS}            — call succeeded, return value sensible</li>
 *   <li>{@code FAIL_SECURITY}   — {@link SecurityException}, blocked by
 *       permission / SELinux → Phase 4 verb is NOT viable from uid 2000</li>
 *   <li>{@code FAIL_API}        — {@link NoSuchMethodException} /
 *       {@link ClassNotFoundException} → API signature differs on this OEM,
 *       need device-specific reflection</li>
 *   <li>{@code FAIL_NULL}       — service binder not found (SDK_INT mismatch
 *       or service renamed)</li>
 *   <li>{@code FAIL_OTHER}      — any other Throwable; detail carries class
 *       name + message for triage</li>
 * </ul>
 *
 * <p>The result format is a pipe-separated list of {@code Pn=STATUS:detail}
 * tokens, e.g. {@code "P1=PASS:setOverscan ok|P2=FAIL_SECURITY:perm WRITE_SECURE_SETTINGS"}.
 * Detail strings have any {@code |} replaced by {@code !} to keep the parser
 * trivial on the client side.
 */
object Phase4Probes {

    @JvmStatic
    fun runAll(systemContext: Context?): String {
        val out: MutableList<String> = ArrayList(13)
        out.add(probe("P1") { p1_setOverscan() })
        out.add(probe("P2") { p2_getInitialDisplaySize() })
        out.add(probe("P3") { p3_forceStopPackage() })
        out.add(probe("P4") { p4_killBackgroundProcesses() })
        out.add(probe("P5") { p5_getRunningTasks() })
        out.add(probe("P6") { p6_autoContainerSendInfo() })
        out.add(probe("P7") { p7_systemProperties() })
        out.add(probe("P8") { p8_procCmdlineScan() })
        out.add(probe("P9") { p9_listServices() })
        out.add(probe("P10") { p10_packageInstallerName(systemContext) })
        out.add(probe("P11") { p11_inputManagerProbe() })
        out.add(probe("P12") { p12_displayManagerListDisplays(systemContext) })
        out.add(probe("P13") { p13_autoContainerDirectSendInfo() })
        return joinPipe(out)
    }

    // ─── individual probes ─────────────────────────────────────────────────

    /** P1 — {@code IWindowManager.setOverscan(displayId, l, t, r, b)} signature
     *  reachability probe. Replaces the {@code wm overscan} JVM fork.
     *
     *  1.2.29 — Non-destructive: we no longer invoke setOverscan(0,…) on display 0,
     *  because on DL3/DL5 that overwrites any user-configured overscan margins to
     *  zero (audit finding). The probe now only verifies that IWindowManager is
     *  reachable AND that the setOverscan(int×5) signature is present. The real
     *  verb is exercised end-to-end by Phase4Verbs.setOverscan via the daemon path. */
    @Throws(Throwable::class)
    private fun p1_setOverscan(): String {
        val wmb = getService("window")
        if (wmb == null) return "FAIL_NULL:no window service"
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val wm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wmb)
        if (wm == null) return "FAIL_NULL:asInterface returned null"
        val iface = Class.forName("android.view.IWindowManager")
        try {
            iface.getMethod(
                "setOverscan",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:setOverscan(int,int,int,int,int) absent on API " + Build.VERSION.SDK_INT
        }
        return "PASS:setOverscan(int x5) signature reachable on API " + Build.VERSION.SDK_INT
    }

    /** P2 — read-only display size getter; if PASS we know we can read display
     *  metadata directly from IWindowManager. */
    @Throws(Throwable::class)
    private fun p2_getInitialDisplaySize(): String {
        val wmb = getService("window")
        if (wmb == null) return "FAIL_NULL:no window service"
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val wm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wmb)
        val iface = Class.forName("android.view.IWindowManager")
        val m: Method = try {
            iface.getMethod("getInitialDisplaySize", Int::class.javaPrimitiveType, Point::class.java)
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:getInitialDisplaySize absent"
        }
        val p = Point()
        m.invoke(wm, 0, p)
        return "PASS:display0 = " + p.x + "x" + p.y
    }

    /** P3 — {@code IActivityManager.forceStopPackage}. Replaces {@code am force-stop}. */
    @Throws(Throwable::class)
    private fun p3_forceStopPackage(): String {
        val am = getActivityManagerService()
        if (am == null) return "FAIL_NULL:no IActivityManager"
        val m: Method = try {
            am.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:forceStopPackage(String,int) absent"
        }
        // Target a guaranteed-nonexistent package so we never disturb a real app.
        m.invoke(am, "com.example.dashcast.phase4.does.not.exist", -1)
        return "PASS:forceStopPackage accepted"
    }

    /** P4 — {@code IActivityManager.killBackgroundProcesses}. */
    @Throws(Throwable::class)
    private fun p4_killBackgroundProcesses(): String {
        val am = getActivityManagerService()
        if (am == null) return "FAIL_NULL:no IActivityManager"
        val m: Method = try {
            am.javaClass.getMethod("killBackgroundProcesses", String::class.java, Int::class.javaPrimitiveType)
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:killBackgroundProcesses(String,int) absent"
        }
        m.invoke(am, "com.example.dashcast.phase4.does.not.exist", -1)
        return "PASS:killBackgroundProcesses accepted"
    }

    /** P5 — {@code IActivityManager.getRunningTasks(int)}. Replaces the
     *  {@code dumpsys activity recents} + regex pipeline used today. */
    @Throws(Throwable::class)
    private fun p5_getRunningTasks(): String {
        val am = getActivityManagerService()
        if (am == null) return "FAIL_NULL:no IActivityManager"
        val m: Method = try {
            am.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
        } catch (ignore: NoSuchMethodException) {
            try {
                am.javaClass.getMethod("getRunningTasks", Int::class.javaPrimitiveType)
            } catch (nse: NoSuchMethodException) {
                return "FAIL_API:no getTasks/getRunningTasks(int)"
            }
        }
        val res = m.invoke(am, 20)
        val n = if (res is List<*>) res.size else -1
        return "PASS:" + m.name + " returned " + n + " task(s)"
    }

    /** P6 — Raw transact against the {@code AutoContainer} system service used
     *  by {@code AdbLocalClient.sendInfo}. PASS would let us replace the
     *  shell wrapper entirely. */
    @Throws(Throwable::class)
    private fun p6_autoContainerSendInfo(): String {
        val b = getService("AutoContainer")
        if (b == null) return "FAIL_NULL:no AutoContainer service"
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            // Best-effort: not all OEM builds expose the same descriptor; try a
            // benign INTERFACE_TRANSACTION query first to confirm the binder is
            // alive and reachable from uid 2000.
            val ok = b.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0)
            val descr = reply.readString()
            return "PASS:AutoContainer reachable, descriptor=" + (if (descr == null) "<null>" else descr) +
                " (transact ret=" + ok + ")"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** P7 — read + write {@link android.os.SystemProperties}. PASS on read is
     *  expected; write almost certainly fails for uid 2000 (sepolicy). */
    @Throws(Throwable::class)
    private fun p7_systemProperties(): String {
        val sp = Class.forName("android.os.SystemProperties")
        val release = sp.getMethod("get", String::class.java)
            .invoke(null, "ro.build.version.release") as String?
        val writeStatus: String = try {
            sp.getMethod("set", String::class.java, String::class.java)
                .invoke(null, "debug.dashcast.probe", "1")
            "set-ok"
        } catch (t: Throwable) {
            val cause = if (t.cause != null) t.cause!! else t
            "set-fail:" + cause.javaClass.simpleName
        }
        return "PASS:read=" + release + " write=" + writeStatus
    }

    /** P8 — sanity baseline: pure Java scan of {@code /proc/<pid>/cmdline}. Should
     *  always PASS — if it fails, the daemon is heavily sandboxed and Phase 4
     *  is doomed regardless of any Binder permission. */
    @Throws(Throwable::class)
    private fun p8_procCmdlineScan(): String {
        val dirs = File("/proc").listFiles()
        if (dirs == null) return "FAIL_OTHER:/proc not readable"
        var scanned = 0
        var matched = 0
        val needle = "com.byd.dashcast"
        val buf = ByteArray(256)
        for (d in dirs) {
            val name = d.name
            if (!d.isDirectory) continue
            var numeric = true
            for (i in 0 until name.length) {
                if (name[i] < '0' || name[i] > '9') { numeric = false; break }
            }
            if (!numeric) continue
            val cmd = File(d, "cmdline")
            if (!cmd.canRead()) continue
            scanned++
            val n: Int
            try {
                n = FileInputStream(cmd).use { fis -> fis.read(buf) }
            } catch (ignore: Throwable) { continue }
            if (n <= 0) continue
            val s = String(buf, 0, n).replace(' ', ' ').trim { it <= ' ' }
            if (s.contains(needle)) matched++
        }
        return "PASS:scanned " + scanned + " procs, matched " + matched + " '" + needle + "'"
    }

    /** P9 — {@code ServiceManager.listServices()}. PASS confirms broad
     *  enumeration access (useful for runtime service discovery). */
    @Suppress("UNCHECKED_CAST")
    @Throws(Throwable::class)
    private fun p9_listServices(): String {
        val sm = Class.forName("android.os.ServiceManager")
        val m: Method = try {
            sm.getMethod("listServices")
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:listServices absent"
        }
        val res = m.invoke(null)
        val arr = res as Array<String>?
        // Cherry-pick a few we care about for Phase 4 to confirm visibility.
        val wanted: List<String> = Arrays.asList(
            "window", "activity", "package", "input",
            "display", "AutoContainer", "media_session", "audio"
        )
        val sb = StringBuilder("PASS:")
        sb.append(if (arr == null) 0 else arr.size).append(" services; ")
        val all: List<String> = if (arr == null) emptyList() else Arrays.asList(*arr)
        for (w in wanted) {
            sb.append(w).append('=').append(if (all.contains(w)) '1' else '0').append(' ')
        }
        return sb.toString().trim { it <= ' ' }
    }

    /** P10 — {@code IPackageManager.getInstallerPackageName}. Cheap read API to
     *  prove typed PM access works from uid 2000. */
    @Suppress("UNUSED_PARAMETER")
    @Throws(Throwable::class)
    private fun p10_packageInstallerName(systemContext: Context?): String {
        val pmb = getService("package")
        if (pmb == null) return "FAIL_NULL:no package service"
        val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
        val pm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, pmb)
        val iface = Class.forName("android.content.pm.IPackageManager")
        val m: Method = try {
            iface.getMethod("getInstallerPackageName", String::class.java)
        } catch (nse: NoSuchMethodException) {
            return "FAIL_API:getInstallerPackageName absent"
        }
        val res = m.invoke(pm, "com.byd.dashcast")
        return "PASS:installer=" + (if (res == null) "<null>" else res.toString())
    }

    /** P11 — Confirm {@code IInputManager} stub is reachable; injectInputEvent
     *  is the known-working path used by the existing input forwarder, so we
     *  just confirm the stub is asInterface-able from this process. */
    @Throws(Throwable::class)
    private fun p11_inputManagerProbe(): String {
        val ib = getService("input")
        if (ib == null) return "FAIL_NULL:no input service"
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, ib)
        if (im == null) return "FAIL_NULL:asInterface returned null"
        // Don't actually inject — just walk the API surface to confirm reflection works.
        val iface = Class.forName("android.hardware.input.IInputManager")
        val methods = iface.declaredMethods
        var hasInject = false
        for (m in methods) {
            if ("injectInputEvent" == m.name) { hasInject = true; break }
        }
        return "PASS:IInputManager bound, injectInputEvent " + (if (hasInject) "present" else "ABSENT") +
            " (api surface=" + methods.size + " methods)"
    }

    /** P12 — {@code DisplayManager.getDisplays()} via the system context. The
     *  daemon already holds a system Context — confirm the framework managers
     *  attached to it are usable (replaces several {@code dumpsys display} pipes). */
    @Throws(Throwable::class)
    private fun p12_displayManagerListDisplays(systemContext: Context?): String {
        if (systemContext == null) return "FAIL_NULL:no system context"
        val dm = systemContext.getSystemService(Context.DISPLAY_SERVICE)
        if (dm == null) return "FAIL_NULL:DISPLAY_SERVICE null"
        val m = dm.javaClass.getMethod("getDisplays")
        val displays = m.invoke(dm) as Array<*>
        val sb = StringBuilder("PASS:")
        sb.append(displays.size).append(" display(s):")
        for (d in displays) {
            val getId = d!!.javaClass.getMethod("getDisplayId")
            val getName = d.javaClass.getMethod("getName")
            sb.append(" ").append(getId.invoke(d)).append('/').append(getName.invoke(d))
        }
        return sb.toString()
    }

    /** P13 — direct typed {@code transact(code=2)} on the {@code AutoContainer}
     *  binder, equivalent of {@code service call AutoContainer 2 i32 1000 i32 30 s16 ""}.
     *  This is the actual production call shape used by
     *  {@code AdbLocalClient.sendInfo}. P6 confirmed only that the binder is
     *  reachable and the descriptor is {@code android.os.IAutoContainer}; P13
     *  confirms the transaction itself goes through (no signature/uid check
     *  blocking the call). The chosen payload — {@code sendInfo(1000, 30, "")} —
     *  is the idempotent "Seal EU screen size" notification used by the
     *  diagnostic dump button, so even on PASS it has no visible side effect
     *  beyond what the user already triggers when opening Diag → ADB. */
    @Throws(Throwable::class)
    private fun p13_autoContainerDirectSendInfo(): String {
        val b = getService("AutoContainer")
        if (b == null) return "FAIL_NULL:no AutoContainer service"
        // First confirm the descriptor matches what we expect — if the OEM
        // changed it, the writeInterfaceToken below would land on the wrong
        // server-side interface check and we want a clean diagnostic instead.
        val descr: String?
        val d0 = Parcel.obtain()
        val r0 = Parcel.obtain()
        try {
            b.transact(IBinder.INTERFACE_TRANSACTION, d0, r0, 0)
            descr = r0.readString()
        } finally {
            r0.recycle()
            d0.recycle()
        }
        if (descr == null || descr.isEmpty()) {
            return "FAIL_OTHER:no descriptor from AutoContainer"
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descr)
            data.writeInt(1000)  // type
            data.writeInt(30)    // info — Seal EU screen size notification
            data.writeString("") // infoStr — empty for size queries
            val ok = b.transact(2, data, reply, 0)
            // readException() throws on remote SecurityException / IllegalArgument
            // — exactly what we need to triage whether direct transact is allowed.
            reply.readException()
            return "PASS:sendInfo(1000,30,\"\") via transact(2) on " + descr +
                " (ret=" + ok + ")"
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /** Reflective {@code ServiceManager.getService(name)}. */
    @Throws(Throwable::class)
    private fun getService(name: String): IBinder? {
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }

    /** Reflective {@code ActivityManager.getService()} (API 26+) with fallback to
     *  the pre-O {@code ActivityManagerNative.getDefault()}. */
    @Throws(Throwable::class)
    private fun getActivityManagerService(): Any? {
        try {
            val am = Class.forName("android.app.ActivityManager")
            val m = am.getMethod("getService")
            return m.invoke(null)
        } catch (ignore: Throwable) {
            val amn = Class.forName("android.app.ActivityManagerNative")
            val m = amn.getMethod("getDefault")
            return m.invoke(null)
        }
    }

    private fun interface ProbeBody {
        @Throws(Throwable::class)
        fun run(): String
    }

    /** Run a single probe, normalising its outcome into the {@code Pn=STATUS:detail} grammar. */
    private fun probe(id: String, body: ProbeBody): String {
        try {
            val r = body.run()
            return id + "=" + sanitise(r)
        } catch (t: Throwable) {
            // Reflection wraps the real exception inside InvocationTargetException —
            // unwrap once so the message reflects what actually failed.
            var cause = t
            if (t is java.lang.reflect.InvocationTargetException && t.cause != null) {
                cause = t.cause!!
            }
            val cls = cause.javaClass.simpleName
            val msg = cause.message
            val cat: String
            if (cause is SecurityException) cat = "FAIL_SECURITY"
            else if (cause is NoSuchMethodException) cat = "FAIL_API"
            else if (cause is ClassNotFoundException) cat = "FAIL_API"
            else cat = "FAIL_OTHER"
            return id + "=" + cat + ":" + cls + (if (msg == null) "" else (" " + sanitise(msg)))
        }
    }

    /** Strip pipe + newline from a free-form string to keep the wire format unambiguous. */
    private fun sanitise(s: String?): String {
        if (s == null) return ""
        return s.replace('|', '!').replace('\n', ' ').replace('\r', ' ')
    }

    private fun joinPipe(parts: List<String>): String {
        val sb = StringBuilder()
        for (i in parts.indices) {
            if (i > 0) sb.append('|')
            sb.append(parts[i])
        }
        return sb.toString()
    }

    /** Parse the result string back into {@code Pn → "STATUS:detail"} pairs. Used by the
     *  test runner on the client side to attribute each token to its TestDef. */
    @JvmStatic
    fun parse(wire: String?): MutableMap<String, String> {
        val out: MutableMap<String, String> = LinkedHashMap()
        if (wire == null || wire.isEmpty()) return out
        for (tok in wire.split("|")) {
            val eq = tok.indexOf('=')
            if (eq <= 0) continue
            out[tok.substring(0, eq)] = tok.substring(eq + 1)
        }
        return out
    }

}

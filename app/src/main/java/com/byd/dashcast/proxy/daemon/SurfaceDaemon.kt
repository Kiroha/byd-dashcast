package com.byd.dashcast.proxy.daemon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager

import androidx.core.graphics.createBitmap

import com.byd.dashcast.BuildConfig
import com.byd.dashcast.cluster.display.ClusterDisplayInfo
import com.byd.dashcast.cluster.display.ClusterDisplaySelectionPolicy
import com.byd.dashcast.cluster.mirror.InputDisplayRoutingPolicy
import com.byd.dashcast.util.concurrent.BoundedSerialExecutor
import com.byd.dashcast.util.concurrent.DeathLease

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * SurfaceDaemon — the uid=2000 daemon that **HOLDS** graphical state, started via app_process.
 *
 * ### The two-daemon boundary (read this before touching either daemon)
 * DashCast drives the instrument cluster through **two** uid-2000 helper processes. They are
 * not interchangeable and they do not share a binder:
 *  - **[ProxyDaemonMain] — "DOES things".** A stateless command executor: shell (TXN_EXEC) plus
 *    typed one-shot verbs (launchAndForce, moveAndResize, setOverscan, autoContainerSendInfo…).
 *    It owns no long-lived state, so if it dies you simply retry the command. Reached from the
 *    app via `ProxyClient` / `ProxyClient.getProxyDaemonBinder()`.
 *  - **`SurfaceDaemon` (this object) — "HOLDS things".** A stateful surface/window owner. It
 *    holds the in-app preview mirror *and*, in Layout mode, the per-slot `TYPE_SYSTEM_OVERLAY`
 *    windows **ON THE CLUSTER**, the trusted VirtualDisplays created from those windows'
 *    Surfaces, the slot geometry, and touch injection. If it dies, the graphical state is lost
 *    and must be rebuilt — retrying a command is not enough. Reached from the app via
 *    `FissionClient` / `ClusterMirrorManager`, whose binder comes from
 *    `DaemonBinderResolver.surfaceDaemonBinder()`.
 *
 * Practical triage rule: **a failed command → ProxyDaemon; a black or frozen surface →
 * SurfaceDaemon**.
 *
 * The class was called `MirrorDaemon` until 1.8.x. That name implied "the in-app preview mirror"
 * and hid the second half of the job — it also owns CLUSTER surfaces (Layout slot overlays and
 * their VirtualDisplays), not only the main-screen preview. Misreading that produced a real
 * defect (a teardown that sent this daemon's DESCRIPTOR to the ProxyDaemon's binder, so the
 * cluster SurfaceControl token was never released). **Only the class identity was renamed**:
 * every on-the-wire / on-disk identifier below still says "mirror" and MUST NOT be renamed with
 * the class — a daemon spawned by an older APK keeps running across app updates, and the
 * bug-report tooling greps these strings in historical reports.
 *
 * Exposes a Binder (IMirrorDaemon) for:
 *   - TRANSACT_MIRROR_START  (1) : configure a SurfaceControl mirror of the cluster display
 *   - TRANSACT_INJECT_MOTION (2) : inject a MotionEvent on the cluster display
 *   - TRANSACT_INJECT_KEY    (3) : inject a KeyEvent
 *   - TRANSACT_MIRROR_STOP   (4) : destroy the mirror
 *   - TRANSACT 5 / 9-17           : cluster slot overlays, VirtualDisplays, capture (see below)
 *
 * The Binder is broadcast via ACTION_DAEMON_READY at startup and registered in ServiceManager
 * under "byd_mirror_daemon". Only uid=2000 can call SurfaceControl.createDisplay() and
 * InputManager.injectInputEvent() without additional permission.
 */
@Suppress("DEPRECATION")
@SuppressLint("StaticFieldLeak") // system/application contexts, daemon process-scoped, safe
object SurfaceDaemon {

    /** WIRE IDENTIFIER — do NOT rename with the class. Emitted to logcat and, via [out], as the
     *  literal "[MirrorDaemon] " prefix in /data/local/tmp/mirrordaemon_latest.log, whose tail is
     *  pasted into every bug report. Triagers grep it across historical reports. */
    private const val TAG = "MirrorDaemon"

    /** Our app's uid. Resolution is retried from the Binder gate after transient PM failures. */
    @Volatile private var sAppUid = -1

    /** True if `uid` may drive the privileged MirrorBinder verbs. */
    private fun isAllowedCaller(uid: Int): Boolean {
        var appUid = sAppUid
        if (appUid < 0) appUid = resolveAppUid()
        return DaemonCallerPolicy.isAllowed(uid, Process.myUid(), appUid)
    }

    private fun resolveAppUid(): Int {
        val context = sSysContext ?: return -1
        return try {
            val resolved = context.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0)
            sAppUid = resolved
            resolved
        } catch (t: Throwable) {
            out("app uid resolution failed; privileged app calls remain denied: " + t.message)
            -1
        }
    }

    // Actions broadcast
    /** WIRE IDENTIFIER — do NOT rename with the class (registered receiver in MainActivity, and
     *  emitted by daemons built from older APKs). */
    const val ACTION_DAEMON_READY = "com.byd.dashcast.MIRROR_DAEMON_READY"

    // Interface Binder — wire-protocol ID, must stay stable across package moves:
    // a daemon spawned by an older APK build keeps running across app updates and
    // enforces this exact token in onTransact(). WIRE IDENTIFIER — do NOT rename with the class.
    const val DESCRIPTOR = "com.byd.dashcast.daemon.IMirrorDaemon"
    const val TRANSACT_MIRROR_START = 1
    const val TRANSACT_INJECT_MOTION = 2
    const val TRANSACT_INJECT_KEY = 3
    const val TRANSACT_MIRROR_STOP = 4

    // ── Fission slot transacts (purely additive — transacts 1-4 unchanged) ──
    // 6-8 reserved (wire-format gap matching devtools numbering)
    /** TRANSACT 5 — create default full-screen overlay+VD (legacy CLUSTER_ATTACH).
     *
     *  RESERVED, no client. handleClusterAttach is dispatched below but nothing in the repo
     *  sends transaction 5. The code stays allocated rather than reused: a daemon survives an APK
     *  reinstall, so a rebuilt app talking to an old daemon must never find a different meaning
     *  behind a number it already knows. */
    const val TRANSACT_CLUSTER_ATTACH = 5
    /** TRANSACT 9 — resize a named slot overlay+VD in-place. */
    const val TRANSACT_RESIZE_SLOT = 9
    /** TRANSACT 10 — create a named overlay+VD slot for one app at a given rect. */
    const val TRANSACT_ATTACH_SLOT = 10
    /** TRANSACT 11 — release one named slot without stopping others. */
    const val TRANSACT_RELEASE_SLOT = 11
    /** TRANSACT 12 — activate a layout: create N overlay+VD slots. */
    const val TRANSACT_ACTIVATE_LAYOUT = 12
    /** TRANSACT 13 — deactivate layout: release all layout_ slots. */
    const val TRANSACT_DEACTIVATE_LAYOUT = 13
    /** TRANSACT 14 — query the displayId for a named slot; returns -1 if not alive. */
    const val TRANSACT_QUERY_SLOT = 14
    /** TRANSACT 15 — move a package's task back to display 0 (teardown repatriation). */
    const val TRANSACT_MOVE_TO_DISPLAY0 = 15
    /** Capture one frame of a layerStack (0=main, cluster stack=2/1) to a JPEG on disk.
     *  Params: int layerStack, int width, int height, int quality, String outPath.
     *  Reply: String status ("OK <path>" / "FAIL …"). Used by the bug-report screenshot recorder. */
    const val TRANSACT_CAPTURE_DISPLAY = 16
    /** TRANSACT 17 — focus the task belonging to a selected tactile Layout slot. */
    const val TRANSACT_FOCUS_SLOT = 17

    /** PID-bound build marker used by the bounded dadb startup preflight.
     *  ON-DISK IDENTIFIER — do NOT rename with the class: a renamed marker path makes
     *  `SurfaceDaemonReusePolicy.shouldReuse` return false forever, so the daemon would be
     *  killed and respawned on every app launch. */
    const val VERSION_FILE = "/data/local/tmp/dashcast_mirrordaemon_ver"

    // Mirror state (shared between threads via Binder thread pool)
    @Volatile private var sMirrorToken: IBinder? = null
    private var sMirrorOwnerLease: DeathLease? = null
    @Volatile private var sClusterDisplayId = 2
    /** v1.2.7 — first-event trace flag; reset on each setupMirror to log once per session. */
    @Volatile private var sMotionFirstLogged = false
    @Volatile private var sKeyFirstLogged = false
    @Volatile private var sMotionTargetUnavailableLogged = false
    @Volatile private var sKeyTargetUnavailableLogged = false

    // ── Fission slot state ────────────────────────────────────────────────────
    @Volatile private var sContext: Context? = null
    @Volatile private var sSysContext: Context? = null
    private val sSlots = ConcurrentHashMap<String, SlotInfo>()

    private class SlotInfo(
        @JvmField val pkg: String,
        @JvmField var x: Int,
        @JvmField var y: Int,
        @JvmField var w: Int,
        @JvmField var h: Int,
    ) {
        @JvmField var overlayView: View? = null
        @JvmField var overlayWM: WindowManager? = null
        @JvmField var vd: VirtualDisplay? = null
        @JvmField var displayId = 0
        @JvmField val resizeGeneration = AtomicLong()
        private var ownerLease: DeathLease? = null
        private var released = false

        @Synchronized fun setOwnerLease(lease: DeathLease) {
            if (released) lease.close()
            else ownerLease = lease
        }

        @Synchronized fun isReleased(): Boolean = released

        @Synchronized fun release() {
            if (released) return
            released = true
            val lease = ownerLease
            ownerLease = null
            lease?.close()
            val display = vd
            if (display != null) {
                try { display.release() }
                catch (e: Exception) { out("[Fission] slot[$pkg] VD release error: " + e.message) }
                vd = null
            }
            val view = overlayView
            val wm = overlayWM
            overlayView = null
            overlayWM = null
            if (view != null && wm != null) {
                val r = Runnable {
                    try { wm.removeViewImmediate(view) }
                    catch (e: Exception) {
                        out("[Fission] slot[$pkg] overlay remove error: " + e.message)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) r.run()
                else Handler(Looper.getMainLooper()).post(r)
            }
        }
    }

    // InputManager (init une seule fois, lu depuis les threads Binder → volatile)
    @Volatile private var sInputManager: Any? = null
    @Volatile private var sInjectMethod: Method? = null
    @Volatile private var sSetDisplayId: Method? = null // MotionEvent.setDisplayId — may be null
    @Volatile private var sSetDisplayIdKey: Method? = null // KeyEvent.setDisplayId — may be null (v1.2.11)

    /** Keeps the M7 SurfaceFlinger evidence without blocking mirror startup or queuing stale dumps. */
    private val sMirrorAuditExecutor = BoundedSerialExecutor(1, ThreadFactory { runnable ->
        val thread = Thread(runnable, "mirror-sf-audit")
        thread.isDaemon = true
        thread
    })

    // ─────────────────────────────────────────────────────────────────────────

    /** Thread-safe stdout helper — writes to both the redirected log file AND logcat. */
    private fun out(msg: String) {
        println("[MirrorDaemon] $msg")
        System.out.flush()
        Log.i(TAG, msg) // logcat → captured by sniffer
    }

    private fun err(msg: String, t: Throwable?) {
        System.err.println("[MirrorDaemon][ERROR] $msg")
        t?.printStackTrace(System.err)
        System.err.flush()
        Log.e(TAG, msg, t) // logcat → captured by sniffer
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Print the build FIRST. This daemon is a separate uid-2000 process that survives an
        // APK reinstall, and it is reused whenever its marker build matches the app's — so a
        // capture can contain perfectly plausible log lines emitted by a daemon several
        // versions old. Without this line a triager cannot tell, and has twice drawn
        // conclusions from a stale daemon's output.
        out("main() start uid=" + Process.myUid() +
            " build=" + BuildConfig.VERSION_CODE +
            " (" + BuildConfig.VERSION_NAME + ")")
        try {
            // WIRE IDENTIFIER — do NOT rename with the class: AdbLocalClient.DAEMON_GREP matches
            // this exact runtime process name when deciding to reuse or kill the daemon.
            Process::class.java.getMethod("setArgV0", String::class.java)
                .invoke(null, "com.byd.dashcast.mirrordaemon")
            out("setArgV0 OK")
        } catch (ignored: Exception) {
            out("setArgV0 ignored: " + ignored.message)
        }

        Log.i(TAG, "Starting MirrorDaemon uid=" + Process.myUid())

        try {
            out("Looper.getMainLooper()=" + Looper.getMainLooper())
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            out("Looper ready")

            // System context (via ActivityThread)
            out("Loading ActivityThread...")
            val atClass = Class.forName("android.app.ActivityThread")
            out("ActivityThread found, calling systemMain()...")
            val thread = atClass.getMethod("systemMain").invoke(null)
            out("systemMain() returned: $thread")
            val context = thread!!.javaClass.getMethod("getSystemContext").invoke(thread) as Context?
            out("getSystemContext() returned: $context")
            if (context == null) {
                err("Context null — abandon", null)
                Log.e(TAG, "Context null")
                return
            }
            Log.i(TAG, "System context OK")
            out("System context OK")

            // Save contexts for Fission slot operations (overlay + VD creation)
            sSysContext = context
            try {
                sContext = context.createPackageContext("com.android.shell", 0)
                out("Fission: shell package context OK")
            } catch (ePkg: Exception) {
                out("Fission: shell package context failed, fallback to system context: " + ePkg.message)
                sContext = context
            }

            // Unlock hidden APIs
            out("unlockHiddenApis()...")
            unlockHiddenApis()
            out("unlockHiddenApis OK")

            // Initialiser InputManager
            out("initInputManager()...")
            initInputManager()
            out("initInputManager OK")

            // Create our Binder (effectively final for the inner class)
            out("Creating MirrorBinder...")
            val daemonBinder: IBinder = MirrorBinder()
            out("MirrorBinder created")

            // Resolve before publication. A transient failure is retried by isAllowedCaller();
            // until then only system and the daemon itself may use privileged verbs.
            val resolvedAppUid = resolveAppUid()
            if (resolvedAppUid >= 0) out("app uid resolved: $resolvedAppUid")

            writeVersionFile()

            // Enregistrer dans ServiceManager (accessible par uid=2000) :
            // Remplace registerReceiver (interdit depuis systemMain() — AMS rejette
            // the unregistered IApplicationThread → SecurityException).
            // WIRE IDENTIFIER — the service name "byd_mirror_daemon" below must NOT be renamed
            // with the class: FissionClient / DaemonBinderResolver look it up by this exact string,
            // and a daemon spawned by an older APK already registered under it.
            out("ServiceManager.addService(byd_mirror_daemon)...")
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                // Android 10 : addService(String, IBinder, boolean, int)
                try {
                    val addSvc = smClass.getDeclaredMethod("addService",
                        String::class.java, IBinder::class.java,
                        Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    addSvc.isAccessible = true
                    addSvc.invoke(null, "byd_mirror_daemon", daemonBinder, false, 0)
                    out("ServiceManager.addService (4-arg) OK")
                } catch (e2: NoSuchMethodException) {
                    // Fallback : addService(String, IBinder)
                    val addSvc = smClass.getDeclaredMethod("addService",
                        String::class.java, IBinder::class.java)
                    addSvc.isAccessible = true
                    addSvc.invoke(null, "byd_mirror_daemon", daemonBinder)
                    out("ServiceManager.addService (2-arg) OK")
                }
            } catch (eSm: Exception) {
                err("ServiceManager.addService FAILED — broadcast only", eSm)
            }

            // REMOVED: registerReceiver → SecurityException since systemMain()
            // AMS verifies that the IApplicationThread is in mPidsSelfLocked → refused
            // for an app_process not going through the normal startup sequence.
            // Replacement: ServiceManager.addService() above + initial sendBroadcast.

            // Announce our presence (sendBroadcast works from systemMain())
            out("broadcastBinder()...")
            broadcastBinder(context, daemonBinder)
            Log.i(TAG, "MirrorDaemon ready — Binder broadcast.")
            out("MirrorDaemon READY — Binder in ServiceManager + broadcast sent — Looper.loop() started")

            Looper.loop()
            out("Looper.loop() ended (should not happen)")
        } catch (e: Exception) {
            err("Crash MirrorDaemon", e)
            Log.e(TAG, "Crash MirrorDaemon", e)
        }
        out("main() ended")
    }

    // ── Binder ────────────────────────────────────────────────────────────────

    internal class MirrorBinder : Binder() {

        init {
            attachInterface(null, DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(DESCRIPTOR)
            // Caller-identity gate: the binder is published in the global ServiceManager and can be
            // obtained by any process, but only the app (+ system/self) may drive these privileged
            // input-injection / trusted-display / task verbs. enforceInterface alone is NOT auth.
            val callingUid = getCallingUid()
            if (!isAllowedCaller(callingUid)) {
                Log.w(TAG, "MirrorBinder: rejected transact code=$code from uid=$callingUid")
                reply?.writeException(SecurityException("caller uid $callingUid not permitted"))
                return true // consumed (rejected) — do not run the privileged verb
            }
            when (code) {
                TRANSACT_MIRROR_START -> {
                    val layerStack = data.readInt()
                    val clusterW = data.readInt()
                    val clusterH = data.readInt()
                    sClusterDisplayId = data.readInt()
                    val viewW = data.readInt()
                    val viewH = data.readInt()
                    val surface: Surface? = data.readParcelable(Surface::class.java.classLoader)
                    // Additive wire field: old clients omit it; current clients provide a
                    // process-owned token so an app crash cannot orphan the mirror display.
                    val owner: IBinder? = if (data.dataAvail() > 0) data.readStrongBinder() else null
                    val ok: Boolean
                    try {
                        stopMirror()
                        val ownerAttached = attachMirrorOwner(owner)
                        ok = ownerAttached && setupMirror(layerStack, clusterW, clusterH,
                            viewW, viewH, surface, owner != null)
                        if (!ok) stopMirror()
                    } finally {
                        // readParcelable created a daemon-local wrapper. SurfaceFlinger acquired
                        // the producer reference during setupMirror; this wrapper is no longer owned.
                        surface?.release()
                    }
                    // Reply to the client (synchronous call, not oneway)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeInt(if (ok) 1 else 0)
                    }
                    return true
                }
                TRANSACT_INJECT_MOTION -> {
                    val ev: MotionEvent? = data.readParcelable(MotionEvent::class.java.classLoader)
                    try {
                        injectMotion(ev)
                    } finally {
                        ev?.recycle()
                    }
                    return true
                }
                TRANSACT_INJECT_KEY -> {
                    val kev: KeyEvent? = data.readParcelable(KeyEvent::class.java.classLoader)
                    injectKey(kev)
                    return true
                }
                TRANSACT_MIRROR_STOP -> {
                    stopMirror()
                    return true
                }
                // The Java handlers dereference `reply` unconditionally (unlike verbs 1-4 above,
                // which null-check it). `!!` keeps that exact contract: a oneway call NPEs here
                // just as it did one frame deeper before.
                TRANSACT_CLUSTER_ATTACH -> return handleClusterAttach(data, reply!!)
                TRANSACT_RESIZE_SLOT -> return handleResizeSlot(data, reply!!)
                TRANSACT_ATTACH_SLOT -> return handleAttachSlot(data, reply!!)
                TRANSACT_RELEASE_SLOT -> return handleReleaseSlot(data, reply!!)
                TRANSACT_ACTIVATE_LAYOUT -> return handleActivateLayout(data, reply!!)
                TRANSACT_DEACTIVATE_LAYOUT -> return handleDeactivateLayout(data, reply!!)
                TRANSACT_QUERY_SLOT -> return handleQuerySlot(data, reply!!)
                TRANSACT_MOVE_TO_DISPLAY0 -> return handleMoveToDisplay0(data, reply!!)
                TRANSACT_FOCUS_SLOT -> return handleFocusSlot(data, reply!!)
                TRANSACT_CAPTURE_DISPLAY -> {
                    val layerStack = data.readInt()
                    val w = data.readInt()
                    val h = data.readInt()
                    val quality = data.readInt()
                    val outPath = data.readString()
                    val maxFiles = data.readInt()
                    val maxAgeMin = data.readInt()
                    val status = captureLayerStackToJpeg(layerStack, w, h, quality, outPath,
                        maxFiles, maxAgeMin)
                    if (reply != null) {
                        reply.writeNoException()
                        reply.writeString(status)
                    }
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun writeVersionFile() {
        try {
            FileOutputStream(File(VERSION_FILE)).use { fos ->
                val identity = Process.myPid().toString() + ":" + BuildConfig.VERSION_CODE
                fos.write(identity.toByteArray())
            }
        } catch (t: Throwable) {
            out("version marker write failed (startup remains available): " + t.message)
        }
    }

    // ── SurfaceControl mirror ─────────────────────────────────────────────────

    /**
     * Configures the mirror via STATIC methods of SurfaceControl (deprecated API but
     * functional on Android 10 BYD ROM — identical to the WindowManagement approach).
     * SurfaceControl.Transaction fails silently on this ROM.
     *
     * @return true if the mirror was configured successfully
     */
    @SuppressLint("NewApi")
    @Synchronized
    private fun setupMirror(layerStack: Int, clusterW: Int, clusterH: Int,
                            viewW: Int, viewH: Int, surface: Surface?,
                            ownerRequired: Boolean): Boolean {
        val lease = sMirrorOwnerLease
        if (ownerRequired && (lease == null || !lease.isActive)) {
            out("setupMirror refused: client owner already dead")
            return false
        }
        // v1.2.7 — reset per-session first-event trace so M7 captures the next injection chain.
        sMotionFirstLogged = false
        sKeyFirstLogged = false
        out("setupMirror BEGIN layerStack=" + layerStack +
            " cluster=" + clusterW + "x" + clusterH +
            " view=" + viewW + "x" + viewH +
            " surface=" + (if (surface == null) "null" else ("valid=" + surface.isValid)))
        if (surface == null || !surface.isValid) {
            Log.e(TAG, "setupMirror : surface invalide")
            out("setupMirror FAIL surface invalide")
            return false
        }
        var tx: SurfaceControl.Transaction? = null
        try {
            val scClass = Class.forName("android.view.SurfaceControl")

            // 1. Create the mirror display token
            val createDisplay = scClass.getDeclaredMethod("createDisplay",
                String::class.java, Boolean::class.javaPrimitiveType)
            createDisplay.isAccessible = true
            val token = createDisplay.invoke(null, "byd_myapp_mirror", false) as IBinder?
            sMirrorToken = token
            if (token == null) {
                Log.e(TAG, "setupMirror : createDisplay → null")
                out("setupMirror FAIL createDisplay returned null (DL5 SurfaceControl quirk?)")
                return false
            }
            Log.i(TAG, "setupMirror : createDisplay token=$token")
            out("setupMirror createDisplay OK token=$token")

            // 2. Letterbox projection (preserved ratio)
            val scale = minOf(viewW.toFloat() / clusterW, viewH.toFloat() / clusterH)
            val drawW = (clusterW * scale).toInt()
            val drawH = (clusterH * scale).toInt()
            val offX = (viewW - drawW) / 2
            val offY = (viewH - drawH) / 2
            val src = Rect(0, 0, clusterW, clusterH)
            val dst = Rect(offX, offY, offX + drawW, offY + drawH)
            Log.i(TAG, "setupMirror : src=" + src + " dst=" + dst +
                " surface.valid=" + surface.isValid)

            // 3. SurfaceControl.Transaction — instance methods via reflection.
            //    IMPORTANT: we use Transaction (not the static methods) because that is
            //    what worked in v2.43. Static methods (openTransaction/
            //    closeTransaction) are available on this ROM but produce a black
            //    screen with no error — behavior observed in v2.45.
            tx = SurfaceControl.Transaction()
            val txClass: Class<*> = tx.javaClass

            val setLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                IBinder::class.java, Int::class.javaPrimitiveType)
            setLayerStack.isAccessible = true
            setLayerStack.invoke(tx, token, layerStack)
            Log.i(TAG, "setupMirror : setDisplayLayerStack($layerStack) OK")

            val setSurface = txClass.getDeclaredMethod("setDisplaySurface",
                IBinder::class.java, Surface::class.java)
            setSurface.isAccessible = true
            setSurface.invoke(tx, token, surface)
            Log.i(TAG, "setupMirror : setDisplaySurface OK")

            val setProjection = txClass.getDeclaredMethod("setDisplayProjection",
                IBinder::class.java, Int::class.javaPrimitiveType,
                Rect::class.java, Rect::class.java)
            setProjection.isAccessible = true
            setProjection.invoke(tx, token, 0, src, dst)
            Log.i(TAG, "setupMirror : setDisplayProjection OK")

            tx.apply()
            Log.i(TAG, "setupMirror : tx.apply() OK")
            scheduleSurfaceFlingerAudit(layerStack)

            Log.i(TAG, "setupMirror ✓ (Transaction) layerStack=" + layerStack +
                " src=" + clusterW + "×" + clusterH +
                " dst=" + drawW + "×" + drawH + " offset=(" + offX + "," + offY + ")")
            out("setupMirror DONE ok=true layerStack=" + layerStack +
                " dst=" + drawW + "x" + drawH + " off=(" + offX + "," + offY + ")")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "setupMirror failed", e)
            out("setupMirror EXCEPTION: " + e.javaClass.simpleName + ": " + e.message)
            // If createDisplay succeeded but a later reflection step threw, the
            // SurfaceFlinger display token must be released — otherwise it leaks
            // for the lifetime of the daemon process. stopMirror() handles the
            // null case and clears sMirrorToken atomically.
            stopMirror()
            return false
        } finally {
            // Safe after apply(): close releases only this native transaction builder. Keeping it
            // in finally also covers reflection failures between construction and apply().
            if (tx != null) try { tx.close() } catch (ignored: Throwable) {}
        }
    }

    private fun scheduleSurfaceFlingerAudit(layerStack: Int) {
        try {
            sMirrorAuditExecutor.execute { auditMirrorInSurfaceFlinger(layerStack) }
        } catch (queueFull: RejectedExecutionException) {
            out("setupMirror SF dump skipped: previous audit still queued")
        }
    }

    private fun auditMirrorInSurfaceFlinger(layerStack: Int) {
        var process: java.lang.Process? = null
        try {
            process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c",
                    "dumpsys SurfaceFlinger 2>/dev/null" +
                        " | grep -iE 'byd_myapp_mirror|layerStack=" + layerStack + "'"))
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append('\n')
                }
            }
            try { process.errorStream.close() } catch (ignored: Exception) {}
            try { process.outputStream.close() } catch (ignored: Exception) {}
            process.waitFor()
            Log.i(TAG, "setupMirror SF dump :\n" + output.toString().trim())
            out("setupMirror SF dump (layerStack=" + layerStack + "):\n" +
                (if (output.isEmpty())
                    "(empty — token NOT in SurfaceFlinger!)" else output.toString().trim()))
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.d(TAG, "SF dump read failed: " + error.message)
            out("setupMirror SF dump read failed: " + error.message)
        } finally {
            if (process != null) {
                try { process.destroy() } catch (ignored: Exception) {}
            }
        }
    }

    // ── One-shot layerStack capture (bug-report screenshot recorder) ──────────

    /** Serializes captures against each other WITHOUT sharing the mirror's monitor — a slow/failed
     *  capture (up to ~1.5s waiting for a frame) must never block a visible mirror START/STOP. */
    private val sCaptureLock = Any()

    /**
     * Captures ONE frame of [layerStack] into a JPEG at [outPath], using the exact same
     * SurfaceControl mirror primitives as [setupMirror] (the only path proven to work on these
     * BYD ROMs) but pointed at an [ImageReader] surface so the daemon can read the pixels back.
     * `screencap -d N` cannot be used for the cluster: it silently falls back to display 0 on a
     * virtual display. Runs entirely inside the uid-2000 daemon (the only process that may call
     * SurfaceControl.createDisplay); writes to /data/local/tmp (uid-2000-writable, A13-safe).
     * The capture display is torn down in finally, so nothing leaks.
     *
     * @return "OK &lt;path&gt; &lt;bytes&gt;B" on success, or "FAIL …" / "EXCEPTION …".
     */
    @SuppressLint("NewApi", "WrongConstant") // RGBA_8888 is a PixelFormat, correct for display capture
    private fun captureLayerStackToJpeg(layerStack: Int, w: Int, h: Int,
                                        quality: Int, outPath: String?,
                                        maxFiles: Int, maxAgeMin: Int): String {
        synchronized(sCaptureLock) {
            if (w <= 0 || h <= 0 || outPath == null) return "FAIL bad-args"
            var token: IBinder? = null
            var reader: ImageReader? = null
            var image: Image? = null
            var bmp: Bitmap? = null
            var tx: SurfaceControl.Transaction? = null
            try {
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
                val surface = reader.surface

                val scClass = Class.forName("android.view.SurfaceControl")
                val createDisplay = scClass.getDeclaredMethod("createDisplay",
                    String::class.java, Boolean::class.javaPrimitiveType)
                createDisplay.isAccessible = true
                token = createDisplay.invoke(null, "byd_shot_capture", false) as IBinder?
                if (token == null) return "FAIL createDisplay-null"

                tx = SurfaceControl.Transaction()
                val txClass: Class<*> = tx.javaClass
                val setLayerStack = txClass.getDeclaredMethod("setDisplayLayerStack",
                    IBinder::class.java, Int::class.javaPrimitiveType)
                setLayerStack.isAccessible = true
                setLayerStack.invoke(tx, token, layerStack)
                val setSurface = txClass.getDeclaredMethod("setDisplaySurface",
                    IBinder::class.java, Surface::class.java)
                setSurface.isAccessible = true
                setSurface.invoke(tx, token, surface)
                val setProjection = txClass.getDeclaredMethod("setDisplayProjection",
                    IBinder::class.java, Int::class.javaPrimitiveType,
                    Rect::class.java, Rect::class.java)
                setProjection.isAccessible = true
                val full = Rect(0, 0, w, h)
                setProjection.invoke(tx, token, 0, full, full)
                tx.apply()

                // Wait for SurfaceFlinger to composite one frame into the reader (up to ~1.5s).
                var i = 0
                while (i < 30 && image == null) {
                    Thread.sleep(50)
                    image = reader.acquireLatestImage()
                    i++
                }
                if (image == null) return "FAIL no-frame"

                bmp = imageToBitmap(image, w, h)
                if (bmp == null) return "FAIL decode-null"

                val outFile = File(outPath)
                val dir = outFile.parentFile
                if (dir != null && !dir.exists()) {
                    dir.mkdirs()
                }
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, maxOf(1, minOf(100, quality)), fos)
                }
                val bytes = outFile.length()
                // Enforce the ring buffer HERE, in-process, right after the write — same channel that
                // produced the file. (The app-side shell prune uses ADB-TCP, which can drop while this
                // binder path keeps writing; pruning here makes the bound transport-independent, so the
                // shots can never grow without limit — the user's hard constraint.)
                pruneShotDir(dir, maxFiles, maxAgeMin)
                out("captureLayerStack ok stack=" + layerStack + " " + w + "x" + h +
                    " -> " + outPath + " (" + bytes + "B)")
                return "OK $outPath ${bytes}B"
            } catch (t: Throwable) {
                out("captureLayerStack EXCEPTION stack=$layerStack: $t")
                return "EXCEPTION " + t.javaClass.simpleName + ": " + t.message
            } finally {
                if (image != null) { try { image.close() } catch (ignore: Throwable) {} }
                if (bmp != null) { try { bmp.recycle() } catch (ignore: Throwable) {} }
                if (reader != null) { try { reader.close() } catch (ignore: Throwable) {} }
                // Close the SurfaceControl.Transaction deterministically (it holds a native
                // SurfaceComposerClient transaction freed only on GC otherwise); this runs on a 15s cadence.
                if (tx != null) { try { tx.close() } catch (ignore: Throwable) {} }
                if (token != null) {
                    try {
                        val destroy = Class.forName("android.view.SurfaceControl")
                            .getDeclaredMethod("destroyDisplay", IBinder::class.java)
                        destroy.isAccessible = true
                        destroy.invoke(null, token)
                    } catch (e: Throwable) {
                        Log.w(TAG, "captureLayerStack: destroyDisplay failed: " + e.message)
                    }
                }
            }
        }
    }

    /**
     * Ring-buffer prune of the shots dir: keep at most [maxFiles] newest JPEGs and drop any
     * older than [maxAgeMin] minutes. Pure java.io in the uid-2000 daemon (no shell), so it
     * runs on the same channel as the write and can never lag behind it.
     */
    private fun pruneShotDir(dir: File?, maxFiles: Int, maxAgeMin: Int) {
        if (dir == null) return
        try {
            val files = dir.listFiles { _, name ->
                name.startsWith("shot_") && name.endsWith(".jpg")
            }
            if (files == null || files.isEmpty()) return
            val cutoff = if (maxAgeMin > 0)
                System.currentTimeMillis() - maxAgeMin * 60_000L else Long.MIN_VALUE
            // Newest first.
            java.util.Arrays.sort(files) { a, b -> b.lastModified().compareTo(a.lastModified()) }
            for (i in files.indices) {
                val overCount = maxFiles > 0 && i >= maxFiles
                val tooOld = files[i].lastModified() < cutoff
                if (overCount || tooOld) {
                    files[i].delete()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pruneShotDir failed: " + t.message)
        }
    }

    /** RGBA_8888 [Image] → [Bitmap], handling the plane's row-stride padding. */
    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val planes = image.planes
        if (planes == null || planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * w
        val stridePx = w + (if (pixelStride == 0) 0 else rowPadding / pixelStride)
        val padded = createBitmap(stridePx, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (stridePx == w) return padded
        // Crop away the stride padding on the right edge.
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    @Synchronized
    private fun attachMirrorOwner(owner: IBinder?): Boolean {
        clearMirrorOwnerLease()
        if (owner == null) {
            out("setupMirror legacy client: no death lease")
            return true
        }
        return try {
            val lease = DeathLease.attach(BinderDeathOwner(owner)) {
                out("mirror owner died — releasing transient mirror")
                stopMirror()
            }
            sMirrorOwnerLease = lease
            lease.isActive
        } catch (t: Throwable) {
            out("setupMirror owner link failed: " + t.javaClass.simpleName + ": " + t.message)
            false
        }
    }

    private fun clearMirrorOwnerLease() {
        val lease = sMirrorOwnerLease
        sMirrorOwnerLease = null
        lease?.close()
    }

    @Synchronized
    private fun stopMirror() {
        clearMirrorOwnerLease()
        val token = sMirrorToken
        sMirrorToken = null
        if (token != null) {
            try {
                val scClass = Class.forName("android.view.SurfaceControl")
                val destroyDisplay = scClass.getDeclaredMethod("destroyDisplay", IBinder::class.java)
                destroyDisplay.isAccessible = true
                destroyDisplay.invoke(null, token)
                Log.i(TAG, "stopMirror ✓")
            } catch (e: Exception) {
                Log.w(TAG, "stopMirror: destroyDisplay failed: " + e.message)
            }
        }
    }

    // ── Input injection ───────────────────────────────────────────────────────

    private fun injectMotion(ev: MotionEvent?) {
        val im = sInputManager
        if (ev == null || im == null) {
            if (!sMotionFirstLogged) {
                sMotionFirstLogged = true
                out("injectMotion FAIL pre-check: ev=" + (ev != null) + " im=" + (im != null))
            }
            return
        }
        try {
            var targetApplied = false
            val setDisplayId = sSetDisplayId
            if (setDisplayId != null) {
                setDisplayId.invoke(ev, sClusterDisplayId)
                targetApplied = true
            }
            if (!InputDisplayRoutingPolicy.canInject(sClusterDisplayId, targetApplied)) {
                if (!sMotionTargetUnavailableLogged) {
                    sMotionTargetUnavailableLogged = true
                    out("injectMotion REFUSED: cluster display could not be applied to MotionEvent")
                }
                return
            }
            val r = sInjectMethod!!.invoke(im, ev, 0 /* ASYNC */)
            if (!sMotionFirstLogged) {
                sMotionFirstLogged = true
                out("injectMotion FIRST OK displayId=" + sClusterDisplayId +
                    " setDisplayIdAvail=" + (sSetDisplayId != null) +
                    " action=" + ev.actionMasked +
                    " x=" + ev.x.toInt() + " y=" + ev.y.toInt() +
                    " ret=" + r)
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectMotion failed: " + e.message)
            out("injectMotion EXCEPTION displayId=" + sClusterDisplayId +
                " action=" + ev.actionMasked + " err=" + e.javaClass.simpleName +
                ": " + e.message)
        }
    }

    private fun injectKey(kev: KeyEvent?) {
        val im = sInputManager
        if (kev == null || im == null) return
        try {
            // v1.2.11 — route the KeyEvent to the cluster display, same as MotionEvent.
            // Without this, keys go to the globally focused window (= our own
            // KeyboardBridgeActivity on display 0) and never reach the cluster
            // app. Mirrors the touch-injection displayId pattern.
            var targetApplied = false
            val setDisplayIdKey = sSetDisplayIdKey
            if (setDisplayIdKey != null) {
                try {
                    setDisplayIdKey.invoke(kev, sClusterDisplayId)
                    targetApplied = true
                } catch (ignored: Exception) {
                }
            }
            if (!InputDisplayRoutingPolicy.canInject(sClusterDisplayId, targetApplied)) {
                if (!sKeyTargetUnavailableLogged) {
                    sKeyTargetUnavailableLogged = true
                    out("injectKey REFUSED: cluster display could not be applied to KeyEvent")
                }
                return
            }
            sInjectMethod!!.invoke(im, kev, 0 /* ASYNC */)
            if (!sKeyFirstLogged) {
                sKeyFirstLogged = true
                out("injectKey FIRST OK displayId=" + sClusterDisplayId +
                    " setDisplayIdAvail=" + (sSetDisplayIdKey != null) +
                    " keyCode=" + kev.keyCode + " action=" + kev.action)
            }
        } catch (e: Exception) {
            Log.w(TAG, "injectKey failed: " + e.message)
            out("injectKey EXCEPTION keyCode=" + kev.keyCode + " err=" + e.javaClass.simpleName +
                ": " + e.message)
        }
    }

    private fun initInputManager() {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            sInputManager = getInstance.invoke(null)
            val inject = imClass.getDeclaredMethod("injectInputEvent",
                InputEvent::class.java, Int::class.javaPrimitiveType)
            inject.isAccessible = true
            sInjectMethod = inject
            try {
                val m = MotionEvent::class.java.getDeclaredMethod("setDisplayId",
                    Int::class.javaPrimitiveType)
                m.isAccessible = true
                sSetDisplayId = m
            } catch (ignored: Exception) { /* ROM sans setDisplayId */ }
            try {
                val m = KeyEvent::class.java.getDeclaredMethod("setDisplayId",
                    Int::class.javaPrimitiveType)
                m.isAccessible = true
                sSetDisplayIdKey = m
            } catch (ignored: Exception) { /* ROM sans KeyEvent.setDisplayId */ }
            Log.i(TAG, "InputManager init OK")
        } catch (e: Exception) {
            Log.e(TAG, "initInputManager failed", e)
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    // 1.2.30 — custom signature permission tag used as the receiverPermission on
    // the ACTION_DAEMON_READY broadcast. Only apps signed with the same cert as
    // this APK (= our own app) hold the permission and can receive the binder.
    // The fallback ServiceManager.getService("byd_mirror_daemon") path still
    // works for ROMs where receiverPermission filtering misbehaves.
    // WIRE IDENTIFIER — must match AndroidManifest.xml's <permission>/<uses-permission>; do NOT
    // rename with the class.
    const val PERM_DAEMON_READY = "com.byd.dashcast.permission.DAEMON_READY"

    private fun broadcastBinder(context: Context, binder: IBinder) {
        val extras = Bundle()
        // WIRE IDENTIFIER — read back by DaemonBinderResolver; do NOT rename with the class.
        extras.putBinder("daemon_binder", binder)
        val intent = Intent(ACTION_DAEMON_READY)
        intent.putExtras(extras)
        context.sendBroadcast(intent, PERM_DAEMON_READY)
    }

    // ── Hidden API unlock ─────────────────────────────────────────────────────

    private fun unlockHiddenApis() {
        try {
            // `Class[].class` in Java. Kotlin forbids a class literal on a generic array type
            // (`Array<Class<*>>::class`), so the same runtime type is taken from an empty array.
            val classArrayType: Class<*> = emptyArray<Class<*>>().javaClass
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, classArrayType)
            val forNameMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntimeMethod = getDeclaredMethod.invoke(
                vmRuntimeClass, "getRuntime", null) as Method
            val vmRuntime = getRuntimeMethod.invoke(null)
            val setExemptions = getDeclaredMethod.invoke(vmRuntimeClass,
                "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
            // NO extra Object[] wrapper. Method.invoke is `invoke(Object, Object...)`, so Kotlin
            // already builds the varargs array; passing arrayOf<Any>(arrayOf(...)) made argument 0
            // an Object[] holding the String[] instead of the String[] itself, and
            // setHiddenApiExemptions(String[]) rejected it with IllegalArgumentException — caught
            // below and visible only in logcat, while main() had already printed
            // "unlockHiddenApis OK" into the log the bug reports ship. Same shape as the working
            // copy in ClusterMirrorManager.unlockHiddenApis.
            setExemptions.invoke(vmRuntime, arrayOf("Landroid/", "Lcom/android/", "Ljava/lang/"))
            Log.i(TAG, "unlockHiddenApis OK")
        } catch (e: Exception) {
            Log.e(TAG, "unlockHiddenApis failed", e)
        }
    }

    // ── Fission handlers ──────────────────────────────────────────────────────
    // NOTE: data.enforceInterface(DESCRIPTOR) is already called by onTransact —
    // do NOT call it again inside these handlers.

    private fun handleClusterAttach(data: Parcel, reply: Parcel): Boolean {
        data.readInt() // wire compat: layerStack, deliberately unused
        val w = data.readInt()
        val h = data.readInt()
        out("[Fission] CLUSTER_ATTACH $w×$h")
        val existing = sSlots.remove("__default__")
        existing?.release()
        val slot = SlotInfo("__default__", 0, 0, w, h)
        val surface = tryAttachSlotOverlay(slot)
        if (surface == null) { reply.writeNoException(); reply.writeInt(0); return true }
        val displayId = createTrustedVdForSlot(slot, surface, "dashcast_cluster_default")
        if (displayId < 0) { slot.release(); reply.writeNoException(); reply.writeInt(0); return true }
        // Release whatever this replaced. The pre-check above releases an EXISTING slot, but two
        // concurrent attaches for the same key can both observe null there and both build; the
        // second put would then drop the first SlotInfo on the floor, leaking a
        // TYPE_SYSTEM_OVERLAY window, a VirtualDisplay and a Surface in a daemon that outlives the
        // app. Phase4DisplayVerbs' VirtualDisplay registry — same process, same shape — has always
        // done this; this map had drifted from it.
        run { val replaced = sSlots.put("__default__", slot); replaced?.release() }
        out("[Fission] CLUSTER_ATTACH OK displayId=$displayId")
        reply.writeNoException(); reply.writeInt(1)
        reply.writeParcelable(surface, 0); reply.writeInt(displayId)
        return true
    }

    private fun handleAttachSlot(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()!!
        val x = data.readInt()
        val y = data.readInt()
        val w = data.readInt()
        val h = data.readInt()
        // Added after the original wire fields. Null keeps compatibility with clients built
        // before process-owned slots were introduced.
        val owner: IBinder? = if (data.dataAvail() > 0) data.readStrongBinder() else null
        // build= on the BLOCK HEADER, not only in main(): the startup line is the first line of
        // the daemon log and the report ships the LAST 400, so on a long-lived daemon it is
        // always out of frame — which is exactly the reused-stale-daemon case it was added for.
        // Here it costs one field and travels with the block a triager actually reads.
        out("[Fission] ATTACH_SLOT pkg=" + pkg + " (" + x + "," + y + "," + w + "×" + h + ")" +
            " build=" + BuildConfig.VERSION_CODE)
        val existing = sSlots.remove(pkg)
        existing?.release()
        val slot = SlotInfo(pkg, x, y, w, h)
        val surface = tryAttachSlotOverlay(slot)
        if (surface == null) { reply.writeNoException(); reply.writeInt(0); return true }
        val displayId = createTrustedVdForSlot(slot, surface,
            "dashcast_slot_" + pkg.replace('.', '_'))
        if (displayId < 0) { slot.release(); reply.writeNoException(); reply.writeInt(0); return true }
        // Release whatever this replaced — same reason as in handleClusterAttach.
        run { val replaced = sSlots.put(pkg, slot); replaced?.release() }
        if (owner != null && !attachSlotOwner(pkg, slot, owner)) {
            if (sSlots.remove(pkg, slot)) slot.release()
            reply.writeNoException(); reply.writeInt(0); return true
        }
        out("[Fission] ATTACH_SLOT OK pkg=$pkg displayId=$displayId")
        reply.writeNoException(); reply.writeInt(1)
        reply.writeParcelable(surface, 0); reply.writeInt(displayId)
        return true
    }

    private fun attachSlotOwner(key: String, slot: SlotInfo, owner: IBinder): Boolean {
        return try {
            val lease = DeathLease.attach(BinderDeathOwner(owner)) {
                out("[Fission] slot owner died — releasing $key")
                if (sSlots.remove(key, slot)) slot.release()
            }
            slot.setOwnerLease(lease)
            !slot.isReleased() && sSlots[key] === slot
        } catch (t: Throwable) {
            out("[Fission] slot owner link failed for " + key + ": " +
                t.javaClass.simpleName + ": " + t.message)
            false
        }
    }

    private fun handleReleaseSlot(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()!!
        out("[Fission] RELEASE_SLOT pkg=$pkg")
        val slot = sSlots.remove(pkg)
        slot?.release()
        reply.writeNoException(); reply.writeInt(1)
        return true
    }

    private fun handleResizeSlot(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()!!
        val x = data.readInt()
        val y = data.readInt()
        val w = data.readInt()
        val h = data.readInt()
        out("[Fission] RESIZE_SLOT pkg=" + pkg + " (" + x + "," + y + "," + w + "×" + h + ")")
        val slot = sSlots[pkg]
        if (slot == null || slot.isReleased() || w <= 0 || h <= 0) {
            reply.writeNoException(); reply.writeInt(0); return true
        }
        val generation: Long
        val oldX: Int
        val oldY: Int
        val oldW: Int
        val oldH: Int
        synchronized(slot) {
            generation = slot.resizeGeneration.incrementAndGet()
            oldX = slot.x; oldY = slot.y; oldW = slot.w; oldH = slot.h
        }
        val latch = CountDownLatch(1)
        val overlayResized = AtomicBoolean(false)
        val resizeOverlay = Runnable resize@{
            try {
                if (!isCurrentResize(pkg, slot, generation)) return@resize
                applySlotOverlayGeometry(slot, x, y, w, h)
                overlayResized.set(true)
            } catch (e: Exception) {
                out("[Fission] RESIZE_SLOT overlay error: " + e.message)
            } finally { latch.countDown() }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        if (!mainHandler.post(resizeOverlay)) {
            reply.writeNoException(); reply.writeInt(0); return true
        }
        var completed = false
        try { completed = latch.await(1, TimeUnit.SECONDS) }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        if (!completed) {
            mainHandler.removeCallbacks(resizeOverlay)
            out("[Fission] RESIZE_SLOT overlay timed out — VD resize skipped to avoid size mismatch")
            scheduleResizeRollback(mainHandler, pkg, slot, generation, oldX, oldY, oldW, oldH)
            reply.writeNoException(); reply.writeInt(0); return true
        }
        if (!overlayResized.get()) {
            scheduleResizeRollback(mainHandler, pkg, slot, generation, oldX, oldY, oldW, oldH)
            reply.writeNoException(); reply.writeInt(0); return true
        }
        try {
            val vd = slot.vd
            if (!isCurrentResize(pkg, slot, generation) || vd == null) {
                throw IllegalStateException("slot superseded or released")
            }
            vd.resize(w, h, 160)
        } catch (e: Exception) {
            out("[Fission] RESIZE_SLOT VD error: " + e.message)
            scheduleResizeRollback(mainHandler, pkg, slot, generation, oldX, oldY, oldW, oldH)
            reply.writeNoException(); reply.writeInt(0); return true
        }
        synchronized(slot) {
            if (!isCurrentResize(pkg, slot, generation)) {
                reply.writeNoException(); reply.writeInt(0); return true
            }
            slot.x = x; slot.y = y; slot.w = w; slot.h = h
        }
        reply.writeNoException(); reply.writeInt(1)
        return true
    }

    private fun isCurrentResize(pkg: String, slot: SlotInfo, generation: Long): Boolean {
        return sSlots[pkg] === slot && !slot.isReleased() &&
            slot.resizeGeneration.get() == generation
    }

    private fun scheduleResizeRollback(
        mainHandler: Handler, pkg: String, slot: SlotInfo, generation: Long,
        x: Int, y: Int, w: Int, h: Int) {
        val rollbackGeneration: Long
        synchronized(slot) {
            if (!isCurrentResize(pkg, slot, generation)) return
            rollbackGeneration = slot.resizeGeneration.incrementAndGet()
        }
        mainHandler.post {
            if (!isCurrentResize(pkg, slot, rollbackGeneration)) return@post
            try { applySlotOverlayGeometry(slot, x, y, w, h) }
            catch (rollbackError: Exception) {
                out("[Fission] RESIZE_SLOT overlay rollback error: " + rollbackError.message)
            }
        }
    }

    private fun applySlotOverlayGeometry(slot: SlotInfo, x: Int, y: Int, w: Int, h: Int) {
        val lp = createOverlayLayoutParams(null, w, h)
        lp.x = x; lp.y = y
        slot.overlayWM!!.updateViewLayout(slot.overlayView, lp)
        (slot.overlayView as SurfaceView).holder.setFixedSize(w, h)
    }

    /**
     * Batch layout activation — **no app client sends this any more**.
     *
     * It keys its slots `layout_<label>_<i>` and the package name never crosses the wire, so
     * nothing it creates can be found again by [handleQuerySlot], [handleReleaseSlot] or
     * [handleResizeSlot] — all three look up by package. The app now sends one `ATTACH_SLOT` per
     * zone instead. Kept (and still answered) so a daemon that outlives an app update cannot fail
     * an old transaction; do not add a new client without fixing the keyspace first.
     */
    private fun handleActivateLayout(data: Parcel, reply: Parcel): Boolean {
        val n = data.readInt()
        out("[Fission] ACTIVATE_LAYOUT n=$n")
        for (key in ArrayList(sSlots.keys)) {
            if (key.startsWith("layout_")) {
                val s = sSlots.remove(key); s?.release()
            }
        }
        reply.writeNoException(); reply.writeInt(n)
        for (i in 0 until n) {
            val label = data.readString()!!
            val x = data.readInt()
            val y = data.readInt()
            val w = data.readInt()
            val h = data.readInt()
            val safe = label.replace(Regex("[^A-Za-z0-9_]"), "_")
            val key = "layout_" + safe + "_" + i
            val slot = SlotInfo(key, x, y, w, h)
            val surface = tryAttachSlotOverlay(slot)
            if (surface == null) { reply.writeInt(-1); continue }
            val displayId = createTrustedVdForSlot(slot, surface, "dashcast_layout_$safe")
            if (displayId < 0) { slot.release(); reply.writeInt(-1); continue }
            run { val replaced = sSlots.put(key, slot); replaced?.release() }
            out("[Fission] ACTIVATE_LAYOUT [$label] → displayId=$displayId")
            reply.writeInt(displayId)
        }
        return true
    }

    private fun handleQuerySlot(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()!!
        val slot = sSlots[pkg]
        val displayId = if (slot != null && slot.vd != null) slot.displayId else -1
        out("[Fission] QUERY_SLOT pkg=$pkg → displayId=$displayId")
        reply.writeNoException()
        reply.writeInt(displayId)
        return true
    }

    private fun handleMoveToDisplay0(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()
        out("[Fission] MOVE_TO_DISPLAY0 pkg=$pkg")
        val taskId = Phase4TaskVerbs.findTaskIdForPackage(pkg)
        val result: String = if (taskId <= 0) {
            "no task for $pkg"
        } else {
            Phase4TaskVerbs.moveTaskToDisplayCompatible(taskId, 0)
        }
        out("[Fission] MOVE_TO_DISPLAY0 result: $result")
        reply.writeNoException()
        reply.writeString(result)
        return true
    }

    private fun handleFocusSlot(data: Parcel, reply: Parcel): Boolean {
        val pkg = data.readString()
        val taskId = Phase4TaskVerbs.findTaskIdForPackage(pkg)
        val result = if (taskId > 0)
            Phase4TaskVerbs.setFocusedRootTask(taskId)
        else "ERR no task for $pkg"
        out("[Fission] FOCUS_SLOT pkg=$pkg taskId=$taskId result=$result")
        reply.writeNoException()
        reply.writeString(result)
        return true
    }

    /**
     * Releases EVERY slot, not only the `layout_`-prefixed ones.
     *
     * Since the app activates a layout with per-package `ATTACH_SLOT` calls, a prefix filter here
     * would match nothing and leave every overlay of the active layout on the cluster when the
     * user picks "free mode". `sSlots` is Layout-exclusive — the standard projection path
     * (MIRROR_START/STOP, INJECT_*, CAPTURE_DISPLAY) never touches it.
     */
    private fun handleDeactivateLayout(data: Parcel, reply: Parcel): Boolean {
        out("[Fission] DEACTIVATE_LAYOUT slots=" + sSlots.size)
        for (key in ArrayList(sSlots.keys)) {
            val s = sSlots.remove(key)
            s?.release()
        }
        reply.writeNoException(); reply.writeInt(1)
        return true
    }

    // ── Fission helpers ───────────────────────────────────────────────────────

    /** Returns a daemon-visible cluster display after applying the shared ownership/safety policy. */
    private fun resolveClusterDisplay(): Display? {
        val dmCtx = sSysContext ?: sContext
        if (dmCtx == null) { out("[Fission] resolveClusterDisplay: no context"); return null }
        val dm = dmCtx.getSystemService(DisplayManager::class.java) ?: return null
        val visible = dm.displays
        if (visible == null || visible.isEmpty()) return null
        val infos = ArrayList<ClusterDisplayInfo>(visible.size)
        val byId = HashMap<Int, Display>()
        for (candidate in visible) {
            val id = candidate.displayId
            val name = candidate.name
            var ownerUid = ClusterDisplayInfo.OWNER_UID_UNKNOWN
            var ownerPackage: String? = null
            try {
                val value = Display::class.java.getMethod("getOwnerUid").invoke(candidate)
                if (value is Int) ownerUid = value
            } catch (ignored: Throwable) {}
            try {
                val value = Display::class.java.getMethod("getOwnerPackageName").invoke(candidate)
                if (value != null) ownerPackage = value.toString()
            } catch (ignored: Throwable) {}
            val isPrivate = (candidate.flags and Display.FLAG_PRIVATE) != 0
            val state = if (candidate.state == Display.STATE_OFF) "OFF" else ""
            infos.add(ClusterDisplayInfo(
                id, name ?: "", 0, 0, id,
                isPrivate, ownerUid, ownerPackage, state))
            byId[id] = candidate
        }
        val selected = ClusterDisplaySelectionPolicy.pick(infos)
        if (selected == null) {
            out("[Fission] resolveClusterDisplay: no usable candidate in $infos")
            return null
        }
        return byId[selected.id]
    }

    /** Creates an overlay SurfaceView on the cluster display and returns its Surface. */
    private fun tryAttachSlotOverlay(slot: SlotInfo): Surface? {
        try {
            out("[ATTACH_SLOT] step1: resolveClusterDisplay pkg=" + slot.pkg)
            val target = resolveClusterDisplay()
            if (target == null) {
                out("[ATTACH_SLOT] FAIL: cluster display not found")
                // Layout has never produced a single trace on DL4 / DL5.1 / DX_BYD_AUTO
                // (29 of 149 field captures). A bare "not found" cannot tell "this platform
                // has no cluster display at all" from "the daemon looked too early", so dump
                // what the daemon DID see. Failure path only — costs nothing when it works.
                dumpDisplaysForDiagnostics()
                return null
            }
            // step1..step4 keep their exact existing wording so a new capture still diffs
            // line-for-line against the DiLink 3 successes and the 2 DL5.0 failures;
            // everything added below is a NEW sub-step, never a renumbering.
            //
            // The descriptor goes on the SUCCESS line, not only on the "not found" branch:
            // resolveClusterDisplay() tries getDisplay(1) first and every known platform HAS
            // a display 1 — DL3 the real cluster, DL4 the OEM's own virtual
            // "fission_bg_xdjaVirtualSurface" (owned by com.xdja.containerservice),
            // DX_BYD_AUTO an external "HDMI Screen". So a bare id + name cannot distinguish
            // "found the cluster" from "found something else and will now paint into it".
            // flags/type are exactly what separates those three cases.
            out("[ATTACH_SLOT] step2: targetDisplay=" + target.displayId +
                " " + describeDisplay(target) +
                " latch setup pkg=" + slot.pkg)
            val latch = CountDownLatch(1)
            val surfaceRef = AtomicReference<Surface?>()
            val errorRef = AtomicReference<String?>()
            // Localises a mid-attach throw. On DiLink 5.0 both captures
            // (INC-20260615-160735, INC-20260622-080346) stop after step3 +
            // "OP_SYSTEM_ALERT_WINDOW granted" with
            //   SecurityException: Given calling package android does not match caller's uid 2000
            // and step4 never prints — i.e. the throw is in the statements between the two log
            // lines and nothing recorded which one. This ref names the statement in flight.
            //
            // NOT "step2": step2 already succeeded, on the binder thread. And not a real stage
            // either until the runnable actually starts — the 2 s timeout below reports this
            // value, and a main-looper stall means the runnable NEVER RAN, which is precisely
            // the case the stage ref exists to tell apart from "threw inside a statement".
            // Naming a real stage here would assert the opposite of what happened.
            val stageRef = AtomicReference("queued-never-dispatched")
            // Set true when the binder thread gives up (timeout / invalid surface). The
            // attach runnable checks it under the slot monitor after wm.addView so a
            // late-running attach removes its own window instead of leaking it.
            val aborted = AtomicBoolean(false)

            val attach = Runnable attach@{
                // First statement: the runnable IS running, so the timeout can no longer be
                // reported as "never dispatched". getResources() below is unguarded, so this
                // stage has to be named before it, not after.
                stageRef.set("step3-displayCtx/getResources")
                try {
                    // Use the shell-identity context (pkg="com.android.shell", uid=2000).
                    // On Android 12 (DL5) WMS strictly checks context.getPackageName() against
                    // the calling uid — sSysContext has pkg="android" (uid=1000) which fails.
                    val base = sContext ?: sSysContext!!
                    var displayCtx: Context? = null
                    try { displayCtx = base.createDisplayContext(target) }
                    catch (e: Exception) {
                        out("[ATTACH_SLOT] createDisplayContext: " + e.message)
                    }
                    val hasRes = displayCtx != null && displayCtx.resources != null
                    val viewCtx = if (hasRes) displayCtx!! else base
                    out("[ATTACH_SLOT] step3: displayCtx=" + (if (displayCtx != null) "ok" else "null") +
                        " resources=" + (if (hasRes) "ok" else "null") + " pkg=" + slot.pkg)

                    // The DL5.0 SecurityException names package "android" while the daemon is
                    // uid 2000 (shell) — so print which identity each context actually carries.
                    // getOpPackageName() is the field WMS/AppOps validate and it is NOT
                    // getPackageName() once createPackageContext("com.android.shell") is used.
                    // displayCtx is printed as well as base/view: wm.addView is performed by the
                    // WindowManager obtained from displayCtx, so displayCtx's op-package is the
                    // identity WMS actually validates. When getResources() fails, viewCtx falls
                    // back to base and the other two fields would both show base — hiding the
                    // one that matters.
                    stageRef.set("step3a-identity")
                    out("[ATTACH_SLOT] step3a: uid=" + Process.myUid() +
                        " base=" + contextIdentity(base) +
                        " view=" + contextIdentity(viewCtx) +
                        " displayCtx=" + contextIdentity(displayCtx))

                    // Grant OP_SYSTEM_ALERT_WINDOW (op=24) to our uid via AppOps
                    stageRef.set("step3b-appops")
                    val appCtx = sContext
                    if (appCtx != null) {
                        try {
                            val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE)
                            val setMode = appOps.javaClass.getMethod(
                                "setMode", Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType, String::class.java,
                                Int::class.javaPrimitiveType)
                            setMode.isAccessible = true
                            setMode.invoke(appOps, 24, Process.myUid(), appCtx.packageName, 0)
                            out("[ATTACH_SLOT] OP_SYSTEM_ALERT_WINDOW granted")
                        } catch (appE: Exception) {
                            out("[ATTACH_SLOT] AppOps skipped (" + appE.message + ")")
                        }
                    }

                    stageRef.set("step3c-new-SurfaceView")
                    val sv = SurfaceView(viewCtx)
                    out("[ATTACH_SLOT] step3c: SurfaceView created pkg=" + slot.pkg)
                    stageRef.set("step3d-holder")
                    sv.holder.setFixedSize(slot.w, slot.h)
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            val s = h.surface
                            if (s != null && s.isValid) {
                                out("[ATTACH_SLOT] surfaceCreated valid pkg=" + slot.pkg)
                                surfaceRef.compareAndSet(null, s); latch.countDown()
                            }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w2: Int, h2: Int) {
                            val s = h.surface
                            if (s != null && s.isValid) {
                                out("[ATTACH_SLOT] surfaceChanged valid " + w2 + "x" + h2 +
                                    " pkg=" + slot.pkg)
                                surfaceRef.compareAndSet(null, s); latch.countDown()
                            }
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            out("[ATTACH_SLOT] surfaceDestroyed pkg=" + slot.pkg)
                        }
                    })

                    out("[ATTACH_SLOT] step3d: holder fixed=" + slot.w + "x" + slot.h +
                        " callback attached pkg=" + slot.pkg)
                    stageRef.set("step3e-getSystemService(WindowManager)")
                    val wm = displayCtx?.getSystemService(WindowManager::class.java)
                    if (wm == null) {
                        errorRef.set("step3e-getSystemService(WindowManager): WM null for display " +
                            target.displayId)
                        latch.countDown(); return@attach
                    }
                    out("[ATTACH_SLOT] step3e: wm=ok pkg=" + slot.pkg)
                    stageRef.set("step3f-createOverlayLayoutParams")
                    val lp = createOverlayLayoutParams(target, slot.w, slot.h)
                    lp.x = slot.x; lp.y = slot.y
                    out("[ATTACH_SLOT] step3f: lp ready type=" + lp.type + " lpPkg=" + lp.packageName)
                    stageRef.set("step4-wm.addView")
                    out("[ATTACH_SLOT] step4: wm.addView display=" + target.displayId +
                        " pos=" + lp.x + "," + lp.y + " size=" + slot.w + "x" + slot.h)
                    wm.addView(sv, lp)
                    // step5 separates "addView threw" from "addView returned but no surface
                    // ever became valid" — two different platform failures that used to look
                    // identical in a capture.
                    stageRef.set("step5-publish")
                    out("[ATTACH_SLOT] step5: wm.addView returned pkg=" + slot.pkg)
                    // Publish under the slot monitor and re-check the abort flag: if the
                    // binder thread already timed out, remove this just-added window here
                    // (we are on the main looper — the correct thread for removeView) rather
                    // than leak an orphaned TYPE_SYSTEM_OVERLAY in the permanent daemon.
                    synchronized(slot) {
                        if (aborted.get()) {
                            try { wm.removeViewImmediate(sv) }
                            catch (ignore: Exception) {}
                        } else {
                            slot.overlayView = sv
                            slot.overlayWM = wm
                        }
                    }
                } catch (e: Exception) {
                    // Name the stage: a SecurityException here used to be un-attributable.
                    val reason = stageRef.get() + ": " + e.javaClass.simpleName + ": " + e.message
                    out("[ATTACH_SLOT] error at $reason")
                    // ...and the frames, which is the difference between a hypothesis and a
                    // fact. The DL5.0 wording ("Given calling package android does not match
                    // caller's uid 2000") is AMS.enforceCallingPackage — the stack says which
                    // framework call under this statement reached it. err() already routes to
                    // stderr, which the launcher redirects into the SAME daemon log file, so
                    // this needs no new plumbing.
                    err("[ATTACH_SLOT] $reason", e)
                    // Never store a bare getMessage(): a null message left errorRef null and the
                    // caller then logged the misleading "FAIL: no valid surface" instead of the
                    // real exception.
                    errorRef.set(reason); latch.countDown()
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) attach.run()
            else Handler(Looper.getMainLooper()).post(attach)

            if (!latch.await(2, TimeUnit.SECONDS)) {
                // Report the stage the attach runnable was stuck in — a main-looper stall and
                // a blocking framework call inside addView look the same without it.
                out("[ATTACH_SLOT] TIMEOUT 2s at " + stageRef.get() + " pkg=" + slot.pkg)
                abortOverlayAttach(slot, aborted); return null
            }
            if (errorRef.get() != null) {
                out("[ATTACH_SLOT] FAIL: " + errorRef.get())
                abortOverlayAttach(slot, aborted); return null
            }
            val surface = surfaceRef.get()
            if (surface == null || !surface.isValid) {
                out("[ATTACH_SLOT] FAIL: no valid surface pkg=" + slot.pkg)
                abortOverlayAttach(slot, aborted); return null
            }
            out("[ATTACH_SLOT] OK surface valid pkg=" + slot.pkg +
                " display=" + target.displayId)
            return surface
        } catch (e: Exception) {
            out("[ATTACH_SLOT] exception: " + e.javaClass.simpleName + ": " + e.message)
            return null
        }
    }

    /**
     * `"<packageName>/<opPackageName>"` for a daemon context, or a marker when unavailable.
     *
     * The DiLink 5.0 failure reads "Given calling package android does not match caller's
     * uid 2000" — but the daemon builds its view context from
     * `createPackageContext("com.android.shell")`, whose `getPackageName()` is
     * "com.android.shell". `getOpPackageName()` is the identity AppOps/WMS actually validate and
     * it can still be the inherited system one ("android"). Reflection because that getter is not
     * public API; failure is non-fatal (diagnostics only).
     */
    private fun contextIdentity(c: Context?): String {
        if (c == null) return "null"
        var pkg = "?"
        var op = "?"
        try { pkg = c.packageName.toString() } catch (ignored: Exception) {}
        try {
            val m = Context::class.java.getMethod("getOpPackageName")
            op = m.invoke(c)?.toString() ?: "null" // String.valueOf() semantics, as in Java
        } catch (ignored: Exception) {}
        return "$pkg/$op"
    }

    /**
     * One-line descriptor of a logical display: name, state, flags, physical size and — when
     * the platform lets us read them — the owner package and display type.
     *
     * Owner and type are the fields that actually separate the known platforms: DiLink 3's
     * display 1 is the real cluster, DiLink 4's is the OEM's own VIRTUAL
     * `fission_bg_xdjaVirtualSurface` owned by `com.xdja.containerservice`, and DX_BYD_AUTO's is
     * an EXTERNAL "HDMI Screen". Both getters are non-public, so they are read reflectively and
     * degrade to `?` — this is diagnostics, never a hard failure.
     */
    private fun describeDisplay(d: Display?): String {
        if (d == null) return "null"
        val sb = StringBuilder()
        try { sb.append("name=").append(d.name) } catch (ignored: Exception) { sb.append("name=?") }
        try { sb.append(" state=").append(d.state) } catch (ignored: Exception) {}
        try { sb.append(" flags=0x").append(Integer.toHexString(d.flags)) } catch (ignored: Exception) {}
        try {
            val mode = d.mode
            if (mode != null) {
                sb.append(" size=").append(mode.physicalWidth)
                    .append('x').append(mode.physicalHeight)
            }
        } catch (ignored: Exception) {}
        try {
            val type = Display::class.java.getMethod("getType").invoke(d)
            sb.append(" type=").append(type)
        } catch (ignored: Exception) { sb.append(" type=?") }
        try {
            val owner = Display::class.java.getMethod("getOwnerPackageName").invoke(d)
            sb.append(" owner=").append(owner)
        } catch (ignored: Exception) { sb.append(" owner=?") }
        return sb.toString()
    }

    /**
     * Logs every logical display the daemon can see. Called only when [resolveClusterDisplay]
     * returns `null`, so it is free on the happy path.
     *
     * Layout is proven working on DiLink 3 only; DL4, DL5.1 and DX_BYD_AUTO have never produced
     * one Layout trace. Whether the daemon can even see a cluster display there is the first
     * question a capture must answer, and "not found" alone does not answer it.
     */
    private fun dumpDisplaysForDiagnostics() {
        try {
            val dmCtx = sSysContext ?: sContext
            val dm = dmCtx?.getSystemService(DisplayManager::class.java)
            if (dm == null) { out("[ATTACH_SLOT] displays: DisplayManager unavailable"); return }
            val all = dm.displays
            out("[ATTACH_SLOT] displays: count=" + (all?.size ?: 0))
            if (all == null) return
            for (d in all) {
                out("[ATTACH_SLOT] display id=" + d.displayId + " " + describeDisplay(d))
            }
        } catch (e: Exception) {
            out("[ATTACH_SLOT] displays: enumeration failed: " + e.message)
        }
    }

    /**
     * Abort an in-flight overlay attach after a failure: mark the attach runnable to
     * self-remove if it runs late, then release any window it already added. Prevents an
     * orphaned TYPE_SYSTEM_OVERLAY window (+ Surface) accumulating in this permanent daemon
     * on every slow/failed attach. The slot monitor orders this against the runnable's
     * publish so the window is removed exactly once (here, or by the late runnable).
     */
    private fun abortOverlayAttach(slot: SlotInfo, aborted: AtomicBoolean) {
        synchronized(slot) {
            aborted.set(true)
        }
        slot.release()
    }

    /** Creates a TRUSTED VirtualDisplay for the given slot. Never returns display id=0. */
    @SuppressLint("WrongConstant")
    private fun createTrustedVdForSlot(slot: SlotInfo, surface: Surface, vdName: String): Int {
        try {
            out("[CREATE_VD] name=" + vdName + " size=" + slot.w + "x" + slot.h + " dpi=160")
            val dm = sContext!!.getSystemService(DisplayManager::class.java)
            var vd: VirtualDisplay? = null
            try {
                // flags 1346 = PRESENTATION(2)|SUPPORTS_TOUCH(64)|DESTROY_ON_REMOVAL(256)|TRUSTED(1024)
                vd = dm.createVirtualDisplay(vdName, slot.w, slot.h, 160, surface, 1346)
                if (vd != null) out("[CREATE_VD] TRUSTED OK name=$vdName")
            } catch (e: Exception) {
                out("[CREATE_VD] TRUSTED failed, fallback flags=322: " + e.message)
            }
            if (vd == null) {
                // flags 322 = PRESENTATION(2)|SUPPORTS_TOUCH(64)|DESTROY_ON_REMOVAL(256)
                vd = dm.createVirtualDisplay(vdName, slot.w, slot.h, 160, surface, 322)
                if (vd != null) out("[CREATE_VD] fallback OK name=$vdName")
            }
            if (vd == null) { out("[CREATE_VD] FAIL: both flags failed name=$vdName"); return -1 }
            slot.vd = vd
            slot.displayId = vd.display.displayId
            // Safety guard: new VD must never land on display 0
            if (slot.displayId == 0) {
                out("SAFETY: createTrustedVdForSlot: VD got displayId=0 — RELEASING")
                vd.release(); slot.vd = null; return -1
            }
            out("[CREATE_VD] OK displayId=" + slot.displayId + " name=" + vdName)
            return slot.displayId
        } catch (e: Exception) {
            out("[Fission] createTrustedVdForSlot error: " + e.message)
            return -1
        }
    }

    private fun createOverlayLayoutParams(
        target: Display?, w: Int, h: Int): WindowManager.LayoutParams {
        // TYPE_SYSTEM_OVERLAY (2006): INTERNAL_SYSTEM_WINDOW is granted to shell uid=2000.
        val overlayType = 2006 // TYPE_SYSTEM_OVERLAY: INTERNAL_SYSTEM_WINDOW granted to shell uid=2000
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(
            w, h, overlayType, flags, PixelFormat.OPAQUE)
        lp.title = "dashcast_fission_overlay"
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0; lp.y = 0
        // WMS resolves TYPE_SYSTEM_OVERLAY permission against this package — must be "com.android.shell"
        // (uid=2000) which holds INTERNAL_SYSTEM_WINDOW. Matches DevTool pattern.
        lp.packageName = "com.android.shell"
        out("[Fission] createOverlayLayoutParams type=" + overlayType +
            " size=" + w + "×" + h +
            (if (target != null) " display=" + target.displayId else "") +
            " pkg=" + lp.packageName)
        return lp
    }
}

package com.byd.dashcast.cluster

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.byd.dashcast.cluster.display.ProjectionCommandBus
import com.byd.dashcast.cluster.display.ProjectionCommandSequencer
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.proxy.ProxyClient
import com.byd.dashcast.util.AppLogger

/** Main-looper confined state; source reads and IPC run on a bounded independent lane. */
internal class ProjectionAdasCoordinator(
    private val context: Context,
    private val available: () -> Boolean = { AndroidDevice(context).available() },
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pinnedBinder: android.os.IBinder? = null
    private val transport = ProjectionAdasTransport(context, { pinnedBinder }, { restoring })
    private data class Target(val pkg: String, val display: Int,
        val session: ProjectionCommandSequencer.Session)
    @Volatile private var target: Target? = null
    private val targetRequest = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var epoch = 0L
    @Volatile private var permitted = false
    private var started = false
    @Volatile private var restoring = false
    @Volatile private var sequenceRequested = false
    private val restoreCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var poll = 0
    private var platform: Int? = null
    private var platformAt = 0L
    private var proofAt = 0L
    private var recoveryTicket: ProjectionCommandSequencer.CleanupTicket? = null
    private var plan = makePlan()
    private val proofExpiry = Runnable {
        plan.cancel()
        started = false
        proofAt = 0
    }

    private fun makePlan() = ProjectionAdasPlan(object : ProjectionAdasPlan.Io {
        override fun later(delay: Long, action: () -> Unit) { main.postDelayed(action, delay) }
        override fun now() = SystemClock.uptimeMillis()
        override fun valid(): Boolean = permitted && pinnedBinder != null &&
            pinnedBinder === ProxyClient.getProxyDaemonBinder() && pinnedBinder?.isBinderAlive == true && (restoring ||
            (target?.let { ProjectionCommandBus.isCurrent(it.session) } == true &&
                SystemClock.uptimeMillis() - proofAt <= 3_000))
        override fun journal(done: (Boolean) -> Unit) {
            val e = epoch
            transport.call({ e == epoch && permitted }, work = {
                ClusterPrefs.saveProjectionAdasRecovery(context)
            }, done = {
                if (it == true && e == epoch) sequenceRequested = true
                done(it == true && e == epoch)
            })
        }
        override fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) =
            transport.initialize(valid, done)
        override fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            val session = target?.session
            if (!restoring && session != null) {
                ProjectionCommandBus.submitOwned(session, valid,
                    { complete -> transport.command(code, valid, complete) },
                    { ok -> main.post { done(ok) } })
            } else {
                val ticket = recoveryTicket
                if (ticket == null) { done(false); return }
                ProjectionCommandBus.submitCleanup(ticket, valid,
                    { complete -> transport.command(code, valid, complete) },
                    { ok -> main.post { done(ok) } })
            }
        }
        override fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) =
            transport.frames(frame, count, valid, done)
    })

    private fun launch(pkg: String, display: Int, session: ProjectionCommandSequencer.Session) {
        if (display <= 0 || pkg == context.packageName || !ProjectionCommandBus.isCurrent(session)) return
        val next = Target(pkg, display, session)
        if (target == next) {
            if (!permitted && ClusterPrefs.isProjectionAdasEnabled(context)) begin()
            return
        }
        invalidate()
        target = next
        if (ClusterPrefs.isProjectionAdasEnabled(context)) begin()
    }
    private fun begin() {
        if (target == null || !available() || !ProxyClient.supportsProtocol(27)) return
        pinnedBinder = ProxyClient.getProxyDaemonBinder() ?: return
        permitted = true
        restoring = false
        started = false
        poll = 0
        platform = null
        platformAt = 0
        plan = makePlan()
        observe(epoch)
    }
    private fun observe(e: Long) {
        if (e != epoch || restoring || !permitted) return
        val t = target ?: return
        if (!ProjectionCommandBus.isCurrent(t.session)) { stop(true); return }
        if (pinnedBinder !== ProxyClient.getProxyDaemonBinder() || pinnedBinder?.isBinderAlive != true) {
            invalidate()
            return
        }
        val platformDue = poll % 3 == 0
        transport.call({ e == epoch && permitted }, 2_500, work = { binder ->
            val dump = ProxyClient.runShellOnce(binder, "dumpsys activity activities")
            require(dump.length <= 1_048_576)
            val resumed = ResumedProjection.matches(dump, t.display, t.pkg)
            val typed = ProxyClient.readAdasBodywork(binder, 0)
            val raw = ProxyClient.readAdasBodywork(binder, 0x12d0002a).takeIf { it in 0..3 }
            val model = if (platformDue) ProxyClient.readAdasBodywork(binder, 0x40d00010)
                .takeIf { it in 0..254 } else platform
            Observation(resumed, typed, raw, model)
        }) { result ->
            if (e != epoch || restoring || !permitted) return@call
            if (result == null || !result.resumed || result.typedPower != 2 || result.rawPower !in setOf(2, 3) ||
                pinnedBinder !== ProxyClient.getProxyDaemonBinder()) {
                // Loss of proof invalidates in-flight writes immediately; only a fresh poll can rearm.
                main.removeCallbacks(proofExpiry)
                plan.cancel()
                started = false
                proofAt = 0
            } else {
                proofAt = SystemClock.uptimeMillis()
                main.removeCallbacks(proofExpiry)
                main.postDelayed(proofExpiry, 3_000)
                if (platformDue) { platform = result.platform; platformAt = proofAt }
                if (!started && result.typedPower == 2) {
                    started = true
                    plan.start(result.rawPower)
                    AppLogger.i("ProjectionAdas", "projection verified; fixed masking sequence requested")
                }
                if (started) plan.observe(result.rawPower,
                    platform.takeIf { proofAt - platformAt <= 4_000 }, poll % 3 == 0, proofAt)
            }
            poll++
            main.postDelayed({ observe(e) }, 1_000)
        }
    }
    private data class Observation(val resumed: Boolean, val typedPower: Int,
        val rawPower: Int?, val platform: Int?)

    /** Immediately closes all old guards, including commands queued on another thread. */
    @Synchronized private fun invalidate() {
        epoch++
        permitted = false
        plan.cancel()
        main.removeCallbacks(proofExpiry)
        started = false
        restoring = false
        val callbacks = restoreCallbacks.toList()
        restoreCallbacks.clear()
        if (callbacks.isNotEmpty()) main.post { callbacks.forEach { it(false) } }
    }
    private fun stop(restore: Boolean, explicit: Boolean = false, done: (Boolean) -> Unit = {}) {
        if (restoring) { restoreCallbacks += done; return }
        val hadSequence = sequenceRequested
        sequenceRequested = false
        invalidate()
        target = null
        if (!restore || (!explicit && !hadSequence) || !ClusterPrefs.hasProjectionAdasRecovery(context) || !available()) {
            done(false); return
        }
        pinnedBinder = ProxyClient.getProxyDaemonBinder()
        permitted = pinnedBinder != null
        restoring = true
        recoveryTicket = ProjectionCommandBus.cleanupTicket()
        val e = epoch
        restoreCallbacks += done
        plan.restore { ok ->
            if (epoch != e) return@restore
            if (epoch == e) { permitted = false; restoring = false }
            AppLogger.i("ProjectionAdas", "restore delivery=$ok; rendered state unknown")
            val callbacks = restoreCallbacks.toList()
            restoreCallbacks.clear()
            callbacks.forEach { it(ok) }
        }
    }
    private fun preferenceChanged() {
        if (ClusterPrefs.isProjectionAdasEnabled(context)) { if (!permitted) begin() }
        else stop(true, explicit = true)
    }

    companion object {
        @Volatile private var instance: ProjectionAdasCoordinator? = null
        @Synchronized private fun get(context: Context): ProjectionAdasCoordinator =
            instance ?: ProjectionAdasCoordinator(context.applicationContext).also { instance = it }
        fun targetRequested(context: Context, pkg: String?, display: Int) {
            val session = ProjectionCommandBus.currentSession() ?: return
            if (pkg.isNullOrBlank()) return
            val owner = get(context)
            val e = owner.epoch
            val request = owner.targetRequest.incrementAndGet()
            owner.main.post {
                if (owner.epoch == e && owner.targetRequest.get() == request) owner.launch(pkg, display, session)
            }
        }
        fun preferenceChanged(context: Context) { get(context).let { it.main.post { it.preferenceChanged() } } }
        fun stop(context: Context) {
            instance?.let { owner ->
                if (owner.restoring) return
                owner.invalidate()
                val e = owner.epoch
                owner.main.post { if (owner.epoch == e) owner.stop(true) }
            }
        }
        fun sessionEnded(session: ProjectionCommandSequencer.Session) {
            instance?.let { owner ->
                if (owner.target?.session === session && !owner.restoring) {
                    owner.invalidate()
                    val e = owner.epoch
                    owner.main.post { if (owner.epoch == e) owner.stop(true) }
                }
            }
        }
        /** Used before the retained one-shot recovery: it must not race an automatic hide. */
        fun cancelForManualRecovery() { instance?.invalidate() }
        fun restore(context: Context, done: (Boolean) -> Unit) {
            NativeAdasWindowControl.cancelPending()
            val owner = get(context)
            if (owner.restoring) {
                if (Looper.myLooper() == Looper.getMainLooper()) owner.restoreCallbacks += done
                else owner.main.post {
                    if (owner.restoring) owner.restoreCallbacks += done else done(false)
                }
                return
            }
            owner.invalidate()
            val e = owner.epoch
            owner.main.post { if (owner.epoch == e) owner.stop(true, explicit = true, done = done) else done(false) }
        }
    }
}

internal object ResumedProjection {
    private val display = Regex("^\\s*Display #?(\\d+)(?:\\s|:|$)")
    private val component = Regex("(?:^|[\\s{])([A-Za-z0-9_.$]+)/(\\.?[A-Za-z0-9_.$]+)(?:[\\s}])")
    fun matches(dump: String, displayId: Int, packageName: String): Boolean {
        if (displayId <= 0 || dump.length > 1_048_576 || dump.contains("[output truncated]")) return false
        var currentDisplay: Int? = null
        val resumed = linkedSetOf<String>()
        for (line in dump.lineSequence()) {
            display.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { currentDisplay = it }
            if (currentDisplay == displayId &&
                (line.contains("mResumedActivity:") || line.contains("topResumedActivity="))) {
                val match = component.find(line) ?: return false
                resumed += match.groupValues[1] + "/" + match.groupValues[2]
            }
        }
        return resumed.size == 1 && resumed.single().substringBefore('/') == packageName
    }
}

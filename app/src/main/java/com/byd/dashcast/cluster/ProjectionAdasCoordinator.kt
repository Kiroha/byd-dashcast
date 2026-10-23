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
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

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
    // A restored projection remains a candidate, never authority to resume masking by itself.
    @Volatile private var retryTarget: Target? = null
    @Volatile private var restoredSession: ProjectionCommandSequencer.Session? = null
    @Volatile private var hideRequest: HideRequest? = null
    private val targetRequest = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var epoch = 0L
    @Volatile private var permitted = false
    private var started = false
    @Volatile private var restoring = false
    @Volatile private var sequenceRequested = false
    // Explicit restoration does not end the projection whose Stop still owes a full sequence.
    @Volatile private var recoverySession: ProjectionCommandSequencer.Session? = null
    // Unlike the saved journal alone, known in-process mutation debt follows a replacement owner.
    @Volatile private var recoveryDebt = false
    @Volatile private var deferredStop: DeferredStop? = null
    private var restoreQuietUntil = 0L
    private val restoreCallbacks = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var poll = 0
    private var platform: Int? = null
    private var platformAt = 0L
    private var proofAt = 0L
    private var recoveryTicket: ProjectionCommandSequencer.CleanupTicket? = null
    private var plan = makePlan()
    private val proofExpiry = Runnable {
        val explicitPending = hideRequest != null
        finishHide(NativeAdasWindowControl.Result.UNAVAILABLE)
        plan.cancel()
        started = false
        proofAt = 0
        if (explicitPending) { invalidate(); target = null }
    }

    private fun makePlan() = ProjectionAdasPlan(object : ProjectionAdasPlan.Io {
        override fun later(delay: Long, action: () -> Unit) { main.postDelayed(action, delay) }
        override fun now() = SystemClock.uptimeMillis()
        override fun valid(): Boolean = permitted && pinnedBinder != null &&
            hideRequest?.cancelled != true &&
            pinnedBinder === ProxyClient.getProxyDaemonBinder() && pinnedBinder?.isBinderAlive == true && (if (restoring)
            recoveryTicket?.let(ProjectionCommandBus::cleanupCurrent) == true else
            (target?.let { ProjectionCommandBus.isCurrent(it.session) } == true &&
                SystemClock.uptimeMillis() - proofAt < PROOF_LIFETIME_MS))
        override fun journal(done: (Boolean) -> Unit) {
            val e = epoch
            transport.call({ e == epoch && permitted }, work = {
                ClusterPrefs.saveProjectionAdasRecovery(context)
            }, done = {
                if (it == true && e == epoch) {
                    sequenceRequested = true
                    recoverySession = target?.session
                    recoveryDebt = true
                }
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
        if (restoring || deferredStop != null) return
        val next = Target(pkg, display, session)
        if (restoredSession === session) { retryTarget = next; return }
        if (target == next) {
            if (!permitted && ClusterPrefs.isProjectionAdasEnabled(context)) begin()
            return
        }
        invalidate()
        target = next
        retryTarget = next
        if (ClusterPrefs.isProjectionAdasEnabled(context)) begin()
    }
    private fun begin() {
        if (target == null || !available() || !ProxyClient.supportsProtocol(27) ||
            !NativeAdasWindowControl.cancelPending()) {
            finishHide(NativeAdasWindowControl.Result.UNAVAILABLE)
            return
        }
        pinnedBinder = ProxyClient.getProxyDaemonBinder()
        if (pinnedBinder == null) { finishHide(NativeAdasWindowControl.Result.UNAVAILABLE); return }
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
            finishHide(NativeAdasWindowControl.Result.UNAVAILABLE)
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
                if (hideRequest != null) {
                    finishHide(NativeAdasWindowControl.Result.UNAVAILABLE)
                    invalidate()
                    target = null
                    return@call
                }
            } else {
                proofAt = SystemClock.uptimeMillis()
                main.removeCallbacks(proofExpiry)
                main.postDelayed(proofExpiry, PROOF_LIFETIME_MS)
                if (platformDue) { platform = result.platform; platformAt = proofAt }
                if (!started && result.typedPower == 2) {
                    started = true
                    val explicitRequest = hideRequest
                    plan.start(result.rawPower, if (explicitRequest == null) null else { delivered ->
                        if (e == epoch && hideRequest === explicitRequest) {
                            finishHide(if (delivered) NativeAdasWindowControl.Result.HIDE_SENT
                                else NativeAdasWindowControl.Result.SEND_FAILED)
                            if (delivered) restoredSession = null
                            if (!delivered) { invalidate(); target = null }
                        }
                    })
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
        val deferred = deferredStop
        deferredStop = null
        if (deferred != null) main.post { deferred.complete(false) }
        permitted = false
        plan.cancel()
        main.removeCallbacks(proofExpiry)
        started = false
        restoring = false
        finishHide(NativeAdasWindowControl.Result.CANCELLED)
        val callbacks = restoreCallbacks.toList()
        restoreCallbacks.clear()
        if (callbacks.isNotEmpty()) main.post { callbacks.forEach { it(false) } }
    }
    private fun stop(restore: Boolean, explicit: Boolean = false, retainTarget: Boolean = false,
                     ticket: ProjectionCommandSequencer.CleanupTicket? = null,
                     done: (Boolean) -> Unit = {}) {
        if (!retainTarget) retryTarget = null
        if (restoring) { restoreCallbacks += done; return }
        if (retainTarget) {
            retryTarget = target ?: retryTarget
            restoredSession = retryTarget?.session ?: recoverySession
        }
        val hadSequence = sequenceRequested || recoverySession != null || recoveryDebt
        sequenceRequested = false
        if (!retainTarget) recoverySession = null
        invalidate()
        target = null
        if (!restore || (!explicit && !hadSequence) || !ClusterPrefs.hasProjectionAdasRecovery(context) || !available()) {
            done(false); return
        }
        pinnedBinder = ProxyClient.getProxyDaemonBinder()
        permitted = pinnedBinder != null
        restoring = true
        recoveryTicket = ticket ?: ProjectionCommandBus.cleanupTicket()
        val e = epoch
        restoreCallbacks += done
        plan.restore { ok ->
            if (epoch != e) return@restore
            if (epoch == e) { permitted = false; restoring = false }
            if (ok) recoveryDebt = false
            restoreQuietUntil = SystemClock.uptimeMillis() + 1_000
            AppLogger.i("ProjectionAdas", "restore delivery=$ok; rendered state unknown")
            val callbacks = restoreCallbacks.toList()
            restoreCallbacks.clear()
            callbacks.forEach { it(ok) }
        }
    }
    private fun preferenceChanged() {
        if (ClusterPrefs.isProjectionAdasEnabled(context)) {
            if (!permitted && !restoring && deferredStop == null && restoredSession !== target?.session) begin()
        }
        else stop(true, explicit = true)
    }

    private fun finishHide(result: NativeAdasWindowControl.Result) {
        val request = hideRequest ?: return
        hideRequest = null
        main.post { request.complete(result) }
    }

    private class HideRequest(callback: (NativeAdasWindowControl.Result) -> Unit,
                              private val onCancel: () -> Unit) : Closeable {
        private val callback = AtomicReference<((NativeAdasWindowControl.Result) -> Unit)?>(callback)
        @Volatile var cancelled = false
            private set
        fun complete(result: NativeAdasWindowControl.Result) { callback.getAndSet(null)?.invoke(result) }
        override fun close() {
            if (callback.getAndSet(null) != null) {
                cancelled = true
                onCancel()
            }
        }
    }
    private class DeferredStop(val epoch: Long, val ticket: ProjectionCommandSequencer.CleanupTicket) {
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        private var callbacks: MutableList<(Boolean) -> Unit>? = mutableListOf()
        @Synchronized fun add(callback: (Boolean) -> Unit): Boolean {
            val pending = callbacks ?: return false
            pending += callback
            return true
        }
        fun complete(ok: Boolean) {
            val pending = synchronized(this) { callbacks.also { callbacks = null } } ?: return
            pending.forEach { it(ok) }
        }
    }

    companion object {
        // The 1-second poll gap plus a bounded 2.5-second read leaves 500 ms of scheduling margin.
        private const val PROOF_LIFETIME_MS = 4_000L
        @Volatile private var instance: ProjectionAdasCoordinator? = null
        fun idleForDiagnostic(): Boolean = instance?.let {
            it.target == null && it.retryTarget == null && !it.recoveryDebt && !it.restoring &&
                it.deferredStop == null && !it.sequenceRequested && it.hideRequest == null && it.recoverySession == null
        } ?: true
        @Synchronized private fun get(context: Context): ProjectionAdasCoordinator =
            instance ?: ProjectionAdasCoordinator(context.applicationContext).also { instance = it }
        fun targetRequested(context: Context, pkg: String?, display: Int) {
            if (ProjectionCommandBus.diagnosticReserved()) return
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
                owner.retryTarget = null
                if (owner.deferredStop != null) return
                if (owner.restoring) return
                owner.invalidate()
                val e = owner.epoch
                owner.main.post { if (owner.epoch == e) owner.stop(true) }
            }
        }
        fun sessionEnded(session: ProjectionCommandSequencer.Session) {
            instance?.let { owner ->
                if (owner.retryTarget?.session === session) owner.retryTarget = null
                if (owner.deferredStop != null) return
                if (owner.target?.session === session && !owner.restoring) {
                    owner.invalidate()
                    val e = owner.epoch
                    owner.main.post { if (owner.epoch == e) owner.stop(true) }
                }
            }
        }
        /** Used before the retained one-shot recovery: it must not race an automatic hide. */
        fun cancelForManualRecovery() { instance?.let { it.retryTarget = null; it.invalidate() } }
        fun prepareManualRequest(action: NativeAdasWindowControl.Action): Boolean {
            if (ProjectionCommandBus.diagnosticReserved()) return false
            val owner = instance ?: return true
            synchronized(owner) {
                // Saved-value recovery must remain usable even if native teardown IPC is wedged.
                if (owner.restoring || owner.deferredStop != null)
                    return action == NativeAdasWindowControl.Action.RESTORE
                owner.invalidate()
                return true
            }
        }
        fun projectionReplaced() { instance?.let {
            it.recoverySession = null
            it.sequenceRequested = false
            it.retryTarget = null
            it.restoredSession = null
            it.target = null
            it.invalidate()
        } }

        /** Close hide authority now; restore only after this owner's final teardown command. */
        fun prepareStop(context: Context, session: ProjectionCommandSequencer.Session? = null): Runnable {
            val owner = instance ?: return Runnable {}
            synchronized(owner) {
                if (session != null && !ProjectionCommandBus.isCurrent(session)) return Runnable {}
                if (owner.deferredStop != null) return Runnable {}
                val ticket = ProjectionCommandBus.cleanupTicket()
                owner.restoredSession = owner.target?.session ?: owner.retryTarget?.session ?: owner.recoverySession
                owner.invalidate()
                owner.target = null
                owner.retryTarget = null
                val pending = DeferredStop(owner.epoch, ticket)
                owner.deferredStop = pending
                return Runnable {
                    if (pending.completed.compareAndSet(false, true)) owner.main.postDelayed({
                        if (owner.deferredStop === pending && owner.epoch == pending.epoch) {
                            owner.deferredStop = null
                            owner.stop(true, ticket = pending.ticket, done = pending::complete)
                        }
                    }, 1_000)
                }
            }
        }

        /** Explicit rearm reuses the projection owner, but none of its previous read proof. */
        fun requestHide(context: Context, done: (NativeAdasWindowControl.Result) -> Unit): Closeable {
            if (ProjectionCommandBus.diagnosticReserved()) return HideRequest(done) {}.also {
                it.complete(NativeAdasWindowControl.Result.BUSY)
            }
            val owner = get(context)
            lateinit var request: HideRequest
            request = HideRequest(done) {
                if (owner.hideRequest === request) { owner.invalidate(); owner.target = null }
            }
            owner.main.post {
                if (request.cancelled) return@post
                if (ProjectionCommandBus.diagnosticReserved()) {
                    request.complete(NativeAdasWindowControl.Result.BUSY); return@post
                }
                if (owner.restoring || owner.deferredStop != null || owner.hideRequest != null) {
                    request.complete(NativeAdasWindowControl.Result.BUSY)
                    return@post
                }
                val candidate = owner.target ?: owner.retryTarget
                if (!ClusterPrefs.isProjectionAdasEnabled(context) || candidate == null ||
                    !ProjectionCommandBus.isCurrent(candidate.session)) {
                    request.complete(NativeAdasWindowControl.Result.UNAVAILABLE)
                    return@post
                }
                owner.invalidate()
                owner.target = candidate
                owner.restoredSession = candidate.session
                owner.hideRequest = request
                owner.proofAt = 0
                val e = owner.epoch
                owner.main.postDelayed({
                    if (owner.epoch == e && owner.hideRequest === request && !request.cancelled) owner.begin()
                }, maxOf(0, owner.restoreQuietUntil - SystemClock.uptimeMillis()))
            }
            return request
        }
        fun restore(context: Context, done: (Boolean) -> Unit) {
            NativeAdasWindowControl.cancelPending()
            val owner = get(context)
            owner.deferredStop?.let { pending ->
                if (!pending.add { ok -> owner.main.post { done(ok) } }) owner.main.post { done(false) }
                return
            }
            if (owner.restoring) {
                if (Looper.myLooper() == Looper.getMainLooper()) owner.restoreCallbacks += done
                else owner.main.post {
                    if (owner.restoring) owner.restoreCallbacks += done else done(false)
                }
                return
            }
            owner.invalidate()
            val e = owner.epoch
            owner.main.post { if (owner.epoch == e)
                owner.stop(true, explicit = true, retainTarget = true, done = done) else done(false) }
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

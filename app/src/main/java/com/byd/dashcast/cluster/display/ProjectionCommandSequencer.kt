package com.byd.dashcast.cluster.display

import android.content.Context

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger
import java.util.ArrayDeque

/** Orders AutoContainer projection commands and drops work owned by superseded sessions. */
internal class ProjectionCommandSequencer {

    class Session internal constructor(internal val generation: Long)

    class CleanupTicket internal constructor(internal val generation: Long)

    sealed class Result {
        data class Success(val output: String?) : Result()
        data class Error(val message: String?) : Result()
    }

    fun interface Completion {
        fun complete(result: Result)
    }

    private class Pending(
        val session: Session,
        val launch: (Completion) -> Unit,
        val completion: Completion,
        val cleanup: CleanupTicket? = null,
        val cleanupValid: (() -> Boolean)? = null,
    ) {
        var completed = false
    }

    private val queue = ArrayDeque<Pending>()
    private var generation = 0L
    private var ownerSession: Session? = null
    private var retiredGeneration: Long? = null
    private var inFlight = false
    private var diagnostic = false

    @Synchronized fun diagnosticReserved(): Boolean = diagnostic

    /** Never retire projection ownership or queued cleanup merely to run a diagnostic. */
    @Synchronized fun reserveDiagnostic(): Session? {
        if (diagnostic || ownerSession != null || inFlight || queue.isNotEmpty()) return null
        retiredGeneration = null
        diagnostic = true
        return Session(++generation)
    }

    @Synchronized
    fun beginSession(): Session {
        check(!diagnostic) { "Native cluster diagnostic owns the command lane" }
        retiredGeneration = null
        return Session(++generation).also { ownerSession = it }
    }

    @Synchronized fun currentSession(): Session? = ownerSession

    @Synchronized fun cleanupTicket(): CleanupTicket = CleanupTicket(generation)

    @Synchronized fun cleanupCurrent(ticket: CleanupTicket): Boolean =
        ticket.generation == generation || ticket.generation == retiredGeneration

    @Synchronized
    fun isCurrent(session: Session): Boolean = session.generation == generation

    /** Only bounded local state updates belong here; never hold ownership across I/O. */
    @Synchronized
    fun runIfCurrent(session: Session, action: () -> Unit) {
        if (isCurrent(session)) action()
    }

    @Synchronized
    fun runIfIdle(action: () -> Unit) {
        if (ownerSession == null && !diagnostic) action()
    }

    fun endSession(session: Session) {
        val next = synchronized(this) {
            if (session.generation == generation) { retiredGeneration = generation; generation++; ownerSession = null; diagnostic = false }
            takeNextLocked()
        }
        dispatch(next)
    }

    fun submit(
        session: Session,
        launch: (Completion) -> Unit,
        completion: Completion,
    ) {
        val next = synchronized(this) {
            queue.addLast(Pending(session, launch, completion))
            takeNextLocked()
        }
        dispatch(next)
    }

    /** Read-only ticket survives its owner's end, but never a newer beginSession. */
    fun submitCleanup(ticket: CleanupTicket, valid: () -> Boolean,
                      launch: (Completion) -> Unit, completion: Completion) {
        val next = synchronized(this) {
            queue.addLast(Pending(Session(ticket.generation), launch, completion, ticket, valid))
            takeNextLocked()
        }
        dispatch(next)
    }

    private fun takeNextLocked(): Pending? {
        if (inFlight) return null
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (pending.cleanup != null) {
                if (!cleanupCurrent(pending.cleanup)) {
                    pending.completion.complete(Result.Error("cleanup retired"))
                    continue
                }
            } else if (pending.session.generation != generation) continue
            inFlight = true
            return pending
        }
        return null
    }

    private fun dispatch(pending: Pending?) {
        if (pending == null) return
        if (pending.cleanup != null &&
            (!synchronized(this) { cleanupCurrent(pending.cleanup) } || pending.cleanupValid?.invoke() != true)) {
            complete(pending, Result.Error("cleanup retired"))
            return
        }
        try {
            synchronized(this) {
                // A new owner may have arrived after dequeue but before the asynchronous starter.
                if (pending.cleanup == null && !isCurrent(pending.session)) {
                    complete(pending, Result.Error("projection retired"))
                    return
                }
                pending.launch(Completion { result -> complete(pending, result) })
            }
        } catch (t: Throwable) {
            complete(pending, Result.Error("${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    private fun complete(pending: Pending, result: Result) {
        val cleanupAllowed = pending.cleanupValid?.invoke() == true
        val next = synchronized(this) {
            if (pending.completed) return
            pending.completed = true
            inFlight = false

            // Keep result delivery and any follow-up enqueue in the same ordering decision as a
            // competing beginSession(). The callback is small and only posts or queues more work.
            if (pending.cleanup != null) {
                pending.completion.complete(if (cleanupCurrent(pending.cleanup) && cleanupAllowed)
                    result else Result.Error("cleanup retired"))
            } else if (pending.session.generation == generation) {
                pending.completion.complete(result)
            }
            takeNextLocked()
        }
        dispatch(next)
    }
}

/** Process-wide adapter from the sequencer to AdbLocalClient's asynchronous transport. */
internal object ProjectionCommandBus {
    private const val TAG = "ProjectionCommandBus"
    private val sequencer = ProjectionCommandSequencer()
    private var externalCommands = 0
    fun claimExternalCommand(): Boolean = synchronized(sequencer) {
        if (sequencer.diagnosticReserved()) return@synchronized false
        externalCommands++; true
    }
    fun releaseExternalCommand() = synchronized(sequencer) { check(externalCommands > 0); externalCommands-- }

    fun currentSession(): ProjectionCommandSequencer.Session? =
        sequencer.currentSession()

    fun diagnosticReserved(): Boolean = sequencer.diagnosticReserved()

    fun reserveDiagnostic(): ProjectionCommandSequencer.Session? = synchronized(sequencer) {
        if (externalCommands > 0 || !com.byd.dashcast.cluster.ProjectionAdasCoordinator.idleForDiagnostic() ||
            com.byd.dashcast.cluster.NativeAdasWindowControl.isBusy()) return@synchronized null
        sequencer.reserveDiagnostic()
    }

    fun tryBeginSession(): ProjectionCommandSequencer.Session? = synchronized(sequencer) {
        if (diagnosticReserved()) return@synchronized null
        beginSession()
    }

    fun beginSession(): ProjectionCommandSequencer.Session = synchronized(sequencer) {
        check(!diagnosticReserved()) { "Native cluster diagnostic owns the command lane" }
        com.byd.dashcast.cluster.ProjectionAdasCoordinator.projectionReplaced()
        sequencer.beginSession()
    }

    fun isCurrent(session: ProjectionCommandSequencer.Session): Boolean =
        sequencer.isCurrent(session)

    fun runIfCurrent(session: ProjectionCommandSequencer.Session, action: () -> Unit) =
        sequencer.runIfCurrent(session, action)

    fun runIfIdle(action: () -> Unit) = sequencer.runIfIdle(action)

    fun endSession(session: ProjectionCommandSequencer.Session) {
        com.byd.dashcast.cluster.ProjectionAdasCoordinator.sessionEnded(session)
        sequencer.endSession(session)
    }

    /** Uses the existing owner queue with the caller's single-attempt transport. */
    fun submitOwned(session: ProjectionCommandSequencer.Session, valid: () -> Boolean,
                    launch: ((Boolean) -> Unit) -> Unit, done: (Boolean) -> Unit) {
        sequencer.submit(session, { completion ->
            if (!valid()) completion.complete(ProjectionCommandSequencer.Result.Error("retired"))
            else launch { ok -> completion.complete(if (ok) ProjectionCommandSequencer.Result.Success(null)
                else ProjectionCommandSequencer.Result.Error("delivery failed")) }
        }, { result -> done(result is ProjectionCommandSequencer.Result.Success) })
    }

    fun cleanupTicket(): ProjectionCommandSequencer.CleanupTicket = sequencer.cleanupTicket()

    fun cleanupCurrent(ticket: ProjectionCommandSequencer.CleanupTicket): Boolean =
        sequencer.cleanupCurrent(ticket)

    fun submitCleanup(ticket: ProjectionCommandSequencer.CleanupTicket, valid: () -> Boolean,
                      launch: ((Boolean) -> Unit) -> Unit, done: (Boolean) -> Unit) {
        sequencer.submitCleanup(ticket, valid, { completion ->
            if (!valid()) completion.complete(ProjectionCommandSequencer.Result.Error("retired"))
            else launch { ok -> completion.complete(if (ok) ProjectionCommandSequencer.Result.Success(null)
                else ProjectionCommandSequencer.Result.Error("delivery failed")) }
        }, { result -> done(result is ProjectionCommandSequencer.Result.Success) })
    }

    fun sendInfo(
        context: Context,
        session: ProjectionCommandSequencer.Session,
        type: Int,
        info: Int,
        value: String,
        callback: AdbLocalClient.Callback,
    ) {
        sequencer.submit(
            session,
            launch = { completion ->
                val watchdog = ProjectionTransportRecovery.watchdog(
                    context,
                    Runnable {
                        completion.complete(
                            ProjectionCommandSequencer.Result.Error(
                                "typed projection command timed out; proxy daemon reset"
                            )
                        )
                    },
                )
                AdbLocalClient.sendInfo(
                    context.applicationContext,
                    type,
                    info,
                    value,
                    object : AdbLocalClient.Callback {
                        override fun onSuccess(out: String?) {
                            if (watchdog.shouldAbortFallback()) return
                            completion.complete(ProjectionCommandSequencer.Result.Success(out))
                        }

                        override fun onError(err: String?) {
                            if (watchdog.shouldAbortFallback()) return
                            completion.complete(ProjectionCommandSequencer.Result.Error(err))
                        }
                    },
                    watchdog,
                )
            },
            completion = ProjectionCommandSequencer.Completion { result ->
                when (result) {
                    is ProjectionCommandSequencer.Result.Success -> callback.onSuccess(result.output)
                    is ProjectionCommandSequencer.Result.Error -> callback.onError(result.message)
                }
            },
        )
        AppLogger.d(TAG, "queued session=${session.generation} command=$type/$info")
    }

}

package com.byd.dashcast.cluster.display

import android.content.Context

import com.byd.dashcast.infrastructure.AdbLocalClient
import com.byd.dashcast.util.AppLogger
import java.util.ArrayDeque

/** Orders AutoContainer projection commands and drops work owned by superseded sessions. */
internal class ProjectionCommandSequencer {

    class Session internal constructor(internal val generation: Long)

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
    ) {
        var completed = false
    }

    private val queue = ArrayDeque<Pending>()
    private var generation = 0L
    private var inFlight = false

    @Synchronized
    fun beginSession(): Session = Session(++generation)

    fun endSession(session: Session) {
        val next = synchronized(this) {
            if (session.generation == generation) generation++
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

    private fun takeNextLocked(): Pending? {
        if (inFlight) return null
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (pending.session.generation != generation) continue
            inFlight = true
            return pending
        }
        return null
    }

    private fun dispatch(pending: Pending?) {
        if (pending == null) return
        try {
            pending.launch(Completion { result -> complete(pending, result) })
        } catch (t: Throwable) {
            complete(pending, Result.Error("${t.javaClass.simpleName}: ${t.message}"))
        }
    }

    private fun complete(pending: Pending, result: Result) {
        val next = synchronized(this) {
            if (pending.completed) return
            pending.completed = true
            inFlight = false

            // Keep result delivery and any follow-up enqueue in the same ordering decision as a
            // competing beginSession(). The callback is small and only posts or queues more work.
            if (pending.session.generation == generation) {
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

    fun beginSession(): ProjectionCommandSequencer.Session = sequencer.beginSession()

    fun endSession(session: ProjectionCommandSequencer.Session) {
        sequencer.endSession(session)
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
                AdbLocalClient.sendInfo(
                    context.applicationContext,
                    type,
                    info,
                    value,
                    object : AdbLocalClient.Callback {
                        override fun onSuccess(out: String?) {
                            completion.complete(ProjectionCommandSequencer.Result.Success(out))
                        }

                        override fun onError(err: String?) {
                            completion.complete(ProjectionCommandSequencer.Result.Error(err))
                        }
                    },
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
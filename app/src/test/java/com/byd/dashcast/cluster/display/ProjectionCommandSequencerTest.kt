package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionCommandSequencerTest {

    @Test fun `owner snapshot is published atomically and stale end cannot clear new owner`() {
        val sequencer = ProjectionCommandSequencer()
        val old = sequencer.beginSession()
        val current = sequencer.beginSession()
        sequencer.endSession(old)
        assertTrue(sequencer.currentSession() === current)
        sequencer.endSession(current)
        assertEquals(null, sequencer.currentSession())
    }

    @Test
    fun `new session invalidates old display ownership immediately`() {
        val sequencer = ProjectionCommandSequencer()
        val old = sequencer.beginSession()
        assertTrue(sequencer.isCurrent(old))

        val current = sequencer.beginSession()

        assertFalse(sequencer.isCurrent(old))
        assertTrue(sequencer.isCurrent(current))
    }

    @Test
    fun `new activation waits for in-flight stop and discards its restore callback`() {
        val sequencer = ProjectionCommandSequencer()
        val launched = mutableListOf<Int>()
        val pending = mutableMapOf<Int, ProjectionCommandSequencer.Completion>()

        fun submit(
            session: ProjectionCommandSequencer.Session,
            command: Int,
            onResult: (ProjectionCommandSequencer.Result) -> Unit = {},
        ) {
            sequencer.submit(
                session,
                launch = { completion ->
                    launched += command
                    pending[command] = completion
                },
                completion = ProjectionCommandSequencer.Completion(onResult),
            )
        }

        val stoppingSession = sequencer.beginSession()
        submit(stoppingSession, 18) { result ->
            if (result is ProjectionCommandSequencer.Result.Success) {
                submit(stoppingSession, 0)
            }
        }

        val activatingSession = sequencer.beginSession()
        submit(activatingSession, 16)
        assertEquals(listOf(18), launched)

        pending.remove(18)!!.complete(ProjectionCommandSequencer.Result.Success("ok"))

        assertEquals(listOf(18, 16), launched)
        assertFalse("stale cmd=0 must never reach the transport", launched.contains(0))
    }

    @Test
    fun `queued stop barrier survives restart but its stale restore does not`() {
        val sequencer = ProjectionCommandSequencer()
        val launched = mutableListOf<Int>()
        val pending = mutableMapOf<Int, ProjectionCommandSequencer.Completion>()

        fun submit(
            session: ProjectionCommandSequencer.Session,
            command: Int,
            barrier: Boolean = false,
            onResult: (ProjectionCommandSequencer.Result) -> Unit = {},
        ) {
            sequencer.submit(
                session,
                launch = { completion ->
                    launched += command
                    pending[command] = completion
                },
                completion = ProjectionCommandSequencer.Completion(onResult),
                dispatchIfSuperseded = barrier,
            )
        }

        val old = sequencer.beginSession()
        submit(old, 16)
        submit(old, 18, barrier = true) { result ->
            if (result is ProjectionCommandSequencer.Result.Success) submit(old, 0)
        }
        val current = sequencer.beginSession()
        submit(current, 116)

        pending.remove(16)!!.complete(ProjectionCommandSequencer.Result.Success("old done"))
        assertEquals(listOf(16, 18), launched)

        pending.remove(18)!!.complete(ProjectionCommandSequencer.Result.Success("stopped"))
        assertEquals(listOf(16, 18, 116), launched)
        assertFalse("stale restore must remain suppressed", launched.contains(0))
    }

    @Test fun `retired cleanup waits for in flight owner and completes only once`() {
        val queue = ProjectionCommandSequencer()
        val owner = queue.beginSession()
        val launched = mutableListOf<Int>()
        var first: ProjectionCommandSequencer.Completion? = null
        var restore: ProjectionCommandSequencer.Completion? = null
        var callbacks = 0
        queue.submit(owner, { launched += 16; first = it }, {})
        val ticket = queue.cleanupTicket()
        queue.endSession(owner)
        queue.submitCleanup(ticket, { true }, { launched += 12; restore = it }, { callbacks++ })
        assertEquals(listOf(16), launched)
        first!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(listOf(16, 12), launched)
        restore!!.complete(ProjectionCommandSequencer.Result.Success(null))
        restore!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(1, callbacks)
    }

    @Test fun `new owner discards queued retired cleanup without replacing its session`() {
        val queue = ProjectionCommandSequencer()
        val owner = queue.beginSession()
        var first: ProjectionCommandSequencer.Completion? = null
        var writes = 0
        var result: ProjectionCommandSequencer.Result? = null
        queue.submit(owner, { first = it }, {})
        val ticket = queue.cleanupTicket()
        queue.endSession(owner)
        queue.submitCleanup(ticket, { true }, { writes++ }, { result = it })
        val next = queue.beginSession()
        first!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(0, writes)
        assertTrue(result is ProjectionCommandSequencer.Result.Error)
        assertTrue(queue.isCurrent(next))
    }

    @Test fun `retired cleanup checks revocation again before dispatch`() {
        val queue = ProjectionCommandSequencer()
        val owner = queue.beginSession()
        var first: ProjectionCommandSequencer.Completion? = null
        var allowed = true
        var writes = 0
        queue.submit(owner, { first = it }, {})
        queue.submitCleanup(queue.cleanupTicket(), { allowed }, { writes++ }, {})
        allowed = false
        first!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(0, writes)
    }
}
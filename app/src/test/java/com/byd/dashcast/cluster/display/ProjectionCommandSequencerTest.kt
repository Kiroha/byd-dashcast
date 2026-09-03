package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionCommandSequencerTest {

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
}
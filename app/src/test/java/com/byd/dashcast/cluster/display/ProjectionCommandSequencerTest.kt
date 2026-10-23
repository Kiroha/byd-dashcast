package com.byd.dashcast.cluster.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectionCommandSequencerTest {
    @Test fun `diagnostic refuses existing ownership queued cleanup and in flight work`() {
        val queue = ProjectionCommandSequencer()
        val owner = queue.beginSession()
        assertEquals(null, queue.reserveDiagnostic())
        var completion: ProjectionCommandSequencer.Completion? = null
        queue.submit(owner, { completion = it }, {})
        queue.endSession(owner)
        assertEquals(null, queue.reserveDiagnostic())
        completion!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertTrue(queue.reserveDiagnostic() != null)
    }
    @Test fun `diagnostic retires old cleanup tickets and blocks new projection until released`() {
        val queue = ProjectionCommandSequencer(); val owner = queue.beginSession()
        val old = queue.cleanupTicket(); queue.endSession(owner)
        val diagnostic = queue.reserveDiagnostic()!!
        assertFalse(queue.cleanupCurrent(old))
        assertThrows(IllegalStateException::class.java) { queue.beginSession() }
        var writes = 0
        queue.submitCleanup(old, { true }, { writes++ }, {})
        assertEquals(0, writes)
        queue.endSession(diagnostic); assertTrue(queue.beginSession() != null)
    }
    @Test fun `external legacy command admission and diagnostic reservation exclude one another`() {
        assertTrue(ProjectionCommandBus.claimExternalCommand())
        try { assertEquals(null, ProjectionCommandBus.reserveDiagnostic()) }
        finally { ProjectionCommandBus.releaseExternalCommand() }
        val lease = ProjectionCommandBus.reserveDiagnostic()!!
        try {
            assertFalse(ProjectionCommandBus.claimExternalCommand())
            assertEquals(null, ProjectionCommandBus.tryBeginSession())
        } finally { ProjectionCommandBus.endSession(lease) }
    }

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
    fun `new activation discards queued old stop and its restore`() {
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

        val old = sequencer.beginSession()
        submit(old, 16)
        submit(old, 18) { result ->
            if (result is ProjectionCommandSequencer.Result.Success) submit(old, 0)
        }
        val current = sequencer.beginSession()
        submit(current, 16)

        assertEquals("a new owner cannot overtake an entered transaction", listOf(16), launched)

        pending.remove(16)!!.complete(ProjectionCommandSequencer.Result.Success("old done"))
        assertEquals(listOf(16, 16), launched)
        assertFalse("an obsolete close must never reach the transport", launched.contains(18))
        assertFalse("stale restore must remain suppressed", launched.contains(0))
    }

    @Test fun `current stop still sends close then refresh exactly once`() {
        val queue = ProjectionCommandSequencer()
        val owner = queue.beginSession()
        val launched = mutableListOf<Int>()
        var close: ProjectionCommandSequencer.Completion? = null
        var refresh: ProjectionCommandSequencer.Completion? = null
        queue.submit(owner, { launched += 18; close = it }, {
            queue.submit(owner, { launched += 0; refresh = it }, { queue.endSession(owner) })
        })
        assertEquals(listOf(18), launched)
        close!!.complete(ProjectionCommandSequencer.Result.Success(null))
        close!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(listOf(18, 0), launched)
        refresh!!.complete(ProjectionCommandSequencer.Result.Success(null))
        assertEquals(null, queue.currentSession())
    }

    @Test fun `replacement between dequeue and dispatch cannot launch the old command`() {
        val queue = ProjectionCommandSequencer()
        val old = queue.beginSession()
        var writes = 0
        // Model the exact gap between takeNextLocked and dispatch without a timing-sensitive race.
        val pendingType = Class.forName("${ProjectionCommandSequencer::class.java.name}\$Pending")
        val constructor = pendingType.declaredConstructors.single { it.parameterCount == 5 }
            .apply { isAccessible = true }
        val pending = constructor.newInstance(old,
            { _: ProjectionCommandSequencer.Completion -> writes++ },
            ProjectionCommandSequencer.Completion {}, null, null)
        queue.beginSession()
        val dispatch = ProjectionCommandSequencer::class.java.getDeclaredMethod("dispatch", pendingType)
            .apply { isAccessible = true }
        dispatch.invoke(queue, pending)
        assertEquals(0, writes)
    }

    @Test fun `local owner state update is atomic with replacement and retired action is ignored`() {
        val queue = ProjectionCommandSequencer()
        val old = queue.beginSession()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempted = CountDownLatch(1)
        val replaced = CountDownLatch(1)
        var value = "old"
        val clearing = Thread {
            queue.runIfCurrent(old) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                value = "cleared"
            }
        }
        val replacing = Thread {
            attempted.countDown()
            queue.beginSession()
            value = "new"
            replaced.countDown()
        }
        try {
            clearing.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            replacing.start()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertEquals(1L, replaced.count)
        } finally {
            release.countDown()
            clearing.join(5000)
            replacing.join(5000)
        }
        assertFalse(clearing.isAlive)
        assertFalse(replacing.isAlive)
        assertEquals("new", value)
        queue.runIfCurrent(old) { value = "stale" }
        assertEquals("new", value)
    }

    @Test fun `idle recovery admission cannot act on a projection or diagnostic owner`() {
        val queue = ProjectionCommandSequencer()
        var calls = 0
        queue.runIfIdle { calls++ }
        val owner = queue.beginSession()
        queue.runIfIdle { calls++ }
        assertEquals(1, calls)
        queue.endSession(owner)
        queue.runIfIdle { calls++ }
        assertEquals(2, calls)
        val diagnostic = queue.reserveDiagnostic()!!
        queue.runIfIdle { calls++ }
        assertEquals(2, calls)
        queue.endSession(diagnostic)
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

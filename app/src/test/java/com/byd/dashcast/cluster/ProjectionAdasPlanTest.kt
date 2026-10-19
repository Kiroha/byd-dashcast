package com.byd.dashcast.cluster

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class ProjectionAdasPlanTest {
    private class Fake : ProjectionAdasPlan.Io {
        data class Job(val at: Long, val order: Int, val action: () -> Unit)
        var now = 0L
        var serial = 0
        val jobs = PriorityQueue<Job>(compareBy({ it.at }, { it.order }))
        val frames = mutableListOf<String>()
        val commands = mutableListOf<Pair<Long, Int>>()
        var saved = true
        var holdJournal = false
        var journalReply: ((Boolean) -> Unit)? = null
        var available = true
        var initialized = 0
        var frameFailure = false
        var delayedFrame: (() -> Unit)? = null
        var holdNext = false
        override fun later(delay: Long, action: () -> Unit) { jobs += Job(now + delay, serial++, action) }
        override fun now() = now
        override fun valid() = available
        override fun journal(done: (Boolean) -> Unit) {
            if (holdJournal) { holdJournal = false; journalReply = done } else done(saved)
        }
        override fun initialize(valid: () -> Boolean, done: (Boolean) -> Unit) {
            if (valid()) initialized++
            done(valid())
        }
        override fun command(code: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            if (valid()) commands += now to code
            done(valid())
        }
        override fun frames(frame: String, count: Int, valid: () -> Boolean, done: (Boolean) -> Unit) {
            val deliver = {
                if (valid() && !frameFailure) repeat(count) { frames += frame }
                done(valid() && !frameFailure)
            }
            if (holdNext) { holdNext = false; delayedFrame = deliver } else deliver()
        }
        fun until(at: Long) {
            var limit = 10000
            while (jobs.isNotEmpty() && jobs.peek().at <= at) {
                check(limit-- > 0)
                val job = jobs.remove(); now = job.at; job.action()
            }
            now = at
        }
    }
    @Test fun `first pass contains exactly the recovered ordered 263 frames`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(1000)
        val expected = List(3) { ProjectionAdasPlan.HIDE } +
            ProjectionAdasPlan.PHASES.flatMap { (frame, count) -> List(count) { frame } }
        assertEquals(expected, io.frames)
        assertEquals(listOf(0L to 13, 500L to 18, 1000L to 0), io.commands)
    }
    @Test fun `post layout retains nominal timing and supersedes the earlier followup`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(30000)
        assertEquals(listOf(0L to 13, 500L to 18, 1000L to 0, 2000L to 16,
            3000L to 35, 3000L to 13, 16000L to 13), io.commands)
        assertEquals(263 * 3, io.frames.size)
    }
    @Test fun `journal failure prevents every command including layout`() {
        val io = Fake().apply { saved = false }; val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(60000)
        assertTrue(io.frames.isEmpty()); assertTrue(io.commands.isEmpty()); assertEquals(0, io.initialized)
    }
    @Test fun `maintenance and transitions cannot bypass failed or pending journal`() {
        for (pending in listOf(false, true)) {
            val io = Fake().apply { saved = false; holdJournal = pending }
            val plan = ProjectionAdasPlan(io)
            plan.start(3)
            plan.observe(3, 102, true, 0)
            plan.observe(2, 102, false, 1)
            plan.observe(3, 102, true, 2)
            io.until(5000)
            assertTrue(io.frames.isEmpty()); assertTrue(io.commands.isEmpty()); assertEquals(0, io.initialized)
            io.journalReply?.invoke(false)
            io.until(30000)
            assertTrue(io.frames.isEmpty()); assertTrue(io.commands.isEmpty())
        }
    }
    @Test fun `restore cancels a paused hide and sends forty restores then twelve and fifty three`() {
        val io = Fake().apply { holdNext = true }; val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(1000)
        var restored: Boolean? = null
        plan.restore { restored = it }
        io.delayedFrame!!(); io.until(2200)
        assertEquals(List(40) { ProjectionAdasPlan.RESTORE }, io.frames)
        assertEquals(true, restored)
        assertEquals(listOf(1000L to 12, 2200L to 53), io.commands.takeLast(2))
        io.until(30000)
        assertEquals(40, io.frames.size)
    }
    @Test fun `old session callbacks cannot mutate a replacement pass`() {
        val io = Fake().apply { holdNext = true }; val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(1000)
        val old = io.delayedFrame!!
        plan.cancel(); plan.start(); old(); io.until(2000)
        assertEquals(263, io.frames.size)
    }
    @Test fun `failure permits only the one bounded retry`() {
        val io = Fake().apply { frameFailure = true }; val plan = ProjectionAdasPlan(io)
        plan.start(); io.until(60000)
        assertEquals(3, io.commands.count { it.second == 13 })
        assertTrue(io.frames.isEmpty())
    }
    @Test fun `initial raw power snapshot does not manufacture a state transition`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(3); plan.observe(3, 102, false, 0); io.until(5000)
        assertEquals(2, io.commands.count { it.second == 13 })
    }
    @Test fun `lost transition is not replayed after four seconds`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(2); plan.observe(3, 102, false, 0)
        plan.observe(null, 102, false, 1); io.until(5000)
        assertEquals(2, io.commands.count { it.second == 13 })
    }
    @Test fun `periodic keepalive requires both valid raw power and platform`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(3); io.until(1000)
        val before = io.frames.size
        plan.observe(3, null, true, 1000); plan.observe(2, 102, true, 1000)
        assertEquals(before, io.frames.size)
        plan.observe(3, 102, true, 1000)
        assertEquals(before + 3, io.frames.size)
    }
    @Test fun `cancel revokes every pending frame and debug command`() {
        val io = Fake(); val plan = ProjectionAdasPlan(io)
        plan.start(); plan.cancel(); io.until(60000)
        assertEquals(listOf(0L to 13), io.commands); assertTrue(io.frames.isEmpty())
    }
    @Test fun `restore does not claim success after capability is lost`() {
        val io = Fake().apply { available = false }; val plan = ProjectionAdasPlan(io)
        var result: Boolean? = null; plan.restore { result = it }
        assertEquals(false, result); assertTrue(io.frames.isEmpty())
    }
    @Test fun `explicit hide reports delivery only after the first hide batch not when armed`() {
        val io = Fake().apply { holdNext = true }; val plan = ProjectionAdasPlan(io)
        val delivered = mutableListOf<Boolean>()
        plan.start(delivered = delivered::add)
        assertTrue(delivered.isEmpty())
        io.until(1000)
        assertTrue(delivered.isEmpty())
        io.delayedFrame!!()
        assertEquals(listOf(true), delivered)
        io.until(30000)
        assertEquals(listOf(true), delivered)
        assertEquals(263 * 3, io.frames.size)
    }
    @Test fun `superseded batch cannot settle explicit delivery or emit its late frame`() {
        val io = Fake().apply { holdNext = true }; val plan = ProjectionAdasPlan(io)
        val delivered = mutableListOf<Boolean>()
        plan.start(delivered = delivered::add); io.until(1000)
        val stale = io.delayedFrame!!
        io.until(4000)
        assertEquals(listOf(true), delivered)
        val count = io.frames.size
        stale()
        assertEquals(count, io.frames.size)
        assertEquals(listOf(true), delivered)
    }
    @Test fun `failed initial delivery is not reported as sent`() {
        for (journalFailure in listOf(false, true)) {
            val io = Fake().apply { saved = !journalFailure; frameFailure = !journalFailure }
            val plan = ProjectionAdasPlan(io)
            val delivered = mutableListOf<Boolean>()
            plan.start(delivered = delivered::add); io.until(30000)
            assertEquals(listOf(false), delivered)
            assertTrue(io.frames.isEmpty())
        }
    }
}

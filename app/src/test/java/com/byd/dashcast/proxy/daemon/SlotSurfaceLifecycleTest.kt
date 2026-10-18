package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.util.concurrent.DeathLease
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SlotSurfaceLifecycleTest {
    private class Holder(var valid: Boolean = true) {
        var releases = 0
    }

    private class Display(val id: Int = 7, var valid: Boolean = true) {
        var releases = 0
        var surface: Holder? = null
        var width = 800
        var height = 480
    }

    private class Fixture {
        val queued = ArrayDeque<() -> Unit>()
        val bindings = mutableListOf<Holder?>()
        val diagnostics = mutableListOf<Pair<Long, Boolean>>()
        val failures = mutableListOf<Throwable?>()
        var deferredCleanup = false
        var failBind = false
        var failDetach = false
        var beforeBind: ((Display, Holder?) -> Unit)? = null
        var cleanup = 0
        val lifecycle = SlotSurfaceLifecycle<Holder, Display>(
            isSurfaceValid = { it.valid },
            displayId = { it.id },
            isDisplayValid = { it.valid },
            bind = { display, surface ->
                beforeBind?.invoke(display, surface)
                if (surface == null && failDetach || surface != null && failBind)
                    throw IllegalStateException("platform bind refused")
                bindings += surface
                display.surface = surface
            },
            releaseDisplay = { it.releases++ },
            dispatch = { if (deferredCleanup) queued.add(it) else it() },
            onClosed = { cleanup++; failures += it },
            onBindingChanged = { generation, bound -> diagnostics += generation to bound },
        )

        fun create(holder: Holder = Holder(), display: Display = Display()): Display {
            lifecycle.available(holder)
            assertEquals(display.id, lifecycle.create { display.surface = it; display })
            return display
        }

        fun drain() { while (queued.isNotEmpty()) queued.removeFirst()() }
    }

    @Test fun `callbacks before display creation are retained without publication`() {
        val f = Fixture()
        val first = Holder()
        val latest = Holder()
        f.lifecycle.available(first)
        f.lifecycle.available(latest)
        assertSame(latest, f.lifecycle.currentSurface())
        assertEquals(-1, f.lifecycle.readyDisplayId())
        val display = Display()
        assertEquals(7, f.lifecycle.create { assertSame(latest, it); display })
        assertSame(latest, display.surface)
        assertEquals(7, f.lifecycle.readyDisplayId())
        assertEquals(0, first.releases + latest.releases)
    }

    @Test fun `destroy invalidates readiness before detach and recreation retains display`() {
        val f = Fixture()
        val old = Holder()
        val display = f.create(old)
        f.beforeBind = { _, surface -> if (surface == null) assertEquals(-1, f.lifecycle.readyDisplayId()) }
        f.lifecycle.destroyed()
        assertNull(display.surface)
        assertEquals(-1, f.lifecycle.readyDisplayId())
        val replacement = Holder()
        f.lifecycle.available(replacement)
        assertSame(replacement, display.surface)
        assertEquals(7, f.lifecycle.readyDisplayId())
        assertEquals(0, display.releases)
        assertEquals(0, old.releases + replacement.releases)
        assertEquals(listOf(true, false, true), f.diagnostics.map { it.second })
    }

    @Test fun `a reused Surface wrapper is rebound after destruction`() {
        val f = Fixture()
        val holder = Holder()
        f.create(holder)
        f.lifecycle.destroyed()
        holder.valid = true
        f.lifecycle.available(holder)
        assertEquals(listOf(holder, null, holder), f.bindings)
        assertEquals(7, f.lifecycle.readyDisplayId())
    }

    @Test fun `duplicate changed callback does not rebind or emit repeated diagnostics`() {
        val f = Fixture()
        val holder = Holder()
        f.create(holder)
        repeat(10) { f.lifecycle.available(holder) }
        assertEquals(listOf(holder), f.bindings)
        assertEquals(1, f.diagnostics.size)
    }

    @Test fun `creation adopts a newer holder delivered by a reentrant platform call`() {
        val f = Fixture()
        val initial = Holder()
        val latest = Holder()
        val display = Display()
        f.lifecycle.available(initial)
        assertEquals(7, f.lifecycle.create {
            assertSame(initial, it)
            f.lifecycle.destroyed()
            f.lifecycle.available(latest)
            display
        })
        assertSame(latest, display.surface)
        assertEquals(0, display.releases)
    }

    @Test fun `holder loss during creation disposes local display and closes slot`() {
        val f = Fixture()
        val display = Display()
        f.lifecycle.available(Holder())
        assertEquals(-1, f.lifecycle.create { f.lifecycle.destroyed(); display })
        assertTrue(f.lifecycle.isClosed)
        assertEquals(1, display.releases)
        assertEquals(1, f.cleanup)
    }

    @Test fun `timeout during blocking creation disposes late local display once`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val display = Display()
        val result = AtomicReference<Int>()
        f.lifecycle.available(Holder())
        val lane = Thread {
            result.set(f.lifecycle.create {
                entered.countDown()
                check(finish.await(3, TimeUnit.SECONDS))
                display
            })
        }
        lane.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        // Closing can complete while the lifecycle lane is inside a platform call: neither
        // factory nor close's dispatcher is allowed to hold the state monitor.
        f.lifecycle.close()
        assertTrue(f.lifecycle.isClosed)
        assertEquals(-1, f.lifecycle.readyDisplayId())
        finish.countDown()
        lane.join(3_000)
        assertFalse(lane.isAlive)
        assertEquals(-1, result.get())
        assertEquals(1, display.releases)
        f.lifecycle.close()
        assertEquals(1, display.releases)
    }

    @Test fun `release invalidates immediately and late callbacks cannot resurrect queued disposal`() {
        val f = Fixture()
        val holder = Holder()
        val display = f.create(holder)
        f.deferredCleanup = true
        f.lifecycle.close()
        assertEquals(-1, f.lifecycle.readyDisplayId())
        assertEquals(0, display.releases)
        val bindingsBefore = f.bindings.size
        f.lifecycle.available(Holder())
        f.lifecycle.destroyed()
        assertEquals(-1, f.lifecycle.create { fail("closed factory ran"); Display() })
        assertEquals(bindingsBefore, f.bindings.size)
        f.lifecycle.close()
        f.drain()
        assertEquals(1, display.releases)
        assertEquals(1, f.cleanup)
        assertEquals(0, holder.releases)
    }

    @Test fun `bind failure revokes readiness and disposes resources once`() {
        val f = Fixture()
        val display = f.create()
        f.failBind = true
        f.lifecycle.available(Holder())
        assertTrue(f.lifecycle.isClosed)
        assertEquals(-1, f.lifecycle.readyDisplayId())
        assertEquals(1, display.releases)
        f.lifecycle.available(Holder())
        f.lifecycle.destroyed()
        assertEquals(1, display.releases)
        assertEquals(1, f.cleanup)
    }

    @Test fun `detach failure closes the slot instead of leaving its old display ready`() {
        val f = Fixture()
        val display = f.create()
        f.failDetach = true
        f.lifecycle.destroyed()
        assertEquals(-1, f.lifecycle.readyDisplayId())
        assertTrue(f.lifecycle.isClosed)
        assertEquals(1, display.releases)
        assertEquals(1, f.cleanup)
    }

    @Test fun `initial bind failure releases the newly created display`() {
        val f = Fixture()
        val display = Display()
        f.lifecycle.available(Holder())
        f.failBind = true
        assertEquals(-1, f.lifecycle.create { display })
        assertEquals(1, display.releases)
        assertTrue(f.lifecycle.isClosed)
    }

    @Test fun `nonpositive or invalid displays are never published`() {
        for (display in listOf(Display(0), Display(-1), Display(3, valid = false))) {
            val f = Fixture()
            f.lifecycle.available(Holder())
            assertEquals(-1, f.lifecycle.create { display })
            assertEquals(-1, f.lifecycle.readyDisplayId())
            assertEquals(1, display.releases)
            assertTrue(f.lifecycle.isClosed)
        }
    }

    @Test fun `surface and display validity are checked again on every query`() {
        val f = Fixture()
        val holder = Holder()
        val display = f.create(holder)
        holder.valid = false
        assertEquals(-1, f.lifecycle.readyDisplayId())
        holder.valid = true
        display.valid = false
        assertEquals(-1, f.lifecycle.readyDisplayId())
    }

    @Test fun `releasing an old owner leaves a replacement lifecycle independent`() {
        val old = Fixture()
        val replacement = Fixture()
        val oldDisplay = old.create()
        val newDisplay = replacement.create(display = Display(8))
        var death: Runnable? = null
        val owner = object : DeathLease.Owner {
            override fun link(deathCallback: Runnable) { death = deathCallback }
            override fun unlink(deathCallback: Runnable) {}
        }
        DeathLease.attach(owner, Runnable { old.lifecycle.close() })
        death!!.run()
        old.lifecycle.available(Holder())
        assertEquals(1, oldDisplay.releases)
        assertEquals(0, newDisplay.releases)
        assertEquals(8, replacement.lifecycle.readyDisplayId())
    }

    @Test fun `resize uses the same display across holder recreation and stops after close`() {
        val f = Fixture()
        val display = f.create()
        f.lifecycle.destroyed()
        f.lifecycle.available(Holder())
        assertTrue(f.lifecycle.withDisplay { it.width = 1200; it.height = 600 })
        assertEquals(1200, display.width)
        assertEquals(600, display.height)
        assertEquals(7, f.lifecycle.readyDisplayId())
        f.lifecycle.close()
        assertFalse(f.lifecycle.withDisplay { fail("released display resized") })
    }
}

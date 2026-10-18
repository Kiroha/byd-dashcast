package com.byd.dashcast.proxy.daemon

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.DisplayMetrics
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.byd.dashcast.fission.FissionClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSurfaceView
import java.util.concurrent.atomic.AtomicLong

/** Exercises actual holder callbacks, registry/owner isolation and query/resize handlers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [SurfaceDaemonSlotLifecycleTest.HolderSurfaceView::class])
class SurfaceDaemonSlotLifecycleTest {
    @Implements(SurfaceView::class)
    class HolderSurfaceView : ShadowSurfaceView() {
        class TestHolder(private val registered: () -> Unit) : FakeSurfaceHolder() {
            val ownedSurface = Surface(SurfaceTexture(0))
            var refuseSize = false
            var requestedWidth = 0
            var requestedHeight = 0
            override fun getSurface(): Surface = ownedSurface
            override fun addCallback(callback: SurfaceHolder.Callback) {
                super.addCallback(callback)
                // A deterministic early valid holder, before publication or addView return.
                // Default Robolectric SurfaceView never creates a Surface at all.
                callback.surfaceCreated(this)
                registered()
            }
            override fun setFixedSize(width: Int, height: Int) {
                if (refuseSize) throw IllegalStateException("holder size refused")
                requestedWidth = width
                requestedHeight = height
            }
        }
        val testHolder = TestHolder { instances += this }

        @Implementation public override fun getHolder(): SurfaceHolder = testHolder

        companion object { val instances = mutableListOf<HolderSurfaceView>() }
    }

    private val slotClass = Class.forName("com.byd.dashcast.proxy.daemon.SurfaceDaemon\$SlotInfo")
    private val slots = mutableListOf<Any>()

    @Before fun prepare() {
        HolderSurfaceView.instances.clear()
        val app = RuntimeEnvironment.getApplication()
        field("sContext").set(SurfaceDaemon, app)
        field("sSysContext").set(SurfaceDaemon, app)
        ShadowDisplayManager.addDisplay("w1920dp-h720dp")
    }

    @After fun cleanup() {
        slots.forEach { slotMethod("release").invoke(it) }
        HolderSurfaceView.instances.clear()
        field("sContext").set(SurfaceDaemon, null)
        field("sSysContext").set(SurfaceDaemon, null)
    }

    @Test fun `holder callbacks before publication create a queryable display and rebind it`() {
        val (slot, id) = attach("com.example.nav")
        val view = HolderSurfaceView.instances.single()
        assertEquals(id, query("com.example.nav"))
        val callbacks = view.testHolder.callbacks.toList()
        callbacks.forEach { it.surfaceDestroyed(view.testHolder) }
        assertEquals(-1, query("com.example.nav"))
        callbacks.forEach { it.surfaceCreated(view.testHolder) }
        callbacks.forEach { it.surfaceChanged(view.testHolder, PixelFormat.OPAQUE, 800, 480) }
        assertEquals(id, query("com.example.nav"))
        slotMethod("release").invoke(slot)
        assertEquals(-1, query("com.example.nav"))
        callbacks.forEach { it.surfaceCreated(view.testHolder) }
        assertEquals(-1, query("com.example.nav"))
        assertTrue("holder owns its Surface even after slot cleanup", view.testHolder.surface.isValid)
    }

    @Test fun `old slot teardown and callbacks cannot remove or resurrect its replacement`() {
        val (old, oldId) = attach("com.example.nav")
        val oldHolder = HolderSurfaceView.instances.last().testHolder
        val (_, newId) = attach("com.example.nav")
        assertTrue(newId != oldId)
        slotMethod("release").invoke(old)
        oldHolder.callbacks.toList().forEach { it.surfaceDestroyed(oldHolder); it.surfaceCreated(oldHolder) }
        assertEquals(newId, query("com.example.nav"))
    }

    @Test fun `resize uses main lane without waiting on itself and retains the rebound display`() {
        val (_, id) = attach("com.example.nav")
        val holder = HolderSurfaceView.instances.single().testHolder
        holder.callbacks.toList().forEach { it.surfaceDestroyed(holder); it.surfaceCreated(holder) }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString("com.example.nav")
            listOf(12, 20, 1000, 600).forEach(data::writeInt)
            data.setDataPosition(0)
            method("handleResizeSlot", Parcel::class.java, Parcel::class.java).invoke(SurfaceDaemon, data, reply)
            reply.setDataPosition(0)
            reply.readException()
            assertEquals(1, reply.readInt())
            assertEquals(id, query("com.example.nav"))
        } finally { data.recycle(); reply.recycle() }
    }

    @Test fun `release handler cancels a reserved request before any display is created`() {
        val slot = newSlot("com.example.pending")
        method("beginSlot", slotClass).invoke(SurfaceDaemon, slot)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeString("com.example.pending"); data.setDataPosition(0)
            method("handleReleaseSlot", Parcel::class.java, Parcel::class.java).invoke(SurfaceDaemon, data, reply)
            assertTrue(slotMethod("isReleased").invoke(slot) as Boolean)
            assertFalse(method("publishSlot", slotClass).invoke(SurfaceDaemon, slot) as Boolean)
            assertEquals(-1, query("com.example.pending"))
        } finally { data.recycle(); reply.recycle() }
    }

    @Test fun `timeout rollback after committed resize restores metadata and next resize snapshot`() {
        val (slot, id) = attach("com.example.nav")
        assertEquals(1, resize("com.example.nav", 12, 20, 1000, 600))
        assertEquals(1000, slotField(slot, "w").getInt(slot))
        // Deterministic post-commit/pre-completion timeout boundary: the exact rollback
        // entry receives the resize's current token after its metadata has committed.
        rollback(slot, 0, 0, 800, 480)
        assertGeometry(slot, id, 0, 0, 800, 480)
        assertEquals(1, resize("com.example.nav", 30, 40, 1200, 620))
        assertGeometry(slot, id, 30, 40, 1200, 620)
        rollback(slot, 0, 0, 800, 480)
        assertGeometry(slot, id, 0, 0, 800, 480)
    }

    @Test fun `failed rollback closes resources instead of advertising half restored geometry`() {
        val (slot, _) = attach("com.example.nav")
        assertEquals(1, resize("com.example.nav", 12, 20, 1000, 600))
        val holder = HolderSurfaceView.instances.single().testHolder
        holder.refuseSize = true
        rollback(slot, 0, 0, 800, 480)
        assertTrue(slotMethod("isReleased").invoke(slot) as Boolean)
        assertEquals(-1, query("com.example.nav"))
        assertTrue(holder.surface.isValid)
    }

    private fun resize(pkg: String, x: Int, y: Int, w: Int, h: Int): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeString(pkg); listOf(x, y, w, h).forEach(data::writeInt); data.setDataPosition(0)
            method("handleResizeSlot", Parcel::class.java, Parcel::class.java).invoke(SurfaceDaemon, data, reply)
            reply.setDataPosition(0); reply.readException()
            return reply.readInt()
        } finally { data.recycle(); reply.recycle() }
    }

    private fun rollback(slot: Any, x: Int, y: Int, w: Int, h: Int) {
        val generation = (slotField(slot, "resizeGeneration").get(slot) as AtomicLong).get()
        val int = Int::class.javaPrimitiveType!!
        method("scheduleResizeRollback", Handler::class.java, String::class.java, slotClass,
            Long::class.javaPrimitiveType!!, int, int, int, int).invoke(
            SurfaceDaemon, Handler(Looper.getMainLooper()), "com.example.nav", slot, generation, x, y, w, h)
    }

    @Suppress("DEPRECATION") // API-29 display dimensions, deliberately exercised on this runtime.
    private fun assertGeometry(slot: Any, id: Int, x: Int, y: Int, w: Int, h: Int) {
        for ((key, value) in listOf("x" to x, "y" to y, "w" to w, "h" to h))
            assertEquals(key, value, slotField(slot, key).getInt(slot))
        val holder = HolderSurfaceView.instances.single().testHolder
        assertEquals(w, holder.requestedWidth)
        assertEquals(h, holder.requestedHeight)
        val metrics = DisplayMetrics()
        RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java).getDisplay(id).getRealMetrics(metrics)
        assertEquals(w, metrics.widthPixels)
        assertEquals(h, metrics.heightPixels)
        assertEquals(id, query("com.example.nav"))
    }

    private fun attach(pkg: String): Pair<Any, Int> {
        val slot = newSlot(pkg)
        method("beginSlot", slotClass).invoke(SurfaceDaemon, slot)
        val surface = method("tryAttachSlotOverlay", slotClass).invoke(SurfaceDaemon, slot) as Surface?
        assertNotNull("real daemon holder callback should satisfy initial attach", surface)
        val id = method("createTrustedVdForSlot", slotClass, Surface::class.java, String::class.java)
            .invoke(SurfaceDaemon, slot, surface, "test_slot") as Int
        assertTrue("display must be positive", id > 0)
        return slot to id
    }

    private fun newSlot(pkg: String): Any {
        val ctor = slotClass.getDeclaredConstructor(String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        return ctor.newInstance(pkg, 0, 0, 800, 480).also { slots += it }
    }

    private fun query(pkg: String): Int = FissionClient.querySlot(SurfaceDaemon.MirrorBinder(), pkg)

    private fun slotMethod(name: String) = slotClass.getDeclaredMethod(name).apply { isAccessible = true }
    private fun slotField(slot: Any, name: String) = slot.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun field(name: String) = SurfaceDaemon::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun method(name: String, vararg types: Class<*>) =
        SurfaceDaemon::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
}

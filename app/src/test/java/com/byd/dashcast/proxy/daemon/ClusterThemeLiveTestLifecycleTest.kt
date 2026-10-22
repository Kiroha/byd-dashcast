package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.clusterlive.ClusterThemePolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeLiveTestLifecycleTest {
    private fun field(name: String) = ClusterThemeLiveTest::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any) { field(name).set(null, value) }
    private fun flag(name: String) = field(name).get(null) as AtomicBoolean
    private fun count(name: String) = field(name).get(null) as AtomicLong
    private val worker get() = field("worker").get(null) as ExecutorService
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private fun drain() { val done = CountDownLatch(1); worker.execute { done.countDown() }; assertTrue(done.await(3, TimeUnit.SECONDS)) }
    @Before fun reset() {
        drain(); set("initialized", true); set("journalTrusted", true); set("state", "PREFLIGHT")
        set("filename", name); set("mutated", false); set("uncertain", false); set("root", "")
        set("renderer", ""); set("deadline", 0L); set("markerObserved", false); set("oemObserved", false); set("carrierRemoved", false); set("createdInode", -1L); set("createdDevice", -1L)
        flag("active").set(true); flag("restoreRequested").set(false)
        count("generation").set(10); count("restoring").set(-1)
        (field("observationPending").get(null) as AtomicInteger).set(0)
    }
    @After fun finish() { drain(); flag("active").set(false) }
    @Test fun `queued previous restore cannot clean or cancel a newer session`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        ClusterThemeLiveTest.requestRestore(name)
        count("generation").incrementAndGet(); val newer = name.replace("aaaa", "bbbb")
        set("filename", newer); set("state", "PREFLIGHT"); set("deadline", 123456L)
        flag("restoreRequested").set(false)
        release.countDown(); drain()
        val result = JSONObject(ClusterThemeLiveTest.status())
        assertEquals("PREFLIGHT", result.getString("state")); assertEquals(newer, result.getString("filename"))
        assertTrue(flag("active").get()); assertFalse(flag("restoreRequested").get())
        assertEquals(123456L, field("deadline").getLong(null))
    }
    @Test fun `corrupt journal is never proof of no previous mutation`() {
        set("journalTrusted", false); set("state", "QUARANTINE"); set("uncertain", true)
        ClusterThemeLiveTest.requestRestore(name); drain()
        assertTrue(flag("active").get())
        assertEquals("QUARANTINE", JSONObject(ClusterThemeLiveTest.status()).getString("state"))
    }
    @Test fun `late wrong nonce observations do nothing and duplicate callbacks are bounded`() {
        set("mutated", true)
        ClusterThemeLiveTest.observe(1, name.replace("aaaa", "bbbb")); drain()
        assertFalse(field("markerObserved").getBoolean(null))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        repeat(100) { ClusterThemeLiveTest.observe(1, name) }
        assertEquals(1, (field("observationPending").get(null) as AtomicInteger).get())
        set("filename", name.replace("aaaa", "cccc")); count("generation").incrementAndGet()
        release.countDown(); drain()
        assertFalse(field("markerObserved").getBoolean(null))
    }
    @Test fun `carrier removal and default query do not release session without visual OEM observation`() {
        assertTrue(ClusterThemePolicy.canDeleteCarrier(defaultQueried = true, loadSettled = true, uncertain = false))
        set("state", "DEFAULT_QUERIED"); set("carrierRemoved", true); set("defaultQueried", true)
        set("loadSettled", true); set("oemObserved", false)
        ClusterThemeLiveTest::class.java.getDeclaredMethod("maybeCleanup").apply { isAccessible = true }.invoke(ClusterThemeLiveTest)
        assertEquals("DEFAULT_QUERIED", JSONObject(ClusterThemeLiveTest.status()).getString("state"))
        assertTrue(flag("active").get())
        assertFalse(field("oemObserved").getBoolean(null))
    }

    @Test fun `OEM observation received before default query cannot become a later confirmation`() {
        set("mutated", true); set("defaultQueried", false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await() }; assertTrue(entered.await(1, TimeUnit.SECONDS))
        ClusterThemeLiveTest.observe(2, name)
        set("defaultQueried", true)
        release.countDown(); drain()
        assertFalse(field("oemObserved").getBoolean(null))
    }

}

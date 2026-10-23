package com.byd.dashcast.proxy.daemon

import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeVariant
import com.byd.dashcast.clusterlive.ClusterRendererDiscovery
import com.byd.dashcast.clusterlive.rendererInputStreams
import android.system.ErrnoException
import android.system.OsConstants
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
import java.lang.reflect.InvocationTargetException

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
        set("themeObserved", false); set("variant", ClusterThemeVariant.MARKER)
        flag("active").set(true); flag("queryActive").set(false); flag("restoreRequested").set(false)
        set("oemQuery", JSONObject()); set("helperUnreaped", false)
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

    @Test fun `marker observation cannot settle full surface load and theme observation stays distinct`() {
        set("mutated", true); set("loadSettled", false); set("variant", ClusterThemeVariant.FULL_SURFACE_TEST)
        ClusterThemeLiveTest.observe(1, name); drain()
        assertFalse(field("markerObserved").getBoolean(null)); assertFalse(field("themeObserved").getBoolean(null))
        assertFalse(field("loadSettled").getBoolean(null))
        ClusterThemeLiveTest.observe(1, name, ClusterThemeVariant.FULL_SURFACE_TEST); drain()
        assertFalse(field("markerObserved").getBoolean(null)); assertTrue(field("themeObserved").getBoolean(null))
        assertTrue(field("loadSettled").getBoolean(null))
    }

    @Test fun `renderer discovery refusal cannot pin a renderer stage a carrier or start a helper`() {
        set("identity", ""); set("helperPid", 0); set("helperToken", "")
        val result = ClusterRendererDiscovery.scan(entries = { it("42") }, access = rendererInputStreams {
            throw java.nio.file.AccessDeniedException("private")
        })
        val method = ClusterThemeLiveTest::class.java.getDeclaredMethod("identifyRenderer", ClusterRendererDiscovery.Result::class.java)
            .apply { isAccessible = true }
        val error = runCatching { method.invoke(ClusterThemeLiveTest, result) }.exceptionOrNull() as InvocationTargetException
        assertTrue(error.cause is IllegalStateException)
        assertTrue(error.cause!!.message!!.contains("denied=1"))
        assertEquals("renderer-discovery", field("phase").get(null))
        assertEquals("", field("renderer").get(null)); assertEquals("", field("identity").get(null)); assertEquals("", field("root").get(null))
        assertFalse(field("mutated").getBoolean(null)); assertEquals(-1L, field("createdInode").getLong(null))
        assertEquals(0, field("helperPid").getInt(null)); assertEquals("", field("helperToken").get(null))
    }

    @Test fun `native errno remains distinguishable without exposing exception payloads`() {
        val method = ClusterThemeLiveTest::class.java.getDeclaredMethod("procFailure", Exception::class.java)
            .apply { isAccessible = true }
        for ((errno, kind, label) in listOf(
            Triple(OsConstants.EACCES, ClusterRendererDiscovery.FailureKind.DENIED, "EACCES"),
            Triple(OsConstants.EPERM, ClusterRendererDiscovery.FailureKind.DENIED, "EPERM"),
            Triple(OsConstants.ENOENT, ClusterRendererDiscovery.FailureKind.VANISHED, "ENOENT"),
            Triple(OsConstants.ESRCH, ClusterRendererDiscovery.FailureKind.VANISHED, "ESRCH"),
            Triple(OsConstants.EIO, ClusterRendererDiscovery.FailureKind.IO, "errno_${OsConstants.EIO}"),
        )) {
            val error = java.io.IOException("PRIVATE", ErrnoException("PRIVATE", errno))
            val result = method.invoke(ClusterThemeLiveTest, error) as ClusterRendererDiscovery.Failure
            assertEquals(kind, result.kind); assertEquals(label, result.label)
        }
    }

    @Test fun `read only query preserves the complete live test and recovery state`() {
        flag("active").set(false); set("state", "REFUSED"); set("detail", "previous renderer discovery refusal")
        set("mutated", false); set("loadSettled", true); set("defaultQueried", true); set("uncertain", false)
        val before = JSONObject(ClusterThemeLiveTest.status())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        ClusterThemeLiveTest.querySelection {
            entered.countDown(); release.await()
            ClusterThemeQueryRunner.Result(JSONObject().put("state", "RECEIVED").put("nativeIdentity", "UNKNOWN"), true)
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals("PENDING", JSONObject(ClusterThemeLiveTest.status()).getJSONObject("oemQuery").getString("state"))
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        release.countDown(); drain()
        val after = JSONObject(ClusterThemeLiveTest.status())
        for (key in before.keys()) if (key != "oemQuery" && key != "remainingMs" && key != "nativeMode") assertEquals(key, before.get(key), after.get(key))
        assertEquals(before.getJSONObject("nativeMode").toString(), after.getJSONObject("nativeMode").toString())
        assertFalse(flag("active").get()); assertFalse(flag("restoreRequested").get()); assertFalse(flag("queryActive").get())
        assertTrue(field("loadSettled").getBoolean(null)); assertTrue(field("defaultQueried").getBoolean(null))
    }

    @Test fun `active recovery quarantine and an unreaped query cannot admit another query`() {
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        flag("active").set(false); set("journalTrusted", false)
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        set("journalTrusted", true)
        ClusterThemeLiveTest.querySelection { ClusterThemeQueryRunner.Result(JSONObject().put("state", "UNAVAILABLE"), false) }
        drain()
        assertTrue(flag("queryActive").get())
        assertThrows(IllegalStateException::class.java) { ClusterThemeLiveTest.querySelection { error("must not run") } }
        assertFalse(field("mutated").getBoolean(null)); assertFalse(flag("restoreRequested").get())
    }

    @Test fun `native admission refuses a production1000 call already admitted without waiting for its IPC`() {
        flag("active").set(false)
        ClusterThemeLiveTest.beginProductionModeCommand()
        try {
            val result = JSONObject(ClusterThemeLiveTest.nativeModeRequest(true, "d".repeat(32), true, true, android.os.Binder()))
            assertEquals("d".repeat(32), result.getString("nativeRejectedNonce"))
            assertTrue(result.getString("nativeRefusal").contains("Production mode command in flight"))
            assertFalse(result.getJSONObject("nativeMode").getBoolean("debt"))
        } finally { ClusterThemeLiveTest.endProductionModeCommand() }
    }

}

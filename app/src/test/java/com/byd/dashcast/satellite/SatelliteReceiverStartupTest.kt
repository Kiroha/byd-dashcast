package com.byd.dashcast.satellite

import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.time.Duration
import java.util.Collections
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Provider failures enter through the real foreground service and its scheduled startup worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteReceiverStartupTest {
    private lateinit var controller: ServiceController<SatelliteReceiverService>
    private val provider = object : Provider("SatelliteStartupTest", 1.0, "Test-only keystore startup") {
        init { put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name) }
    }
    private var unblock = CountDownLatch(0)

    @Before fun setUp() {
        loadAction = {}
        Security.insertProviderAt(provider, 1)
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePrefs.setEnabled(context, true)
        controller = Robolectric.buildService(SatelliteReceiverService::class.java).create()
    }

    @After fun tearDown() {
        unblock.countDown()
        awaitWorker()
        controller.destroy()
        Security.removeProvider(provider.name)
        loadAction = {}
    }

    private fun awaitWorker() {
        val worker = ReflectionHelpers.getField<ExecutorService>(controller.get(), "transportWorker")
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test fun `a provider linkage failure becomes unavailable instead of remaining Starting forever`() {
        loadAction = { throw NoSuchMethodError("test-only missing provider method") }
        controller.get().onStartCommand(null, 0, 1)
        awaitWorker()
        assertEquals(SatelliteStatusTracker.Connection.UNAVAILABLE,
            SatelliteStatus.snapshot(RuntimeEnvironment.getApplication()).connection)
    }

    @Test fun `stalled TLS preparation expires and its late completion cannot bind a listener`() {
        val entered = CountDownLatch(1)
        unblock = CountDownLatch(1)
        loadAction = { entered.countDown(); check(unblock.await(10, TimeUnit.SECONDS)) }
        controller.get().onStartCommand(null, 0, 1)
        assertTrue("TLS startup did not enter the provider", entered.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals(SatelliteStatusTracker.Connection.UNAVAILABLE,
            SatelliteStatus.snapshot(RuntimeEnvironment.getApplication()).connection)
        unblock.countDown()
        awaitWorker()
        assertNull("A late startup retained a listener", ReflectionHelpers.getField<Any?>(controller.get(), "server"))
        assertEquals(SatelliteStatusTracker.Connection.UNAVAILABLE,
            SatelliteStatus.snapshot(RuntimeEnvironment.getApplication()).connection)
    }

    @Test fun `disabling during TLS preparation fences its eventual listener startup`() {
        val entered = CountDownLatch(1)
        unblock = CountDownLatch(1)
        loadAction = { entered.countDown(); check(unblock.await(10, TimeUnit.SECONDS)) }
        controller.get().onStartCommand(null, 0, 1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        SatellitePrefs.setEnabled(RuntimeEnvironment.getApplication(), false)
        unblock.countDown()
        awaitWorker()
        assertNull(ReflectionHelpers.getField<Any?>(controller.get(), "server"))
        assertEquals(SatelliteStatusTracker.Connection.DISABLED,
            SatelliteStatus.snapshot(RuntimeEnvironment.getApplication()).connection)
    }

    class TestKeyStore : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = loadAction()
        // The alias exists so production skips key generation. No real key is needed to exercise
        // pre-bind cancellation after SSLContext.init; TLS authentication has its own socket test.
        override fun engineContainsAlias(alias: String?) = true
        override fun engineAliases() = Collections.emptyEnumeration<String>()
        override fun engineSize() = 0
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineIsKeyEntry(alias: String?) = false
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = error("unused")
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = error("unused")
        override fun engineDeleteEntry(alias: String?) = error("unused")
        override fun engineStore(stream: OutputStream?, password: CharArray?) = error("unused")
    }

    companion object {
        @Volatile private var loadAction: () -> Unit = {}
    }
}

package com.byd.dashcast.ui.diag

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.ProxyDaemonContract
import com.byd.dashcast.proxy.ProxyClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiLinkContextDeviceTest {
    @Test fun `pinned binder receives only fixed EXEC requests preserving real exit and merged text`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_EXEC, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                assertEquals("id", data.readString())
                assertEquals(0, flags)
                calls.incrementAndGet()
                reply!!.writeNoException()
                reply.writeInt(13)
                reply.writeString("Permission denied")
                return true
            }
        }
        val device = DiLinkContextDevice(binder)
        val result = device.read(DiLinkContextPlan.commands.first())
        assertEquals(13, result.exitCode)
        assertEquals("Permission denied", result.combinedOutput)
        assertFalse(result.fatal)
        assertEquals(1, calls.get())
    }

    @Test fun `missing already connected binder returns unavailable with no bootstrap or fallback`() {
        val result = DiLinkContextDevice(null).read(DiLinkContextPlan.commands.first())
        assertNull(result.exitCode)
        assertTrue(result.fatal)
        assertTrue(result.error.contains("no connection or bootstrap attempted"))
    }

    @Test fun `fabricated command is rejected before any transaction`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet(); return false
            }
        }
        try {
            DiLinkContextDevice(binder).read(DiLinkContextPlan.commands.first().copy(argv = listOf("setprop", "x", "1")))
            fail("Fabricated command was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls.get())
    }

    @Test fun `session keeps context payload hashes intact after final completion`() {
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(ProxyDaemonContract.TXN_EXEC, code)
                data.enforceInterface(ProxyDaemonContract.DESCRIPTOR)
                val shell = data.readString()
                val command = DiLinkContextPlan.commands.single { it.shell == shell }
                calls.incrementAndGet()
                val output = when (command.kind) {
                    DiLinkContextPlan.Kind.ID -> "uid=2000(shell)"
                    DiLinkContextPlan.Kind.EPOCH -> "1792140000"
                    DiLinkContextPlan.Kind.CURRENT_USER -> "0"
                    DiLinkContextPlan.Kind.SERVICES -> "Found 0 services:"
                    DiLinkContextPlan.Kind.PROPERTY -> "synthetic"
                    DiLinkContextPlan.Kind.PACKAGE_PATH -> "package:/system/app/test/base.apk"
                    DiLinkContextPlan.Kind.PACKAGE_DUMP -> "Package [${command.subject}] (test):"
                }
                reply!!.writeNoException(); reply.writeInt(0); reply.writeString(output)
                return true
            }
        }
        val previousBinder = ProxyClient.sBinder
        val finished = CountDownLatch(1)
        try {
            ProxyClient.sBinder = binder
            FullApkExportSession.start(RuntimeEnvironment.getApplication(), ExportKind.CONTEXT) { finished.countDown() }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            val state = FullApkExportSession.snapshot!!
            assertEquals("finished", state.outcome)
            assertEquals(14, calls.get())
            val directory = state.directory!!
            val manifest = File(directory, "manifest.json").readText()
            for (name in listOf("RESULT.txt", "resultats.json")) {
                val external = File(directory, name).readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(external).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertTrue(manifest.contains(hash))
                ZipFile(state.archives.single()).use { zip -> assertArrayEquals(external, zip.getInputStream(zip.getEntry(name)).readBytes()) }
            }
            assertTrue(File(directory, "resultats.json").readText().contains("\"app_uid\":"))
            assertFalse(File(directory, "RUNNING.txt").exists())
        } finally { ProxyClient.sBinder = previousBinder }
    }

    @Test fun `session releases the diagnostic owner when worker allocation or startup fails`() {
        for (failure in listOf(OutOfMemoryError("synthetic allocation"), IllegalStateException("synthetic startup"))) {
            var released = 0
            var launched = 0
            try {
                FullApkExportSession.start(RuntimeEnvironment.getApplication(), ExportKind.CONTEXT,
                    launch = { _, _ -> launched++; throw failure }) { released++ }
                if (failure is Error) fail("Error must propagate after releasing owner")
            } catch (caught: OutOfMemoryError) { assertSame(failure, caught) }
            assertEquals(1, launched)
            assertEquals(1, released)
        }
    }
}

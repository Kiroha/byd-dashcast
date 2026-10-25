package com.byd.dashcast.infrastructure

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.infrastructure.LegacyDaemonRecovery.Access
import com.byd.dashcast.infrastructure.LegacyDaemonRecovery.Endpoint
import com.byd.dashcast.BuildConfig
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import com.byd.dashcast.proxy.daemon.DaemonCallerIdentity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSigningInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyDaemonRecoveryTest {
    @Test fun `healthy surface query leaves the actual daemon resource registry intact`() {
        @Suppress("UNCHECKED_CAST")
        val registry = SurfaceDaemon::class.java.getDeclaredField("sSlots").apply { isAccessible = true }
            .get(SurfaceDaemon) as MutableMap<String, Any>
        val held = Any()
        registry["held-test-slot"] = held
        try {
            assertEquals(Access.ALLOWED, LegacyDaemonRecovery.probe(Endpoint.SURFACE, SurfaceDaemon.MirrorBinder()))
            assertSame("read-only probe removed a held slot", held, registry["held-test-slot"])
            assertEquals(1, registry.size)
        } finally { registry.remove("held-test-slot") }
    }

    @Test fun `fresh gate PM outage preserves held surfaces without legacy retirement and recovers`() {
        val field = SurfaceDaemon::class.java.getDeclaredField("sCallerIdentity").apply { isAccessible = true }
        val previous = field.get(SurfaceDaemon)
        @Suppress("UNCHECKED_CAST")
        val slots = SurfaceDaemon::class.java.getDeclaredField("sSlots").apply { isAccessible = true }
            .get(SurfaceDaemon) as MutableMap<String, Any>
        val held = Any()
        slots["held-test-slot"] = held
        var unavailable = true
        val signer = byteArrayOf(1, 2, 3)
        field.set(SurfaceDaemon, DaemonCallerIdentity(2000, BuildConfig.APPLICATION_ID, listOf(signer)) {
            if (unavailable) throw IllegalStateException("PM unavailable")
            PackageInfo().apply {
                packageName = BuildConfig.APPLICATION_ID
                applicationInfo = ApplicationInfo().apply { uid = 10127 }
                signingInfo = Shadow.newInstanceOf(SigningInfo::class.java).also {
                    Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(Signature(signer)))
                }
            }
        })
        ShadowBinder.setCallingUid(10127)
        try {
            val binder = SurfaceDaemon.MirrorBinder()
            assertEquals(Access.UNAVAILABLE, LegacyDaemonRecovery.probe(Endpoint.SURFACE, binder))
            assertFalse(LegacyDaemonRecovery().recover(Endpoint.SURFACE, binder,
                { fail("fresh PM outage must not retire surface owner"); "" }) { true })
            assertSame(held, slots["held-test-slot"])
            unavailable = false
            assertEquals(Access.ALLOWED, LegacyDaemonRecovery.probe(Endpoint.SURFACE, binder))
            assertSame(held, slots["held-test-slot"])
        } finally {
            field.set(SurfaceDaemon, previous)
            slots.remove("held-test-slot")
            ShadowBinder.reset()
        }
    }

    @Test fun `same build legacy denial retires only its verified generation once`() {
        for (endpoint in Endpoint.entries) {
            val commands = mutableListOf<String>()
            val recovery = LegacyDaemonRecovery()
            val rejected = rejectingBinder()
            val shell: (String) -> String = { command ->
                commands += command
                if (commands.size == 1) "GENERATION 123 98765" else "RETIRED"
            }
            assertTrue(recovery.recover(endpoint, rejected, shell) { true })
            assertEquals(2, commands.size)
            assertTrue(commands[1].contains("= '123'"))
            assertTrue(commands[1].contains("= '98765'"))
            assertTrue(commands[1].contains("kill -9 \"\$PID\""))
            assertFalse(commands[1].contains("xargs"))
            assertFalse(recovery.recover(endpoint, rejected, shell) { true })
            // A replacement which still fails metadata cannot cause a kill/spawn storm.
            assertFalse(recovery.recover(endpoint, rejectingBinder(), shell) { true })
            assertEquals(2, commands.size)
        }
    }

    @Test fun `unknown probe never invokes shell recovery`() {
        for (endpoint in Endpoint.entries) {
            val unavailable = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
            }
            assertEquals(Access.UNAVAILABLE, LegacyDaemonRecovery.probe(endpoint, unavailable))
            assertFalse(LegacyDaemonRecovery().recover(endpoint, unavailable, { fail("unexpected ADB"); "" }) { true })
        }
    }

    @Test fun `immediate successive probes reuse the bounded worker without false rejection`() {
        val rejected = rejectingBinder()
        repeat(50) { assertEquals(Access.DENIED, LegacyDaemonRecovery.probe(Endpoint.SURFACE, rejected)) }
    }

    @Test fun `non authorization exception cannot trigger retirement`() {
        val unavailable = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeException(IllegalStateException("not ready"))
                return true
            }
        }
        assertEquals(Access.UNAVAILABLE, LegacyDaemonRecovery.probe(Endpoint.SURFACE, unavailable))
        assertFalse(LegacyDaemonRecovery().recover(Endpoint.SURFACE, unavailable, { fail("unexpected ADB"); "" }) { true })
    }

    @Test fun `endpoint replacement during capture cannot retire or clear the replacement`() {
        var current = true
        val commands = mutableListOf<String>()
        val result = LegacyDaemonRecovery().recover(Endpoint.PROXY, rejectingBinder(), {
            commands += it
            current = false
            "GENERATION 123 98765"
        }) { current }
        assertFalse(result)
        assertEquals(1, commands.size)
        assertFalse(commands.single().contains("kill -9"))
    }

    @Test fun `lost generation proof refuses a name match and does not kill`() {
        var calls = 0
        assertFalse(LegacyDaemonRecovery().recover(Endpoint.SURFACE, rejectingBinder(), {
            calls++
            "" // missing marker, unknown owner, inaccessible proc, duplicate metadata, etc.
        }) { true })
        assertEquals(1, calls)
        assertNull(LegacyDaemonRecovery.parseGeneration("GENERATION 123 0"))
        assertNull(LegacyDaemonRecovery.parseGeneration("GENERATION 123 1\nGENERATION 124 2"))
    }

    @Test fun `concurrent rejection coalesces to one generation capture and retirement`() {
        val recovery = LegacyDaemonRecovery()
        val binder = rejectingBinder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val shell: (String) -> String = {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                "GENERATION 123 98765"
            } else "RETIRED"
        }
        val result = AtomicReference<Boolean>()
        val first = Thread { result.set(recovery.recover(Endpoint.SURFACE, binder, shell) { true }) }
        first.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertFalse(recovery.recover(Endpoint.SURFACE, binder, shell) { true })
        release.countDown()
        first.join(1000)
        assertFalse(first.isAlive)
        assertEquals(true, result.get())
        assertEquals(2, calls.get())
    }

    @Test fun `generated shell requires owned unique runtime package build and the same proc generation`() {
        for (endpoint in Endpoint.entries) {
            val fixture = ProcessFixture(endpoint)
            try {
                val inspect = LegacyDaemonRecovery.inspectCommand(endpoint)
                val retire = LegacyDaemonRecovery.terminateCommand(endpoint, LegacyDaemonRecovery.Generation(123, 98765))
                assertEquals("GENERATION 123 98765", fixture.run(inspect).second)
                fixture.startTicks = 98766 // Same PID now belongs to a replacement process.
                assertNotEquals(0, fixture.run(retire).first)
                assertFalse(fixture.killed.exists())
                fixture.startTicks = 98765
                fixture.duplicate = true // New marker owner while rejected old endpoint is alive.
                assertNotEquals(0, fixture.run(retire).first)
                fixture.duplicate = false
                fixture.owner = 10125
                assertNotEquals(0, fixture.run(retire).first)
                fixture.owner = 2000
                fixture.apk = "/data/app/com.example.other-abc/base.apk"
                assertNotEquals(0, fixture.run(retire).first)
                fixture.apk = "/data/app/com.byd.dashcast-legacy/base.apk"
                fixture.build = BuildConfig.VERSION_CODE - 1
                assertNotEquals(0, fixture.run(retire).first)
                fixture.build = BuildConfig.VERSION_CODE
                fixture.runtime = "dashcast_proxy_impostor"
                assertNotEquals(0, fixture.run(retire).first)
                fixture.runtime = fixture.expectedRuntime
                assertFalse(fixture.killed.exists())
                assertEquals(0, fixture.run(retire).first)
                assertEquals("-9 123", fixture.killed.readText().trim())
            } finally { fixture.directory.deleteRecursively() }
        }
    }

    @Test fun `a wedged probe times out and cannot accumulate another queued binder call`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet()
                entered.countDown()
                // A remote Binder call may ignore interruption, unlike an ordinary Future task.
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                return false
            }
        }
        try {
            assertEquals(Access.UNAVAILABLE, LegacyDaemonRecovery.probe(Endpoint.PROXY, binder))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(Access.UNAVAILABLE, LegacyDaemonRecovery.probe(Endpoint.PROXY, binder))
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    private class ProcessFixture(endpoint: Endpoint) {
        val directory = Files.createTempDirectory("daemon-recovery-proc").toFile()
        val killed = File(directory, "killed")
        val expectedRuntime = if (endpoint == Endpoint.PROXY) "dashcast_proxy" else "com.byd.dashcast.mirrordaemon"
        var runtime = expectedRuntime
        var startTicks = 98765L
        var owner = 2000
        var duplicate = false
        var apk = "/data/app/com.byd.dashcast-legacy/base.apk"
        var build = BuildConfig.VERSION_CODE
        private val surface = endpoint == Endpoint.SURFACE
        fun run(command: String): Pair<Int, String> {
            val proc = File(directory, "proc/123").apply { mkdirs() }
            File(directory, "marker").writeText(if (surface) "123:$build" else "123")
            File(directory, "version").writeText(build.toString())
            File(proc, "status").writeText("Uid:\t$owner\t$owner\t$owner\t$owner\n")
            File(proc, "cmdline").writeBytes("$runtime\u0000".toByteArray())
            File(proc, "environ").writeBytes("CLASSPATH=$apk\u0000".toByteArray())
            File(proc, "stat").writeText("123 (daemon) " + List(20) {
                when (it) { 0 -> "S"; 19 -> startTicks.toString(); else -> "0" }
            }.joinToString(" "))
            val ps = "shell 123 1 0 0 0 0 S $expectedRuntime" +
                (if (duplicate) "\nshell 124 1 0 0 0 0 S $expectedRuntime" else "")
            val prefix = "stat() { echo 2000; }; ps() { printf '%s\\n' " + SurfaceDaemonStartCommand.quote(ps) +
                "; }; kill() { printf '%s\\n' \"\$*\" > " + SurfaceDaemonStartCommand.quote(killed.path) + "; }; "
            val script = command.replace("/data/local/tmp/dashcast_proxy.pid", File(directory, "marker").path)
                .replace(SurfaceDaemon.VERSION_FILE, File(directory, "marker").path)
                .replace("/data/local/tmp/dashcast_proxy_ver", File(directory, "version").path)
                .replace("/proc/", File(directory, "proc").path + "/")
            val process = ProcessBuilder("sh", "-c", prefix + script).redirectErrorStream(true).start()
            assertTrue("fixture command stalled", process.waitFor(3, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().readText().trim()
        }
    }

    private fun rejectingBinder() = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeException(SecurityException("caller uid 10127 not permitted"))
            return true
        }
    }
}

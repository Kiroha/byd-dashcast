package com.byd.dashcast.proxy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyInstanceMarkerWiringTest {

    /**
     * Asserts PRESENCE before order. `indexOf(a) < indexOf(b)` on its own reads GREEN when `a`
     * is deleted outright -- `indexOf` returns -1, and -1 is below any real index -- so it could
     * not fail for the exact regression it names.
     */
    private fun assertOrdered(slice: String, first: String, second: String) {
        val a = slice.indexOf(first)
        val b = slice.indexOf(second)
        assertTrue("missing from the daemon: $first", a >= 0)
        assertTrue("missing from the daemon: $second", b >= 0)
        assertTrue("$first must come before $second", a < b)
    }

    @Test
    fun `v25 daemon persists instance marker before publishing binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.kt").isFile }
        val daemon = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.kt").readText()

        // Scoped to main()'s body: searching the whole file makes `releaseStartupLock()` match
        // its own DECLARATION further down, so deleting the CALL still left the ordering green.
        val mainBody = daemon.substringAfter("fun main(args: Array<String>)")
            .substringBefore("private fun emitBroadcast()")
        assertOrdered(mainBody, "if (!writeInstanceFile())", "val binder = ProxyBinder()")
        assertOrdered(mainBody, "if (!writeInstanceFile())", "releaseStartupLock()")
        assertOrdered(mainBody, "releaseStartupLock()", "val binder = ProxyBinder()")
        val beforeBinder = daemon.substringAfter("fun main(args: Array<String>)")
            .substringBefore("val binder = ProxyBinder()")
        assertTrue(beforeBinder.contains("prepareStandaloneMainLooper()"))
        val looperHelper = daemon.substringAfter("private fun prepareStandaloneMainLooper()")
            .substringBefore("fun main(args: Array<String>)")
        assertTrue(looperHelper.contains("Looper.prepareMainLooper()"))
        assertTrue(daemon.contains("System.exit(4)"))
        assertTrue(daemon.contains("reply.writeString(INSTANCE_TOKEN)"))
        val heartbeat = daemon.substringAfter("private fun installSelfHealHeartbeat()")
            .substringBefore("private fun healTriggerFile()")
        assertTrue(heartbeat.contains("healInstanceFile()"))

        val cleanup = daemon.substringAfter("Thread(\"pid-cleanup\")")
            .substringBefore("// shutdown hooks may be disallowed")
        assertOrdered(cleanup,
            "ProxyDaemonStartupLock.tryAcquire", "readSmallFile(File(PID_FILE))")
        assertOrdered(cleanup,
            "readSmallFile(File(PID_FILE))", "File(PID_FILE).delete()")
        assertTrue(cleanup.contains("cleanupLock.close()"))
    }
}
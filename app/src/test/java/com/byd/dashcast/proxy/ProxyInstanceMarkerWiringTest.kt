package com.byd.dashcast.proxy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyInstanceMarkerWiringTest {

    @Test
    fun `v25 daemon persists instance marker before publishing binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java").isFile }
        val daemon = File(root,
            "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java").readText()

        assertTrue(daemon.indexOf("if (!writeInstanceFile())") <
            daemon.indexOf("sBinder = new ProxyBinder()"))
        assertTrue(daemon.contains("System.exit(4)"))
        assertTrue(daemon.contains("reply.writeString(INSTANCE_TOKEN)"))
    }
}
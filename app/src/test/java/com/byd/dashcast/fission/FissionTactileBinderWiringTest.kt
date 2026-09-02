package com.byd.dashcast.fission

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionTactileBinderWiringTest {
    @Test
    fun `tactile paths recover only their captured surface binder`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val tactile = source.substringAfter("public static LayoutMirrorTarget startSelectedLayoutMirror")
            .substringBefore("public static void killLayoutSlot")

        assertTrue(tactile.contains("surfaceBinderForTactile"))
        assertTrue(tactile.contains("catch (DeadObjectException dead)"))
        assertTrue(tactile.contains("recoverSurfaceBinderIfCurrent(binder"))
        assertTrue(source.contains("if (mDaemonBinder != failed) return mDaemonBinder"))
    }
}
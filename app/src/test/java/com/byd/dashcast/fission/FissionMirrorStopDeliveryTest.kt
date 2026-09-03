package com.byd.dashcast.fission

import android.os.Binder
import android.os.Parcel
import com.byd.dashcast.proxy.daemon.SurfaceDaemon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FissionMirrorStopDeliveryTest {

    @Test
    fun `mirror stop reports whether binder accepted the transaction`() {
        val accepted = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(SurfaceDaemon.DESCRIPTOR)
                return code == SurfaceDaemon.TRANSACT_MIRROR_STOP
            }
        }
        val rejected = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        }

        assertTrue(FissionClient.stopMirror(accepted))
        assertFalse(FissionClient.stopMirror(rejected))
        assertFalse(FissionClient.stopMirror(null))
    }

    @Test
    fun `orchestrator clears state only after acceptance or owner death`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val stop = source.substringAfter("public static void stopSelectedLayoutMirror")
            .substringBefore("public static boolean injectSelectedLayoutMotion")

        assertTrue(stop.contains("boolean accepted = FissionClient.stopMirror(binder)"))
        assertTrue(stop.contains("boolean ownerGone = binder == null || !binder.isBinderAlive()"))
        assertTrue(stop.contains("recoverSurfaceBinderIfCurrent(binder, \"LayoutMirrorStop\")"))
        assertTrue(stop.indexOf("if (accepted || ownerGone)") <
            stop.indexOf("o.mMirrorReady = false"))
    }
}
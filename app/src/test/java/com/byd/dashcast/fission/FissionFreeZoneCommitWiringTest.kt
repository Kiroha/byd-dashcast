package com.byd.dashcast.fission

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FissionFreeZoneCommitWiringTest {

    @Test
    fun `old free zones are released only after bound slot switch succeeds`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/fission/FissionOrchestrator.java").readText()
        val activation = source.substringAfter("private void doActivatePreset")
            .substringBefore("private void attachFreeZones")

        val switch = activation.indexOf("switchActiveLayout(preset, null)")
        val release = activation.indexOf("releaseFreeZones()")
        val attach = activation.indexOf("attachFreeZones(preset)")
        assertTrue("bound slots must commit before old free zones are released",
            switch >= 0 && release > switch)
        assertTrue("new free zones must be attached after old ones are released",
            attach > release)
    }
}
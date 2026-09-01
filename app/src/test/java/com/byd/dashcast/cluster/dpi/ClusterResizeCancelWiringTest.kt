package com.byd.dashcast.cluster.dpi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClusterResizeCancelWiringTest {

    @Test
    fun `cancel dispatches restore before finish without using destroyable UI queue`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it,
                "app/src/main/java/com/byd/dashcast/cluster/dpi/ClusterResizeActivity.kt").isFile }
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/cluster/dpi/ClusterResizeActivity.kt").readText()

        val cancel = source.substringAfter("mCancel.setOnClickListener")
            .substringBefore("mOk.setOnClickListener")
        assertTrue(cancel.contains("if (dispatchFinalApply(r[0], r[1], r[2], r[3])) finish()"))
        assertFalse(cancel.contains("scheduleApply("))

        val dispatch = source.substringAfter("private fun dispatchFinalApply")
            .substringBefore("private fun enqueueApply")
        assertTrue(dispatch.indexOf("enqueueApply(rect)") < dispatch.indexOf("persistRect("))
        assertTrue(dispatch.contains("mUi.removeCallbacks(mApplyRunnable)"))

        val enqueue = source.substringAfter("private fun enqueueApply")
            .substringBefore("// ── System UI")
        assertTrue(enqueue.contains("sResizeExec.execute"))
        assertTrue(enqueue.contains("applicationContext"))
        assertFalse(enqueue.contains("mUi.post"))
    }
}
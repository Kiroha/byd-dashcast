package com.byd.dashcast.fission

import org.junit.Assert.assertEquals
import org.junit.Test

class FissionLayoutSwitchPlanTest {

    @Test
    fun `second start failure rolls back first start and preserves existing ownership`() {
        val events = mutableListOf<String>()
        val operations = object : FissionLayoutSwitchPlan.Operations {
            override fun start(packageName: String) {
                events += "start:$packageName"
                if (packageName == "new.second") throw IllegalStateException("attach rejected")
            }

            override fun rollback(packageName: String) {
                events += "rollback:$packageName"
            }

            override fun stop(packageName: String) {
                events += "stop:$packageName"
            }
        }

        try {
            FissionLayoutSwitchPlan.run(
                listOf("existing.app"),
                listOf("new.first", "new.second"),
                operations,
            )
        } catch (_: IllegalStateException) {
        }

        assertEquals(
            listOf("start:new.first", "start:new.second", "rollback:new.first"),
            events,
        )
    }
}
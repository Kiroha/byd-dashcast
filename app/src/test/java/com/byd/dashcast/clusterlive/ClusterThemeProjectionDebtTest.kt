package com.byd.dashcast.clusterlive

import android.content.Context
import android.content.ContextWrapper
import com.byd.dashcast.infrastructure.AdbLocalClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterThemeProjectionDebtTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nonce = "f".repeat(32)
    @After fun reset() {
        app.getSharedPreferences("rcc_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
        ClusterThemeProjectionDebt::class.java.getDeclaredField("failedClear").apply { isAccessible = true }.setBoolean(null, false)
    }
    @Test fun `RCC debt is durable independent of native debt and bound to its nonce`() {
        ClusterThemeProjectionDebt.begin(app, nonce)
        assertTrue(ClusterThemeProjectionDebt.blocked(ContextWrapper(app)))
        assertFalse(ClusterNativeProjectionDebt.blocked(app))
        assertThrows(IllegalStateException::class.java) { ClusterThemeProjectionDebt.begin(app, nonce) }
        assertThrows(IllegalStateException::class.java) { ClusterThemeProjectionDebt.clear(app, "b".repeat(32)) }
        assertTrue(ClusterThemeProjectionDebt.blocked(app)); ClusterThemeProjectionDebt.clear(app, nonce)
        assertFalse(ClusterThemeProjectionDebt.blocked(app))
    }
    @Test fun `RCC debt refuses broad restore before force stop or native mode transport`() {
        ClusterThemeProjectionDebt.begin(app, nonce)
        val failures = ArrayList<String>()
        val callback = object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) = fail("No vehicle command permitted")
            override fun onError(err: String?) { failures.add(err.orEmpty()) }
        }
        AdbLocalClient.restoreBydOnCluster(app, "example.target", callback)
        AdbLocalClient.restoreOriginCluster(app, 30, "example.target", callback)
        assertEquals(2, failures.size); assertTrue(failures.all { it.contains("NATIVE_CLUSTER_DIAGNOSTIC_ACTIVE") })
    }
}

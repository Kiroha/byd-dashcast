package com.byd.dashcast.clusterlive

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
class ClusterNativeProjectionDebtTest {
    private val app = RuntimeEnvironment.getApplication()
    private val nonce = "b".repeat(32)
    @After fun reset() {
        app.getSharedPreferences("native_cluster_operational_debt", Context.MODE_PRIVATE).edit().clear().commit()
        ClusterNativeProjectionDebt::class.java.getDeclaredField("failedClear").apply { isAccessible = true }.setBoolean(null, false)
    }
    @Test fun `separate app context sees durable debt and wrong nonce cannot release it`() {
        ClusterNativeProjectionDebt.begin(app, nonce)
        val restarted = ContextWrapper(app)
        assertTrue(ClusterNativeProjectionDebt.blocked(restarted))
        assertThrows(IllegalStateException::class.java) { ClusterNativeProjectionDebt.clear(restarted, "c".repeat(32)) }
        assertTrue(ClusterNativeProjectionDebt.blocked(restarted)); ClusterNativeProjectionDebt.clear(restarted, nonce)
        assertFalse(ClusterNativeProjectionDebt.blocked(restarted))
    }
    @Test fun `failed clear commit remains blocking after preferences removed key in memory`() {
        ClusterNativeProjectionDebt.begin(app, nonce)
        val broken = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val original = app.getSharedPreferences(name, mode)
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                            override fun commit(): Boolean { editor.commit(); return false }
                        }
                    }
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { ClusterNativeProjectionDebt.clear(broken, nonce) }
        assertNull(ClusterNativeProjectionDebt.nonce(app)); assertTrue(ClusterNativeProjectionDebt.blocked(app))
        ClusterNativeProjectionDebt.clear(app, nonce); assertFalse(ClusterNativeProjectionDebt.blocked(app))
    }
    @Test fun `all broad restore entry points refuse before force stop or legacy egress`() {
        ClusterNativeProjectionDebt.begin(app, nonce)
        val replies = ArrayList<String>()
        val cb = object : AdbLocalClient.Callback {
            override fun onSuccess(out: String?) { fail("No vehicle action allowed") }
            override fun onError(err: String?) { replies.add(err.orEmpty()) }
        }
        AdbLocalClient.restoreBydOnCluster(app, "example.target", cb)
        AdbLocalClient.restoreOriginCluster(app, 30, "example.target", cb)
        assertEquals(2, replies.size); assertTrue(replies.all { it.contains("NATIVE_CLUSTER_DIAGNOSTIC_ACTIVE") })
    }
}

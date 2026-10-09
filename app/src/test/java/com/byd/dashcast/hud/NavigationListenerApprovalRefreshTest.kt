package com.byd.dashcast.hud

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import com.byd.dashcast.data.prefs.ClusterPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationListenerApprovalRefreshTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val component get() = ComponentName(ctx, MapNotificationListenerService::class.java)

    @Test fun `queued refresh rechecks grant outputs connection and exact component before shell`() {
        ctx.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
        val manager = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        manager.setNotificationListenerAccessGranted(component, true)
        assertEquals("requested", NavigationListenerApprovalRefresh.refreshIfApproved(ctx, component,
            { true }) { "requested" })
        assertEquals("skipped", NavigationListenerApprovalRefresh.refreshIfApproved(ctx, component,
            { false }) { fail("connected while queued"); "" })
        ClusterPrefs.setNavigationEnabled(ctx, false)
        assertEquals("skipped", NavigationListenerApprovalRefresh.refreshIfApproved(ctx, component,
            { true }) { fail("disabled while queued"); "" })
        ClusterPrefs.setNavigationEnabled(ctx, true)
        manager.setNotificationListenerAccessGranted(component, false)
        assertEquals("skipped", NavigationListenerApprovalRefresh.refreshIfApproved(ctx, component,
            { true }) { fail("revoked while queued"); "" })
        assertEquals("skipped", NavigationListenerApprovalRefresh.refreshIfApproved(ctx,
            ComponentName(ctx.packageName, "OtherListener"), { true }) { fail("other component"); "" })
    }

    @Test fun `shell command preserves existing grant and refuses missing or lookalike approvals`() {
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val command = NavigationListenerApprovalRefresh.command(component, 0)
        fun run(grants: String, success: Boolean = true): String {
            // Execute the real compound command against fake Android shell verbs on the host.
            val script = "settings() { printf '%s' '$grants'; }; " +
                "cmd() { [ \"\$*\" = 'notification allow_listener $full 0' ] || exit 77; " +
                "return ${if (success) 0 else 1}; }; $command"
            val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return result
        }
        assertEquals("requested", run(full))
        assertEquals("requested", run("other.app/.Listener:$short"))
        assertEquals("no_grant", run("null"))
        assertEquals("no_grant", run("${full}Extra"))
        assertEquals("no_grant", run("other.app/.Listener"))
        assertEquals("failed", run(full, false))
        assertFalse(command.contains("disallow_listener"))
        assertFalse(command.contains("settings put"))
    }
}

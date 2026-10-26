package com.byd.dashcast.clusterlive

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClusterRestartIntentTest {
    @Test fun `daemon CLEAN crash window reconciles only the exact persisted observation receipt`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cluster_restart_intent", 0).edit().clear().commit()
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        val first = "11111111-1111-1111-1111-111111111111"
        val second = "22222222-2222-2222-2222-222222222222"
        ClusterRestartIntent(app) { first }.record(name, "b".repeat(32))
        val recreated = ClusterRestartIntent(app) { second }
        assertThrows(IllegalStateException::class.java) { recreated.observedRestored(name, ClusterRestartCompletion("c".repeat(32), first, second)) }
        assertTrue(recreated.pending())
        recreated.observedRestored(name, ClusterRestartCompletion("b".repeat(32), first, second))
        assertFalse(recreated.pending())
    }
    @Test fun `missing historical boot identity never clears intent`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("cluster_restart_intent", 0)
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        prefs.edit().clear().putString("filename", name).commit()
        val restored = ClusterRestartIntent(app) { "22222222-2222-2222-2222-222222222222" }
        assertThrows(IllegalStateException::class.java) { restored.observedRestored(name) }; assertTrue(restored.pending())
    }
    @Test fun `intent survives recreation and only changed boot with explicit restored identity closes it`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cluster_restart_intent", 0).edit().clear().commit()
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        var boot = "11111111-1111-1111-1111-111111111111"
        val first = ClusterRestartIntent(app) { boot }
        first.record(name, "b".repeat(32))
        val recreated = ClusterRestartIntent(app) { boot }
        assertTrue(recreated.pending())
        assertThrows(IllegalStateException::class.java) { recreated.record(name, "c".repeat(32)) }
        assertThrows(IllegalStateException::class.java) { recreated.observedRestored(name) }
        boot = "22222222-2222-2222-2222-222222222222"
        recreated.observedRestored(ClusterThemePolicy.PREFIX + "d".repeat(32) + ".rcc")
        assertTrue(recreated.pending())
        recreated.observedRestored(name); assertFalse(first.pending())
    }
    @Test fun `unavailable boot observation does not consume persisted restoration debt`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cluster_restart_intent", 0).edit().clear().commit()
        val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
        ClusterRestartIntent(app) { "11111111-1111-1111-1111-111111111111" }.record(name, "b".repeat(32))
        val broken = ClusterRestartIntent(app) { error("boot unavailable") }
        assertThrows(IllegalStateException::class.java) { broken.observedRestored(name) }; assertTrue(broken.pending())
    }
}

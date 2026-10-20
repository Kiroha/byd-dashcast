package com.byd.dashcast.ui.main

import android.app.Application
import android.content.Context
import android.os.Looper
import com.byd.dashcast.cluster.ClusterService
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.ui.settings.SettingsActivity
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InsetAutoApplicatorRecoveryTest {
    @Test fun `recovery prevents scheduling and invalidates already queued resize`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SettingsActivity.PREF_CLUSTER_RECT_PREFIX + "com.waze", "0,0,800,600").commit()
        var preserved = true; var contexts = 0; var serviceReads = 0
        val app = InsetAutoApplicator(object : InsetAutoApplicator.Host {
            override fun getContext(): Context { contexts++; return context }
            override fun getCurrentPkg() = "com.waze"
            override fun getClusterServiceIfBound(): ClusterService? { serviceReads++; return null }
            override fun shouldPreserveRecoveredPlacement(pkg: String) = preserved
        })
        try {
            app.apply("com.waze"); assertEquals(0, contexts)
            preserved = false; app.apply("com.waze"); assertEquals(1, contexts)
            preserved = true; shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0, serviceReads)
            preserved = false; app.apply("com.waze")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, serviceReads)
        } finally { app.destroy() }
    }
}

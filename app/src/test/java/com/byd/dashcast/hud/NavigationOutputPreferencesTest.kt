package com.byd.dashcast.hud

import android.app.Application
import android.os.Looper
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.data.prefs.NavigationOutputs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationOutputPreferencesTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun `existing installations default to both outputs`() {
        assertTrue(ClusterPrefs.isNavigationEnabled(context))
        assertEquals(NavigationOutputs(true, true), ClusterPrefs.getNavigationOutputs(context))
    }

    @Test
    fun `master off retains cluster-only choice when turned back on`() {
        ClusterPrefs.setNavigationHudEnabled(context, false)
        ClusterPrefs.setNavigationEnabled(context, false)

        assertEquals(NavigationOutputs.NONE, ClusterPrefs.getNavigationOutputs(context))
        assertFalse(ClusterPrefs.isNavigationHudEnabled(context))
        assertTrue(ClusterPrefs.isNavigationClusterEnabled(context))

        ClusterPrefs.setNavigationEnabled(context, true)
        assertEquals(NavigationOutputs(false, true), ClusterPrefs.getNavigationOutputs(context))
    }

    @Test
    fun `observer only follows navigation settings and can be unregistered`() {
        var changes = 0
        val observer = ClusterPrefs.observeNavigationOutputs(context) { changes++ }
        ClusterPrefs.setBootAutoStartEnabled(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, changes)

        ClusterPrefs.setNavigationHudEnabled(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)

        observer.close()
        ClusterPrefs.setNavigationClusterEnabled(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
    }
}

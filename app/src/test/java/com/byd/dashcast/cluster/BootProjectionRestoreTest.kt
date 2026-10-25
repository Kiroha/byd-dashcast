package com.byd.dashcast.cluster

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.Display
import com.byd.dashcast.cluster.display.ClusterManager
import com.byd.dashcast.cluster.display.DashboardDisplayHelper
import com.byd.dashcast.data.prefs.ClusterPrefs
import com.byd.dashcast.data.prefs.ProjectionSessionPrefs
import com.byd.dashcast.data.prefs.StartupAppsMode
import com.byd.dashcast.data.prefs.StartupAppsSelection
import com.byd.dashcast.util.concurrent.LifecycleGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** No Service.onCreate, daemon or launch runnable is executed in these lifecycle tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class BootProjectionRestoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var service: ClusterService
    private lateinit var display: DashboardDisplayHelper
    private var activations = 0
    private lateinit var activationCallback: ClusterManager.DisplayReadyCallback

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    @Before fun setup() {
        context.getSharedPreferences(ClusterPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ClusterPrefs.setBootAutoStartEnabled(context, true)
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", null)
        service = Robolectric.buildService(ClusterService::class.java).get()
        display = DashboardDisplayHelper(context, object : DashboardDisplayHelper.Listener {
            override fun onDashboardDisplayConnected(display: Display?, displayId: Int) {}
            override fun onDashboardDisplayDisconnected() {}
            override fun onDashboardActivationComplete(success: Boolean) { service.onDashboardActivationComplete(success) }
        }, { _, callback ->
            activations++
            activationCallback = callback
            callback.onActivationComplete(true)
        }, {})
        display.start()
        setField(service, "mDisplayHelper", display)
        setField(service, "mProjectionActive", true)
    }

    @After fun cleanup() {
        service.cancelProjectionRestore()
        display.stopWithoutAdb()
    }

    private fun boot() = service.onStartCommand(Intent(context, ClusterService::class.java)
        .putExtra(ClusterService.EXTRA_BOOT_AUTOLAUNCH, true), 0, 1)

    @Test fun `chosen app is the sole source even when another session exists`() {
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.APP, "com.example.chosen"))
        boot()
        val apps = field("mBootApps") as List<*>
        assertEquals("com.example.chosen", (apps.single() as ProjectionSessionPrefs.App).packageName)
    }

    @Test fun `last session without snapshot never falls back to old configured app`() {
        ProjectionSessionPrefs.clear(context)
        ClusterPrefs.setAutoLaunchPkg(context, "com.example.legacy")
        boot()
        assertFalse(service.isBootRestoring())
        assertNull(field("mPendingLaunchRunnable"))
    }

    @Test fun `failed fixed app preserves the previous actual session`() {
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.APP, "com.example.chosen"))
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        (field("mPendingDashboardCallback") as ClusterService.LaunchCallback).onResult(false)
        assertEquals("com.example.first", ProjectionSessionPrefs.read(context)!!.apps.single().packageName)
        assertFalse(service.isBootRestoring())
    }

    @Test fun `changing central source cancels a queued launch without launching replacement`() {
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        val oldGate = field("mOperationGate") as LifecycleGate
        val token = oldGate.capture()
        assertNotNull(field("mPendingLaunchRunnable"))
        ClusterPrefs.setStartupApps(context, StartupAppsSelection(StartupAppsMode.APP, "com.example.new"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(token.isValid)
        assertNull(field("mPendingLaunchRunnable"))
        assertFalse(service.isBootRestoring())
        assertEquals("com.example.first", ProjectionSessionPrefs.read(context)!!.apps.single().packageName)
    }

    @Test fun `ready display immediately drains restore and duplicate boot does not relaunch`() {
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        val first = field("mPendingLaunchRunnable")
        assertNotNull(first)
        assertTrue(service.isBootRestoring())
        boot()
        assertSame(first, field("mPendingLaunchRunnable"))
    }

    @Test fun `restore waits for display instead of dropping pending application`() {
        boot()
        assertTrue(service.isBootRestoring())
        assertNull(field("mPendingLaunchRunnable"))
        setField(display, "mKnownClusterDisplayId", 5)
        service.javaClass.getDeclaredMethod("drainBootRestore").apply { isAccessible = true }.invoke(service)
        assertNotNull(field("mPendingLaunchRunnable"))
    }

    @Test fun `initial waiting boot does not repeat native activation`() {
        boot()
        assertEquals(1, activations)
    }

    @Test fun `observed disconnect reopens native projection once on next boot request`() {
        setField(service, "mObservedDisplayDisconnect", true)
        boot()
        assertEquals(2, activations)
        boot()
        assertEquals(2, activations)
    }

    @Test fun `display readiness alone waits for terminal activation completion`() {
        setField(display, "mActivationComplete", false)
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        assertNull(field("mPendingLaunchRunnable"))
        activationCallback.onActivationComplete(true)
        val first = field("mPendingLaunchRunnable")
        assertNotNull(first)
        activationCallback.onActivationComplete(true)
        assertSame(first, field("mPendingLaunchRunnable"))
    }

    @Test fun `failed activation never launches saved apps and retains next boot intent`() {
        setField(display, "mActivationComplete", false)
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        activationCallback.onActivationComplete(false)
        assertNull(field("mPendingLaunchRunnable"))
        assertFalse(service.isBootRestoring())
        assertNotNull(ProjectionSessionPrefs.read(context))
    }

    @Test fun `projection only never arms configured or saved apps`() {
        ClusterPrefs.setBootProjectionEnabled(context, true)
        ClusterPrefs.setBootAutoStartEnabled(context, false)
        ClusterPrefs.setAutoLaunchPkg(context, "com.example.configured")
        setField(display, "mKnownClusterDisplayId", 5)
        service.onStartCommand(Intent(context, ClusterService::class.java)
            .putExtra(ClusterService.EXTRA_BOOT_PROJECTION, true), 0, 1)
        assertFalse(service.ownsBootRestore())
        assertNull(field("mPendingLaunchRunnable"))
        assertEquals(1, activations)
    }

    @Test fun `both options share one activation and one app replay`() {
        ClusterPrefs.setBootProjectionEnabled(context, true)
        setField(display, "mKnownClusterDisplayId", 5)
        val intent = Intent(context, ClusterService::class.java)
            .putExtra(ClusterService.EXTRA_BOOT_PROJECTION, true)
            .putExtra(ClusterService.EXTRA_BOOT_AUTOLAUNCH, true)
        service.onStartCommand(intent, 0, 1)
        val first = field("mPendingLaunchRunnable")
        assertNotNull(first)
        service.onStartCommand(intent, 0, 2)
        assertSame(first, field("mPendingLaunchRunnable"))
        assertEquals(1, activations)
    }

    @Test fun `disabled option never arms a boot restoration`() {
        ClusterPrefs.setBootAutoStartEnabled(context, false)
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        assertFalse(service.ownsBootRestore())
        assertNull(field("mPendingLaunchRunnable"))
    }

    @Test fun `stop cancels scheduled launch and invalidates already captured work`() {
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        val token = (field("mOperationGate") as LifecycleGate).capture()
        service.cancelProjectionRestore()
        assertFalse(token.isValid)
        assertFalse(service.isBootRestoring())
        assertNull(field("mPendingLaunchRunnable"))
    }

    @Test fun `manual selection invalidates queued bounded restore before its side effects`() {
        ProjectionSessionPrefs.rememberClassic(context, "com.example.first", Rect(0, 0, 960, 720))
        setField(display, "mKnownClusterDisplayId", 5)
        boot()
        val restoredWork = (field("mOperationGate") as LifecycleGate).capture()
        service.launchOnDashboard("com.example.manual", null)
        assertFalse(restoredWork.isValid)
        assertFalse(service.isBootRestoring())
        assertNotNull(field("mPendingLaunchRunnable"))
    }

    @Test fun `late success after stop cannot rewrite cleared session`() {
        setField(display, "mKnownClusterDisplayId", 5)
        var accepted: Boolean? = null
        val method = service.javaClass.getDeclaredMethod("trackLaunch", String::class.java, Rect::class.java,
            ClusterService.LaunchCallback::class.java).apply { isAccessible = true }
        val callback = method.invoke(service, "com.example.first", null,
            ClusterService.LaunchCallback { accepted = it }) as ClusterService.LaunchCallback
        service.cancelProjectionRestore()
        ProjectionSessionPrefs.clear(context)
        callback.onResult(true)
        assertEquals(false, accepted)
        assertNull(ProjectionSessionPrefs.read(context))
        assertTrue(service.activeProjectionApps().isEmpty())
    }

    @Test fun `late success after display disappeared cannot adopt stale projection`() {
        setField(display, "mKnownClusterDisplayId", 5)
        var accepted: Boolean? = null
        val callback = service.javaClass.getDeclaredMethod("trackLaunch", String::class.java, Rect::class.java,
            ClusterService.LaunchCallback::class.java).apply { isAccessible = true }
            .invoke(service, "com.example.first", null,
                ClusterService.LaunchCallback { accepted = it }) as ClusterService.LaunchCallback
        display.stopWithoutAdb()
        callback.onResult(true)
        assertEquals(false, accepted)
        assertTrue(service.activeProjectionApps().isEmpty())
    }
}

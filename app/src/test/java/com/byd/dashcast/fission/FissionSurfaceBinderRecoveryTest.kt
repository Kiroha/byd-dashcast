package com.byd.dashcast.fission

import android.os.Binder
import android.os.IBinder
import com.byd.dashcast.domain.cluster.ProjectionStateProvider
import com.byd.dashcast.proxy.DaemonBinderResolver
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FissionSurfaceBinderRecoveryTest {
    @After
    fun resetResolver() {
        DaemonBinderResolver.lookupForTesting = null
        DaemonBinderResolver.resetReacquireThrottleForTesting()
    }

    @Test
    fun `stale failure adopts replacement but cannot clear a newer publication`() {
        val orchestrator = FissionOrchestrator(
            RuntimeEnvironment.getApplication(),
            object : ProjectionStateProvider {
                override fun isProjectionActive() = false
                override fun stopProjectionIfActive(onStopped: Runnable?) = onStopped?.run() ?: Unit
            },
            object : FissionOrchestrator.Callbacks {
                override fun onSlotsChanged(slots: MutableCollection<FissionOrchestrator.SlotState>?) {}
                override fun onDaemonBinderAcquired(binder: IBinder?) {}
                override fun onStatusMessage(message: String?) {}
                override fun onSlotError(pkg: String?, message: String?) {}
                override fun onProjectionConflict(proceedCallback: Runnable?) {}
            },
        )
        val field = FissionOrchestrator::class.java.getDeclaredField("mDaemonBinder").apply {
            isAccessible = true
        }
        val recover = FissionOrchestrator::class.java.getDeclaredMethod(
            "recoverSurfaceBinderIfCurrent", IBinder::class.java, String::class.java
        ).apply { isAccessible = true }
        val stale = Binder()
        val replacement = Binder()
        val newer = Binder()
        field.set(orchestrator, stale)
        ShadowSystemClock.advanceBy(Duration.ofMillis(
            DaemonBinderResolver.REACQUIRE_MIN_INTERVAL_MS + 1
        ))
        DaemonBinderResolver.resetReacquireThrottleForTesting()
        DaemonBinderResolver.lookupForTesting = { replacement }

        assertSame(replacement, recover.invoke(orchestrator, stale, "test"))
        field.set(orchestrator, newer)
        assertSame(newer, recover.invoke(orchestrator, stale, "late"))
        assertSame(newer, field.get(orchestrator))
    }
}
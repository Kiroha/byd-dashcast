package com.byd.dashcast.ui.main

import android.app.Application
import android.content.Context
import android.os.Looper
import com.byd.dashcast.infrastructure.AdbLocalClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DisplayStatePollCoordinatorTest {
    private class Host : DisplayStatePollCoordinator.Host {
        var launchTime = System.currentTimeMillis() - 20_000
        var deaths = 0
        override fun getContext(): Context = RuntimeEnvironment.getApplication()
        override fun isActivityAlive() = true
        override fun runOnMainThread(r: Runnable) = r.run()
        override fun getClusterPkg() = "com.nav"
        override fun getLastLaunchTime() = launchTime
        override fun onClusterPkgDied() { deaths++ }
        override fun getMainDisplayPkg(): String? = null
        override fun onMainPkgDied() = Unit
    }

    @Test
    fun `delayed absence from before a same package relaunch cannot clear it`() {
        val host = Host()
        val replies = mutableListOf<AdbLocalClient.Callback>()
        val poll = DisplayStatePollCoordinator(host) { _, _, callback -> replies += callback }
        poll.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(1, replies.size)

        host.launchTime = System.currentTimeMillis()
        replies.single().onSuccess("")

        assertEquals(0, host.deaths)
        poll.stop()
    }

    @Test
    fun `stop and restart reject replies from the previous lifecycle`() {
        val host = Host()
        val replies = mutableListOf<AdbLocalClient.Callback>()
        val poll = DisplayStatePollCoordinator(host) { _, _, callback -> replies += callback }
        poll.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        poll.stop()
        poll.start()
        replies.single().onSuccess("")
        assertEquals(0, host.deaths)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(2, replies.size)
        replies.last().onSuccess("")
        assertEquals(1, host.deaths)
        poll.stop()
    }
}

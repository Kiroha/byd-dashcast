package com.byd.dashcast.satellite

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteVideoHubTest {
    private class Viewer : SatelliteVideoHub.Viewer {
        var resets = 0
        var signals = 0
        override fun reset() { resets++ }
        override fun signal(session: String, message: JSONObject) { signals++ }
    }

    @Test fun `rejection is queued before a concurrent attach advertises readiness`() {
        val messages = CopyOnWriteArrayList<String>()
        val rejecting = CountDownLatch(1)
        val releaseRejection = CountDownLatch(1)
        val attachAttempted = CountDownLatch(1)
        val viewer = Viewer()
        SatelliteVideoHub.connect("atomic") { messages += JSONObject(it).getString("type") }
        val route = Thread {
            SatelliteVideoHub.signal("atomic", JSONObject().put("type", "video.offer")) {
                rejecting.countDown()
                check(releaseRejection.await(10, TimeUnit.SECONDS))
                messages += "error"
            }
        }
        val attach = Thread { attachAttempted.countDown(); SatelliteVideoHub.attach(viewer) }
        try {
            route.start()
            assertTrue(rejecting.await(3, TimeUnit.SECONDS))
            attach.start()
            assertTrue(attachAttempted.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (attach.state != Thread.State.BLOCKED && attach.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals("attach must block on the pending rejection's hub monitor", Thread.State.BLOCKED, attach.state)
            assertTrue(messages.isEmpty())
            releaseRejection.countDown()
            route.join(3000); attach.join(3000)
            assertFalse(route.isAlive); assertFalse(attach.isAlive)
            assertEquals(listOf("error", "video.ready"), messages.toList())
        } finally {
            releaseRejection.countDown(); route.join(3000); if (attach.isAlive) attach.join(3000)
            SatelliteVideoHub.detach(viewer); SatelliteVideoHub.disconnect("atomic")
        }
    }

    @Test fun `old control session cannot publish rejection for its replacement`() {
        SatelliteVideoHub.connect("replacement") {}
        try {
            assertFalse(SatelliteVideoHub.signal("obsolete", JSONObject()) { fail("stale rejection") })
        } finally { SatelliteVideoHub.disconnect("replacement") }
    }

    @Test fun `late viewer notifies the sender and only the current viewer can reply`() {
        val replies = mutableListOf<String>()
        val old = Viewer()
        val current = Viewer()
        SatelliteVideoHub.connect("first") { replies.add(it) }
        SatelliteVideoHub.attach(old)
        assertEquals("video.ready", JSONObject(replies.last()).getString("type"))
        SatelliteVideoHub.attach(current)
        assertEquals(1, old.resets)
        SatelliteVideoHub.reply(old, "first", "old")
        SatelliteVideoHub.reply(current, "first", "current")
        assertEquals("current", replies.last())
        assertFalse(replies.contains("old"))
        SatelliteVideoHub.detach(old)
        assertTrue(SatelliteVideoHub.signal("first", JSONObject().put("type", "video.offer")))
        assertEquals(1, current.signals)
        SatelliteVideoHub.detach(current)
        SatelliteVideoHub.disconnect("first")
    }

    @Test fun `late disconnect and reply from a replaced socket cannot affect its replacement`() {
        val viewer = Viewer()
        val replies = mutableListOf<String>()
        SatelliteVideoHub.attach(viewer)
        SatelliteVideoHub.connect("old") {}
        SatelliteVideoHub.connect("new") { replies.add(it) }
        val resets = viewer.resets
        SatelliteVideoHub.disconnect("old")
        assertEquals(resets, viewer.resets)
        SatelliteVideoHub.reply(viewer, "old", "stale")
        assertFalse(replies.contains("stale"))
        assertFalse(SatelliteVideoHub.signal("old", JSONObject().put("type", "video.offer")))
        assertTrue(SatelliteVideoHub.signal("new", JSONObject().put("type", "video.offer")))
        SatelliteVideoHub.detach(viewer)
        SatelliteVideoHub.disconnect("new")
    }
}

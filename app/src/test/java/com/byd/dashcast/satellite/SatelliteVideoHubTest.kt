package com.byd.dashcast.satellite

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteVideoHubTest {
    private class Viewer : SatelliteVideoHub.Viewer {
        var resets = 0
        var signals = 0
        override fun reset() { resets++ }
        override fun signal(session: String, message: JSONObject) { signals++ }
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

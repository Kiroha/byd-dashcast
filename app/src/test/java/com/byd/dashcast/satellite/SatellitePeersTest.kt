package com.byd.dashcast.satellite

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatellitePeersTest {
    private lateinit var context: Application
    private val identity = SatellitePeerIdentity("12345678-1234-4123-8123-123456789abc", "Carlinkit Tbox Ultra")
    private fun json() = JSONObject().put("id", identity.id).put("name", identity.name)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().clear().commit()
        SatellitePeers.clear(context, history = true)
        SatellitePrefs.setEnabled(context, true)
    }
    @After fun tearDown() { SatellitePrefs.setEnabled(context, false); SatellitePeers.clear(context, history = true) }

    @Test fun `only token authenticated connections populate current and history`() {
        val token = SatellitePrefs.token(context)
        assertFalse(SatellitePeers.authenticated(context, "incorrect", "bad", identity, "192.168.43.2"))
        assertNull(SatellitePeers.snapshot(context).last)
        assertTrue(SatellitePeers.authenticated(context, token, "live", identity, "192.168.43.2"))
        val snapshot = SatellitePeers.snapshot(context)
        assertEquals(identity, snapshot.current!!.identity)
        assertEquals("192.168.43.2", snapshot.current.address)
        assertEquals(identity, snapshot.last!!.identity)
        assertNull(snapshot.last.session)
        assertTrue(snapshot.last.connectedAtMs > 0)
        assertFalse(context.getSharedPreferences(SatellitePrefs.FILE, 0).getString("last_authenticated_peer", "")!!.contains(token))
    }

    @Test fun `disconnect preserves explicitly separate history and cannot clear replacement`() {
        val token = SatellitePrefs.token(context)
        SatellitePeers.authenticated(context, token, "old", identity, "192.168.43.2")
        SatellitePeers.authenticated(context, token, "new", identity, "192.168.43.3")
        SatellitePeers.disconnected("old")
        assertEquals("new", SatellitePeers.snapshot(context).current!!.session)
        SatellitePeers.disconnected("new")
        assertNull(SatellitePeers.snapshot(context).current)
        assertEquals("192.168.43.3", SatellitePeers.snapshot(context).last!!.address)
    }

    @Test fun `legacy hello replaces previous identity with unknown device`() {
        val token = SatellitePrefs.token(context)
        SatellitePeers.authenticated(context, token, "known", identity, "192.168.43.2")
        SatellitePeers.authenticated(context, token, "legacy", null, "192.168.43.3")
        val snapshot = SatellitePeers.snapshot(context)
        assertNull(snapshot.current!!.identity)
        assertNull(snapshot.last!!.identity)
        assertEquals("192.168.43.3", snapshot.current.address)
    }

    @Test fun `disable clears current but retains history and rejects new writes`() {
        val token = SatellitePrefs.token(context)
        SatellitePeers.authenticated(context, token, "live", identity, "192.168.43.2")
        SatellitePrefs.setEnabled(context, false)
        assertFalse(SatellitePeers.authenticated(context, token, "late", identity, "192.168.43.9"))
        assertNull(SatellitePeers.snapshot(context).current)
        assertEquals("192.168.43.2", SatellitePeers.snapshot(context).last!!.address)
    }

    @Test fun `revocation fences a waiting old authentication and clears history`() {
        val token = SatellitePrefs.token(context)
        SatellitePeers.authenticated(context, token, "live", identity, "192.168.43.2")
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val accepted = AtomicReference<Boolean>()
        synchronized(SatellitePrefs) {
            Thread {
                entered.countDown()
                accepted.set(SatellitePeers.authenticated(context, token, "late", identity, "192.168.43.9"))
                completed.countDown()
            }.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            SatellitePrefs.rotateToken(context)
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(false, accepted.get())
        assertEquals(SatellitePeers.Snapshot(null, null), SatellitePeers.snapshot(context))
    }

    @Test fun `malformed display metadata is ignored with no coercion or control text`() {
        assertEquals(identity, SatellitePeers.parse(json().put("future", true)))
        for (value in listOf(null, JSONObject.NULL, "device", json().put("id", 123),
            json().put("id", identity.id.uppercase()), json().put("id", "not-a-uuid"),
            json().put("name", false), json().put("name", ""), json().put("name", " name "),
            json().put("name", "line\nbreak"), json().put("name", "line\u2028break"), json().put("name", "\u202eTbox"),
            json().put("name", "x".repeat(81)), json().put("name", "界".repeat(80)),
            json().put("name", "\uD83D\uDE00"))) assertNull(SatellitePeers.parse(value))
    }

    @Test fun `common receiver ID is only a formatted public fingerprint prefix`() {
        assertEquals("1234-5678-90AB-CDEF", SatellitePeers.receiverId("1234567890abcdef" + "a".repeat(48)))
        assertThrows(IllegalArgumentException::class.java) { SatellitePeers.receiverId("invalid") }
    }

    @Test fun `corrupted history and nonnumeric address never enter display`() {
        context.getSharedPreferences(SatellitePrefs.FILE, 0).edit().putString("last_authenticated_peer", "broken").commit()
        assertNull(SatellitePeers.snapshot(context).last)
        SatellitePeers.authenticated(context, SatellitePrefs.token(context), "live", identity, "tbox.example")
        assertNull(SatellitePeers.snapshot(context).current!!.address)
        SatellitePeers.authenticated(context, SatellitePrefs.token(context), "ipv6", identity, "fe80::1%wlan0")
        assertEquals("fe80::1", SatellitePeers.snapshot(context).current!!.address)
    }
}

package com.byd.dashcast.satellite

import android.app.Application
import com.byd.dashcast.system.CanBusController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class SatelliteProtocolTest {
    private fun frame() = JSONObject().put("type", "navigation.update").put("seq", 1)
        .put("ageMs", 0).put("maneuver", "right").put("distanceMeters", 200)

    @Test fun `portable right turn becomes existing BYD guidance without guessed numeric icons`() {
        val decoded = SatelliteProtocol.navigation(frame())
        assertEquals(CanBusController.ICON_TURN_RIGHT, decoded.data!!.iconId)
        assertEquals(200, decoded.data.distanceMeters)
        assertNull(decoded.data.etaHour)
    }

    @Test fun `roundabout exit and direction preserve all ten existing output codes`() {
        for (exit in 1..10) {
            assertEquals(CanBusController.ICON_ROUNDABOUT_CCW_1_LAP + exit - 1,
                SatelliteProtocol.navigation(frame().put("maneuver", "roundabout_ccw").put("exit", exit)).data!!.iconId)
            assertEquals(CanBusController.ICON_ROUNDABOUT_CW_1_LAP + exit - 1,
                SatelliteProtocol.navigation(frame().put("maneuver", "roundabout_cw").put("exit", exit)).data!!.iconId)
        }
    }

    @Test fun `unknown maneuvers and invalid roundabout exits never reach OEM outputs`() {
        for (value in listOf("unknown", "9", "roundabout_ccw")) rejects {
            SatelliteProtocol.navigation(frame().put("maneuver", value))
        }
        for (exit in listOf(0, 11)) rejects {
            SatelliteProtocol.navigation(frame().put("maneuver", "roundabout_cw").put("exit", exit))
        }
    }

    @Test fun `numeric strings fractions and booleans are rejected instead of coerced`() {
        for (key in listOf("seq", "ageMs", "distanceMeters")) {
            for (value in listOf("1", 1.5, true, JSONObject.NULL)) rejects {
                SatelliteProtocol.navigation(frame().put(key, value))
            }
        }
    }

    @Test fun `old snapshots negative distances and overflow are rejected`() {
        for ((key, value) in listOf("ageMs" to 1501L, "ageMs" to -1L,
            "distanceMeters" to -1L, "distanceMeters" to Long.MAX_VALUE, "seq" to -1L)) rejects {
            SatelliteProtocol.navigation(frame().put(key, value))
        }
    }

    @Test fun `ETA must be complete and within wall clock range`() {
        rejects { SatelliteProtocol.navigation(frame().put("etaHour", 14)) }
        rejects { SatelliteProtocol.navigation(frame().put("etaHour", 24).put("etaMinute", 30)) }
        rejects { SatelliteProtocol.navigation(frame().put("etaHour", 14).put("etaMinute", 60)) }
        assertEquals(30, SatelliteProtocol.navigation(frame().put("etaHour", 14).put("etaMinute", 30)).data!!.etaMinute)
    }

    @Test fun `road names have a bound and no control characters`() {
        for (value in listOf("x".repeat(161), "road\nname", "\u0000", "\u007f")) rejects {
            SatelliteProtocol.navigation(frame().put("roadName", value))
        }
        assertEquals("École", SatelliteProtocol.navigation(frame().put("roadName", "École")).data!!.roadName)
    }

    @Test fun `wire size is bounded in UTF8 bytes rather than UTF16 characters`() {
        rejects { SatelliteProtocol.decode("{\"type\":\"" + "é".repeat(40_000) + "\"}") }
        rejects { SatelliteProtocol.decode("[]") }
    }

    @Test fun `replays cannot extend route liveness and a valid new sequence can resume`() {
        val gate = SatelliteNavigationGate()
        val decoded = SatelliteProtocol.navigation(frame())
        assertTrue(gate.accept(decoded, 1000))
        assertFalse(gate.accept(decoded, 5000))
        assertFalse(gate.expire(6999))
        assertTrue(gate.expire(7000))
        assertFalse(gate.expire(8000))
        assertTrue(gate.accept(decoded.copy(sequence = 2), 8000))
    }

    @Test fun `source age counts toward expiry and stop cancels the expiry`() {
        val gate = SatelliteNavigationGate()
        val decoded = SatelliteProtocol.navigation(frame().put("ageMs", 1500))
        assertTrue(gate.accept(decoded, 1000))
        assertFalse(gate.expire(5499))
        assertTrue(gate.expire(5500))
        assertTrue(gate.accept(decoded.copy(sequence = 2), 6000))
        assertTrue(gate.accept(decoded.copy(sequence = 3, data = null), 6500))
        assertFalse(gate.expire(20_000))
    }

    @Test fun `invalid freshness does not consume sequence number`() {
        val gate = SatelliteNavigationGate()
        val decoded = SatelliteProtocol.navigation(frame())
        assertFalse(gate.accept(decoded.copy(ageMs = 2000), 0))
        assertTrue(gate.accept(decoded, 0))
    }

    @Test fun `only loopback LAN link local and IPv6 unique local peers are accepted`() {
        for (address in listOf("127.0.0.1", "192.168.43.2", "10.0.0.1", "172.16.1.2", "169.254.1.2", "::1", "fd01::1", "fe80::1")) {
            assertTrue(address, SatelliteProtocol.isLocalAddress(InetAddress.getByName(address)))
        }
        for (address in listOf("0.0.0.0", "8.8.8.8", "172.32.0.1", "224.0.0.1", "::", "2001:4860::1", "ff02::1")) {
            assertFalse(address, SatelliteProtocol.isLocalAddress(InetAddress.getByName(address)))
        }
    }

    @Test fun `authentication rejects wrong and truncated installation tokens`() {
        assertTrue(SatellitePrefs.tokenMatches("example", "example"))
        assertFalse(SatellitePrefs.tokenMatches("example", "examplf"))
        assertFalse(SatellitePrefs.tokenMatches("example", "exampl"))
    }

    @Test fun `versioned companion compatibility fixtures are executed by the receiver`() {
        val fixture = File("../docs/satellite/navigation-fixtures.json")
        val contract = JSONObject(fixture.readText())
        assertEquals(SatelliteProtocol.VERSION, contract.getInt("version"))
        val accepted = contract.getJSONArray("accepted")
        repeat(accepted.length()) { SatelliteProtocol.navigation(accepted.getJSONObject(it)) }
        val rejected = contract.getJSONArray("rejected")
        repeat(rejected.length()) { rejects { SatelliteProtocol.navigation(rejected.getJSONObject(it)) } }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid protocol input was accepted") } catch (_: IllegalArgumentException) {}
        catch (_: org.json.JSONException) {}
    }
}

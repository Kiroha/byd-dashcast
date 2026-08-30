package com.byd.dashcast.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * PRE-0 characterisation net — navigation-text parsers of `MapNotificationListenerService`.
 *
 * Freezes CURRENT behaviour, defects included. These are pure static functions over text, so
 * unlike the other two seismographs this one is genuinely discriminating: break a parser and a
 * case here goes red.
 *
 * Why these: everything the driver sees on the HUD is derived from a third-party notification
 * string by these seven functions. Wave V2 rewrites the HUD staleness watchdog (AUD-003) and wave
 * V5 touches the nav path; the parsers themselves must not drift while that happens.
 *
 * They are private statics, reached here by reflection. That is deliberate: exposing them would be
 * production refactoring, which a test commit must not do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavTextParsersCharacterizationTest {

    private fun m(name: String, vararg types: Class<*>): Method =
        MapNotificationListenerService::class.java
            .getDeclaredMethod(name, *types)
            .apply { isAccessible = true }

    private fun dist(text: String): Int =
        m("parseFirstDistance", String::class.java).invoke(null, text) as Int

    private fun road(text: String): String =
        m("parseRoadName", String::class.java).invoke(null, text) as String

    private fun eta(text: String?): IntArray? =
        m("parseEtaClock", String::class.java).invoke(null, text) as IntArray?

    private fun exit(lower: String?): Int =
        m("parseRoundaboutExit", String::class.java).invoke(null, lower) as Int

    private fun secs(text: String?): Int? =
        m("parseRemainingSeconds", String::class.java).invoke(null, text) as Int?

    // ── Distances ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `distance units convert to metres`() {
        assertEquals(300, dist("In 300 m turn right"))
        assertEquals(1200, dist("1.2 km to destination"))
        assertEquals(1200, dist("1,2 km to destination"))   // comma decimal (fr/ru locales)
        assertEquals(1609, dist("1 mi ahead"))
        assertEquals(300, dist("300 м направо"))            // cyrillic metre
    }

    @Test
    fun `distance returns minus one when the text carries none`() {
        assertEquals(-1, dist("Continue straight"))
    }

    // ── Road name ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `road name is taken after the preposition`() {
        assertEquals("Baker Street", road("Turn right onto Baker Street"))
        assertEquals("Rue de Rivoli", road("Tournez sur Rue de Rivoli"))
    }

    @Test
    fun `road name is empty when no preposition matches`() {
        assertEquals("", road("Continue straight ahead"))
    }

    // ── ETA clock ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `eta clock reads a wall clock and applies am pm`() {
        assertArrayEquals(intArrayOf(14, 35), eta("Arrive at 14:35"))
        assertArrayEquals(intArrayOf(19, 5), eta("ETA 7:05 pm"))
        assertArrayEquals(intArrayOf(0, 30), eta("ETA 12:30 am"))
    }

    @Test
    fun `eta clock returns null when there is no clock at all`() {
        assertNull(eta("Turn right in 300 m"))
        assertNull(eta(null))
    }

    @Test
    fun `eta clock prefers the time associated with arrival over a duration`() {
        assertArrayEquals(intArrayOf(15, 45), eta("1:20 remaining · arrive 15:45"))
        assertArrayEquals(intArrayOf(15, 45), eta("15:45 arrivée · durée 1:20"))
    }

    @Test
    fun `multiple bare clocks retain the vendor summary convention that ETA is last`() {
        assertArrayEquals(intArrayOf(15, 45), eta("1:20 · 15:45"))
    }

    // ── Roundabout exit ──────────────────────────────────────────────────────────────────────

    @Test
    fun `roundabout exit is bounded to one through ten`() {
        assertEquals(3, exit("take the 3rd exit"))
        assertEquals(0, exit("take the 11th exit"))   // out of range → 0, not 11
        assertEquals(0, exit("continue straight"))
        assertEquals(0, exit(null))
    }

    // ── Remaining time ───────────────────────────────────────────────────────────────────────

    @Test
    fun `remaining time sums hours and minutes`() {
        assertEquals(12 * 60, secs("12 min"))
        assertEquals(3900, secs("1 h 05 min"))
        assertEquals(7800, secs("2 ч 10 мин"))
    }

    @Test
    fun `remaining time is null when the text carries none`() {
        assertNull(secs("Turn right"))
        assertNull(secs(""))
        assertNull(secs(null))
    }
}

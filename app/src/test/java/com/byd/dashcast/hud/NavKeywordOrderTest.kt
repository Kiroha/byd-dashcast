package com.byd.dashcast.hud

import com.byd.dashcast.system.CanBusController
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of TEXT_KEYWORD_MAP, which decides which arrow a driver sees.
 *
 * The lookup is first-match-wins over a flat array, so a generic keyword placed above a specific
 * one silently shadows it. The file documents that discipline in three places — "must be before
 * generic", "most specific first", "must not shadow more specific keys" — and the full audit found
 * one pair where it had not been applied: "exit" sat above "roundabout".
 *
 * Almost every real roundabout instruction names an exit, so that single ordering turned every
 * roundabout into a motorway-exit arrow. These cases pin the pair, and the generic fallbacks that
 * must keep working underneath it.
 */
class NavKeywordOrderTest {

    private fun icon(s: String) = MapNotificationListenerService.resolveIconFromText(s.lowercase())

    @Test
    fun `a roundabout naming its exit is a roundabout, in every supported language`() {
        val roundabout = CanBusController.ICON_ROUNDABOUT_CW_1_LAP
        assertEquals("EN", roundabout, icon("At the roundabout, take the 3rd exit"))
        assertEquals("FR", roundabout, icon("Au rond-point, prenez la 3e sortie"))
        assertEquals("DE", roundabout, icon("Im Kreisverkehr, nehmen Sie die 3. Ausfahrt"))
    }

    @Test
    fun `a plain roundabout is still a roundabout`() {
        assertEquals(CanBusController.ICON_ROUNDABOUT_CW_1_LAP, icon("Enter the roundabout"))
    }

    /**
     * The other half: fixing the shadowing must not have broken the exit keyword itself. A motorway
     * exit that does NOT mention a roundabout must still draw the exit arrow.
     */
    @Test
    fun `a motorway exit with no roundabout is still an exit`() {
        assertEquals(CanBusController.ICON_DETOUR_RIGHT, icon("Take the exit toward A7"))
        assertEquals(CanBusController.ICON_DETOUR_RIGHT, icon("Prenez la sortie 12"))
        assertEquals(CanBusController.ICON_DETOUR_RIGHT, icon("Ausfahrt nehmen"))
    }

    /**
     * The specificity rule the rest of the table already relies on, spot-checked so a future
     * reordering cannot quietly undo it.
     */
    @Test
    fun `specific keywords still win over the generic ones below them`() {
        assertEquals("U-turn must beat plain 'right'",
            CanBusController.ICON_U_TURN_RIGHT, icon("Make a U-turn right"))
        assertEquals("destination must beat plain 'right'",
            CanBusController.ICON_DESTINATION, icon("You have arrived at your destination, on the right"))
        assertEquals("'continue straight' must not be read as a turn",
            CanBusController.ICON_STRAIGHT_SOLID, icon("Continue straight for 2 km"))
    }

    @Test
    fun `an instruction with no known keyword returns not-found rather than guessing`() {
        assertEquals(-1, icon("Recalculating"))
    }
}

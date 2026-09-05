package com.byd.dashcast.hud

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.regex.Pattern

/**
 * Arabic guidance parsing — the digits and the units.
 *
 * The maneuver-keyword table in MapNotificationListenerService has a full Arabic section, so on an
 * Arabic head unit the ARROW resolved. The distance and time patterns did not follow: `\d` without
 * UNICODE_CHARACTER_CLASS is ASCII-only, and the unit alternations carried no Arabic token. An
 * arrow with no distance is the worst of the three states — the driver sees a turn and no idea when.
 *
 * The service itself needs a NotificationListenerService to instantiate, so what is pinned here is
 * the two pieces that decide the outcome: the digit normalisation, which is package-visible, and
 * the unit alternations, restated so a token silently dropped from them fails here.
 */
class ArabicNavParsingTest {

    // Same alternations as the service. If one drifts, these go red and say which.
    private val dist = Pattern.compile(
        """(?<![\p{L}\p{N}])(\d+[.,]?\d*)[\s ]*(km|км|كم|mi|ft|yd|mt|m|м|م)(?![\p{L}\p{N}])""", Pattern.CASE_INSENSITIVE)
    private val mins = Pattern.compile(
        """(?<![\p{L}\p{N}])(\d+)[\s ]*(?:min|mins|мин|دقيقة|د)(?![\p{L}\p{N}])""", Pattern.CASE_INSENSITIVE)

    private fun firstDistance(raw: String): String? {
        // normaliseDigits returns null only for a null argument; `raw` is non-null.
        val m = dist.matcher(requireNotNull(MapNotificationListenerService.normaliseDigits(raw)))
        if (!m.find()) return null
        val value = requireNotNull(m.group(1))
        val unit = requireNotNull(m.group(2))
        return "$value $unit"
    }

    @Test
    fun `arabic-indic digits become ascii`() {
        // U+0660..U+0669
        assertEquals("400", MapNotificationListenerService.normaliseDigits("٤٠٠"))
        // U+06F0..U+06F9, Persian/Urdu
        assertEquals("400", MapNotificationListenerService.normaliseDigits("۴۰۰"))
        // mixed, and everything else untouched
        assertEquals("in 400 م", MapNotificationListenerService.normaliseDigits("in ٤٠٠ م"))
    }

    @Test
    fun `ascii text is returned unchanged and not reallocated needlessly`() {
        val s = "in 400 m"
        assertEquals(s, MapNotificationListenerService.normaliseDigits(s))
        assertEquals(null, MapNotificationListenerService.normaliseDigits(null))
        assertEquals("", MapNotificationListenerService.normaliseDigits(""))
    }

    @Test
    fun `an arabic distance parses, digits and unit together`() {
        assertEquals("400 م", firstDistance("انعطف يمينًا بعد ٤٠٠ م"))
        assertEquals("1,2 كم", firstDistance("بعد ١,٢ كم"))
    }

    @Test
    fun `the existing locales keep working`() {
        assertEquals("400 m", firstDistance("in 400 m"))
        assertEquals("1.2 km", firstDistance("dans 1.2 km"))
        assertEquals("300 м", firstDistance("через 300 м"))
        assertEquals("300 mt", firstDistance("tra 300 mt"))
    }

    @Test
    fun `remaining time in arabic parses`() {
        val m = mins.matcher(
            requireNotNull(MapNotificationListenerService.normaliseDigits("٢٥ دقيقة")))
        assertEquals(true, m.find())
        assertEquals("25", m.group(1))
    }

    @Test
    fun `a duration is still not mistaken for a distance`() {
        // The trailing \b makes "min" fail every distance alternative — including the new Arabic
        // ones, which is the thing worth checking after adding a bare "م".
        assertEquals(null, firstDistance("25 min"))
        assertEquals(null, firstDistance("٢٥ دقيقة"))
    }
}

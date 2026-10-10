package com.byd.dashcast.hud

import java.util.Locale

/** Established OEM navigation text, shared by the broadcast and native cluster paths. */
internal object NavigationTextFormatter {
    // These unit strings are part of the OEM input, not localized application labels.
    // Preserve the broadcast's decimal separator regardless of the device language.
    fun formatMeters(meters: Int): String {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.1f km", meters / 1000.0f)
        }
        return meters.toString() + " m"
    }

    fun formatSeconds(totalSeconds: Int): String {
        val totalMinutes = totalSeconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) (h.toString() + "h " + m + "m") else (m.toString() + " min")
    }
}

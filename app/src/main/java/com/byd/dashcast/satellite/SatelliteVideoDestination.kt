package com.byd.dashcast.satellite

/** Never keep the viewer on a fallback display after the existing cluster projection disappears.
 * The service's resolved display remains authoritative (some OEM ROMs hide it from DisplayManager). */
internal object SatelliteVideoDestination {
    fun allowed(expected: Int, actual: Int, resolved: Int, active: Boolean): Boolean =
        expected > 0 && actual == expected && resolved == expected && active
}

package com.byd.dashcast.cluster

import com.byd.dashcast.cluster.ProjectionSafetyPolicy.Verdict
import com.byd.dashcast.cluster.ProjectionSafetyPolicy.isAllowed
import com.byd.dashcast.cluster.ProjectionSafetyPolicy.verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSafetyPolicyTest {

    /**
     * The regression this file exists to prevent. v1.8.29 refused `com.byd.androidauto` by name and
     * every persistent process by rule, which removed Android Auto AND CarPlay — the whole point of
     * the app — from the picker. Projecting them is not the fault; losing them on the way out was,
     * and that is fixed in forceStopApp.
     */
    @Test
    fun `Android Auto and CarPlay stay projectable — they are what the app is for`() {
        for (pkg in listOf(
            "com.byd.androidauto",
            "com.byd.carplay.ui",
            "com.ts.carplay.app",
            "com.autochips.carplayapp"
        )) {
            assertTrue(pkg, isAllowed(pkg, isHomeHandler = false))
        }
    }

    /**
     * The corpus refutes "system uid means unprojectable": both of these run as `system`, were
     * force-stopped cleanly, and com.byd.avc has been projected successfully four times.
     */
    @Test
    fun `OEM system apps that people already project stay projectable`() {
        assertTrue(isAllowed("com.byd.avc", isHomeHandler = false))
        assertTrue(isAllowed("com.byd.mediacenter", isHomeHandler = false))
        assertTrue(isAllowed("com.byd.carsettings", isHomeHandler = false))
    }

    /**
     * Resolved at runtime, not hard-coded: the launcher actually in use on 27 corpus cars is
     * com.lexwah.kinex or com.dudu.autoui, neither of which is in the hard-coded launcher list.
     */
    @Test
    fun `whatever currently handles HOME is refused, including launchers we never listed`() {
        assertEquals(Verdict.DENIED_IS_HOME, verdict("com.lexwah.kinex", isHomeHandler = true))
        assertEquals(Verdict.DENIED_IS_HOME, verdict("com.dudu.autoui", isHomeHandler = true))
        // The same package is fine when it is NOT the active home.
        assertTrue(isAllowed("com.dudu.autoui", isHomeHandler = false))
    }

    @Test
    fun `ordinary apps are untouched`() {
        for (pkg in listOf("com.waze", "com.google.android.apps.maps", "org.schabi.newpipe")) {
            assertTrue(pkg, isAllowed(pkg, isHomeHandler = false))
        }
    }

    /** Nothing but the home screen is refused today, and that is deliberate. */
    @Test
    fun `the name list is empty`() {
        assertTrue(ProjectionSafetyPolicy.ALWAYS_DENIED.isEmpty())
    }

    @Test
    fun `null and empty never crash the caller`() {
        assertTrue(isAllowed(null, isHomeHandler = false))
        assertTrue(isAllowed("", isHomeHandler = true))
    }

    @Test
    fun `every verdict has a distinct human reason`() {
        val reasons = Verdict.values().map { ProjectionSafetyPolicy.reason(it) }
        assertEquals(reasons.size, reasons.toSet().size)
        assertFalse(reasons.any { it.isBlank() })
    }
}

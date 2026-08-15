package com.byd.dashcast.cluster

import com.byd.dashcast.cluster.ProjectionSafetyPolicy.Verdict
import com.byd.dashcast.cluster.ProjectionSafetyPolicy.isAllowed
import com.byd.dashcast.cluster.ProjectionSafetyPolicy.verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionSafetyPolicyTest {

    @Test
    fun `the package from INC-20260815-181820 is refused`() {
        assertEquals(
            Verdict.DENIED_KNOWN_HARMFUL,
            verdict("com.byd.androidauto", isPersistent = false, isHomeHandler = false)
        )
    }

    /**
     * The corpus refutes "system uid means unprojectable": both of these run as `system`, were
     * force-stopped cleanly, and com.byd.avc has been projected successfully four times. Blocking
     * them would remove a working feature to fix a fault they never caused.
     */
    @Test
    fun `system apps that force-stop cleanly stay projectable`() {
        assertTrue(isAllowed("com.byd.avc", isPersistent = false, isHomeHandler = false))
        assertTrue(isAllowed("com.byd.mediacenter", isPersistent = false, isHomeHandler = false))
    }

    @Test
    fun `a persistent process is refused whatever its name`() {
        assertEquals(
            Verdict.DENIED_PERSISTENT,
            verdict("com.example.whatever", isPersistent = true, isHomeHandler = false)
        )
    }

    /**
     * Resolved at runtime, not hard-coded: the launcher actually in use on 27 corpus cars is
     * com.lexwah.kinex or com.dudu.autoui, neither of which is in the hard-coded launcher list.
     */
    @Test
    fun `whatever currently handles HOME is refused, including launchers we never listed`() {
        assertEquals(
            Verdict.DENIED_IS_HOME,
            verdict("com.lexwah.kinex", isPersistent = false, isHomeHandler = true)
        )
        assertEquals(
            Verdict.DENIED_IS_HOME,
            verdict("com.dudu.autoui", isPersistent = false, isHomeHandler = true)
        )
        // The same package is fine when it is NOT the active home.
        assertTrue(isAllowed("com.dudu.autoui", isPersistent = false, isHomeHandler = false))
    }

    @Test
    fun `ordinary apps are untouched`() {
        for (pkg in listOf("com.waze", "com.google.android.apps.maps", "org.schabi.newpipe")) {
            assertTrue(pkg, isAllowed(pkg, isPersistent = false, isHomeHandler = false))
        }
    }

    @Test
    fun `null and empty never crash the caller`() {
        assertTrue(isAllowed(null, isPersistent = false, isHomeHandler = false))
        assertTrue(isAllowed("", isPersistent = true, isHomeHandler = true))
    }

    @Test
    fun `every verdict has a distinct human reason`() {
        val reasons = Verdict.values().map { ProjectionSafetyPolicy.reason(it) }
        assertEquals(reasons.size, reasons.toSet().size)
        assertFalse(reasons.any { it.isBlank() })
    }
}

package com.byd.dashcast.hud

import org.junit.Assert.*
import org.junit.Test

class NavigationDiagnosticsTest {
    private val pkg = "app.morphe.android.apps.maps"

    @Test fun `receipt and rejection remain visible without raw notification capture`() {
        val diagnostics = NavigationDiagnostics()
        diagnostics.observe(pkg, 0)
        val entry = diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 0,
            "maps_2025", 100, 5, 21, 0)

        assertTrue(entry!!.contains("reason=no_maneuver smallIcon='maps_2025' dist=100"))
        val report = diagnostics.summary(20_000)
        assertTrue(report.contains("observed=1 duplicate=0 parsed=0"))
        assertTrue(report.contains("lastObservedAgeMs=20000"))
        assertTrue(report.contains("no_maneuver=1"))
        assertTrue(report.contains("deliveryAttempts=0 acceptedAny=0"))
    }

    @Test fun `repeated rejects are counted even when journal logging is throttled`() {
        val diagnostics = NavigationDiagnostics()
        assertNotNull(diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 0))
        assertNull(diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 1_000))
        assertNotNull(diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 30_000))
        assertTrue(diagnostics.summary(30_000).contains("no_maneuver=3"))
    }

    @Test fun `a new rejection reason is logged immediately`() {
        val diagnostics = NavigationDiagnostics()
        diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 0)
        assertNotNull(diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_DISTANCE, 1))
    }

    @Test fun `duplicates and coalesced frames do not imply extra transport deliveries`() {
        val diagnostics = NavigationDiagnostics()
        repeat(3) { diagnostics.observe(pkg, it.toLong()) }
        diagnostics.duplicate()
        repeat(2) { diagnostics.parsed() }
        diagnostics.delivery(pkg, true, true, 3)

        val report = diagnostics.summary(3)
        assertTrue(report.contains("observed=3 duplicate=1 parsed=2"))
        assertTrue(report.contains("deliveryAttempts=1 acceptedAny=1 unavailable=0 errors=0"))
    }

    @Test fun `disabled outputs and transport exceptions have distinct evidence`() {
        val diagnostics = NavigationDiagnostics()
        diagnostics.delivery(pkg, false, false, 0)
        diagnostics.delivery(pkg, true, false, 1, "SecurityException")
        assertTrue(diagnostics.summary(1).contains("deliveryAttempts=2 acceptedAny=0 unavailable=1 errors=1"))
        assertTrue(diagnostics.summary(1).contains("outputsEnabled=true acceptedAny=false errorClass=SecurityException"))
    }

    @Test fun `repeated delivery failures are throttled and a failure after recovery is visible`() {
        val diagnostics = NavigationDiagnostics()
        assertNotNull(diagnostics.delivery(pkg, true, false, 0))
        assertNull(diagnostics.delivery(pkg, true, false, 1))
        assertNull(diagnostics.delivery(pkg, true, true, 2))
        assertNotNull(diagnostics.delivery(pkg, true, false, 3))
        assertTrue(diagnostics.summary(3).contains("acceptedAny=1 unavailable=3"))
    }

    @Test fun `resource identifiers are bounded and cannot inject extra log lines`() {
        val diagnostics = NavigationDiagnostics()
        val entry = diagnostics.reject(pkg, NavigationDiagnostics.Rejection.NO_MANEUVER, 0,
            "maps_2025\nforged='line'" + "x".repeat(200))!!
        assertFalse(entry.contains('\n'))
        assertFalse(entry.contains("forged='line'"))
        assertTrue(entry.length < 300)
    }
}

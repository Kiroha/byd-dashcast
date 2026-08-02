package com.byd.dashcast.ui.diag

import com.byd.dashcast.ui.diag.ApkExtractionPolicy.Skip
import com.byd.dashcast.ui.diag.ApkExtractionPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkExtractionPolicyTest {

    // ── the primary target ─────────────────────────────────────────────────────

    @Test
    fun `the container service is tier1 even though its name has neither byd nor cluster`() {
        // The single biggest design risk: a pattern-only filter would miss this package.
        assertEquals(
            Tier.TIER1,
            ApkExtractionPolicy.classify(
                "com.xdja.containerservice", "/system/priv-app/xdjacs/xdjacs.apk")
        )
    }

    @Test
    fun `all three named targets classify as tier1`() {
        for (pkg in ApkExtractionPolicy.TIER1) {
            assertEquals(pkg, Tier.TIER1,
                ApkExtractionPolicy.classify(pkg, "/system/app/$pkg/base.apk"))
        }
    }

    // ── "cluster" is found wherever it appears ──────────────────────────────────

    @Test
    fun `a cluster-named app is swept in even when installed under data`() {
        // The user's explicit ask: search for "cluster" everywhere, not only on firmware.
        assertEquals(
            Tier.TIER2,
            ApkExtractionPolicy.classify(
                "com.thirdparty.clustertheme", "/data/app/~~abc==/com.thirdparty.clustertheme/base.apk")
        )
    }

    @Test
    fun `a package is matched by its PATH even when its name has no keyword`() {
        // Package name is neutral, but the apk path says "cluster" → still caught.
        assertEquals(
            Tier.TIER2,
            ApkExtractionPolicy.classify("com.oem.svc", "/system/priv-app/ClusterRenderer/base.apk")
        )
    }

    @Test
    fun `a named tier1 target is tier1 regardless of where it is installed`() {
        assertEquals(
            Tier.TIER1,
            ApkExtractionPolicy.classify(
                "com.xdja.containerservice", "/data/app/com.xdja.containerservice/base.apk")
        )
    }

    @Test
    fun `partition is labelled but never excludes`() {
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/system/app/x/base.apk"))
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/vendor/app/x/base.apk"))
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/apex/com.android.x/x.apk"))
        assertEquals("data", ApkExtractionPolicy.partitionLabel("/data/app/x/base.apk"))
        assertEquals("?", ApkExtractionPolicy.partitionLabel(null))
    }

    // ── pattern sweep (tier 2) ──────────────────────────────────────────────────

    @Test
    fun `a system fission package matches the pattern sweep as tier2`() {
        assertEquals(
            Tier.TIER2,
            ApkExtractionPolicy.classify("com.byd.fissionhost", "/system/app/fh/fh.apk")
        )
    }

    @Test
    fun `an unrelated system package is excluded`() {
        assertEquals(
            Tier.EXCLUDED,
            ApkExtractionPolicy.classify("com.android.calculator2", "/system/app/calc/calc.apk")
        )
    }

    @Test
    fun `blank package is excluded`() {
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classify("", "/system/app/x/base.apk"))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classify(null, "/system/app/x/base.apk"))
    }

    // ── ordering: named targets claim the budget first ──────────────────────────

    @Test
    fun `tier1 sorts before tier2`() {
        assertTrue(ApkExtractionPolicy.order(Tier.TIER1) < ApkExtractionPolicy.order(Tier.TIER2))
    }

    // ── budget arithmetic ───────────────────────────────────────────────────────

    @Test
    fun `a normal APK within budget is admitted`() {
        assertEquals(Skip.NONE, ApkExtractionPolicy.admit(4L * 1024 * 1024, 0L, 0))
    }

    @Test
    fun `an APK bigger than the per-file cap is rejected`() {
        assertEquals(
            Skip.TOO_BIG,
            ApkExtractionPolicy.admit(ApkExtractionPolicy.BUDGET_FILE + 1, 0L, 0)
        )
    }

    @Test
    fun `an APK that would exceed the total budget is rejected`() {
        val already = ApkExtractionPolicy.BUDGET_TOTAL - 1024
        assertEquals(Skip.OVER_BUDGET, ApkExtractionPolicy.admit(4096, already, 1))
    }

    @Test
    fun `the count backstop rejects beyond the max`() {
        assertEquals(
            Skip.MAX_COUNT,
            ApkExtractionPolicy.admit(1024, 0L, ApkExtractionPolicy.MAX_COUNT)
        )
    }

    @Test
    fun `the total budget stays under Telegram's 50 MB document ceiling`() {
        assertTrue(ApkExtractionPolicy.BUDGET_TOTAL < 50L * 1024 * 1024)
        // A single file must not be allowed to consume the whole budget and starve tier 1.
        assertTrue(ApkExtractionPolicy.BUDGET_FILE < ApkExtractionPolicy.BUDGET_TOTAL)
    }

    // ── native binaries ─────────────────────────────────────────────────────────

    @Test
    fun `the native process that returns the -1 is a named tier1 target`() {
        // fission_service registers AutoContainerNative, which returns -1 on trinket.
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("fission_service"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("fission_screennproject"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("BydClusterManager"))
    }

    @Test
    fun `an unrelated fission-family binary matches the native sweep as tier2`() {
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classifyNative("fission_toolbox"))
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classifyNative("BydClusterKanzi"))
    }

    @Test
    fun `an unrelated system binary is excluded from the native sweep`() {
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative("toybox"))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative(""))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative(null))
    }

    @Test
    fun `a small native dispatcher within budget is admitted`() {
        assertEquals(Skip.NONE, ApkExtractionPolicy.admitNative(30 * 1024, 20L * 1024 * 1024, 0))
    }

    @Test
    fun `the multi-MB Kanzi renderer is skipped by the native per-file cap`() {
        assertEquals(
            Skip.TOO_BIG,
            ApkExtractionPolicy.admitNative(8L * 1024 * 1024, 0L, 0)
        )
    }

    @Test
    fun `native shares the APK budget so the whole bundle stays under Telegram's ceiling`() {
        // With the APKs already near the ceiling, a native file that would overflow is rejected.
        val nearFull = ApkExtractionPolicy.BUDGET_TOTAL - 10 * 1024
        assertEquals(Skip.OVER_BUDGET, ApkExtractionPolicy.admitNative(1L * 1024 * 1024, nearFull, 1))
    }

    @Test
    fun `native count backstop rejects beyond the native max`() {
        assertEquals(
            Skip.MAX_COUNT,
            ApkExtractionPolicy.admitNative(1024, 0L, ApkExtractionPolicy.NATIVE_MAX_COUNT)
        )
    }
}

package com.byd.dashcast.ui.diag

import com.byd.dashcast.ui.diag.ApkExtractionPolicy.Skip
import com.byd.dashcast.ui.diag.ApkExtractionPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ApkExtractionPolicyTest {

    // ── the named targets ───────────────────────────────────────────────────────

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
    fun `every named target classifies as tier1 wherever it is installed`() {
        for (pkg in ApkExtractionPolicy.TIER1) {
            assertEquals(pkg, Tier.TIER1,
                ApkExtractionPolicy.classify(pkg, "/system/app/$pkg/base.apk"))
            assertEquals(pkg, Tier.TIER1,
                ApkExtractionPolicy.classify(pkg, "/data/app/$pkg/base.apk"))
        }
    }

    @Test
    fun `clusterdebug and automap are named tier1 targets`() {
        assertTrue(ApkExtractionPolicy.TIER1.contains("com.byd.clusterdebug"))
        assertTrue(ApkExtractionPolicy.TIER1.contains("com.byd.automap"))
        assertTrue(ApkExtractionPolicy.TIER1.contains("com.example.amapservice"))
    }

    @Test
    fun `both container-service implementations are tier1 - xdja on DL3-trinket, byd on DL5_0`() {
        // The 1.8.10 narrowing dropped the generic "byd" and stopped pulling com.byd.containerservice
        // (the DL5.0 container service). Both names must be captured.
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classify(
            "com.xdja.containerservice", "/system/priv-app/x/x.apk"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classify(
            "com.byd.containerservice", "/system/priv-app/BydContainerService/BydContainerService.apk"))
    }

    @Test
    fun `a container-named package is caught by the sweep wherever it lives`() {
        // Belt-and-suspenders beyond the named list: the "container" pattern subsumes "autocontainer".
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classify(
            "com.oem.containerhelper", "/system/app/c/c.apk"))
    }

    // ── narrowed scope: the generic "byd" sweep is gone ─────────────────────────

    @Test
    fun `unrelated com_byd apps we already RE'd are no longer swept in`() {
        // These were pulled by the old "byd" pattern; RE proved they are irrelevant to projection.
        for (pkg in listOf(
            "com.byd.acquisitioncontrol", "com.byd.xcall", "com.byd.appstartmanagement",
            "com.byd.filemanager", "com.byd.androidauto", "com.byd.media.autoplay",
            "com.byd.network.networksetting", "com.byd.logswitch"
        )) {
            assertEquals(pkg, Tier.EXCLUDED,
                ApkExtractionPolicy.classify(pkg, "/system/app/$pkg/base.apk"))
        }
    }

    // ── the projection sweep (tier 2) ───────────────────────────────────────────

    @Test
    fun `a cluster-named app is swept in even when installed under data`() {
        assertEquals(
            Tier.TIER2,
            ApkExtractionPolicy.classify(
                "com.thirdparty.clustertheme", "/data/app/~~abc==/com.thirdparty.clustertheme/base.apk")
        )
    }

    @Test
    fun `a package is matched by its PATH even when its name has no keyword`() {
        assertEquals(
            Tier.TIER2,
            ApkExtractionPolicy.classify("com.oem.svc", "/system/priv-app/ClusterRenderer/base.apk")
        )
    }

    @Test
    fun `projection-surface terms sweep as tier2`() {
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classify("com.byd.fissionhost", "/system/app/fh/fh.apk"))
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classify("com.x.projectionmanager", "/system/app/p/p.apk"))
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classify("com.x.instrumentcluster", "/system/app/i/i.apk"))
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

    @Test
    fun `partition is labelled but never excludes`() {
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/system/app/x/base.apk"))
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/vendor/app/x/base.apk"))
        assertEquals("system", ApkExtractionPolicy.partitionLabel("/apex/com.android.x/x.apk"))
        assertEquals("data", ApkExtractionPolicy.partitionLabel("/data/app/x/base.apk"))
        assertEquals("?", ApkExtractionPolicy.partitionLabel(null))
    }

    @Test
    fun `tier1 sorts before tier2`() {
        assertTrue(ApkExtractionPolicy.order(Tier.TIER1) < ApkExtractionPolicy.order(Tier.TIER2))
    }

    // ── budget: APKs are capped below a native reserve ──────────────────────────

    @Test
    fun `the reserve carves the total budget into an APK budget plus native room`() {
        assertEquals(
            ApkExtractionPolicy.BUDGET_TOTAL,
            ApkExtractionPolicy.APK_BUDGET + ApkExtractionPolicy.NATIVE_RESERVE
        )
        assertTrue("native reserve must be meaningful", ApkExtractionPolicy.NATIVE_RESERVE >= 8L * 1024 * 1024)
    }

    @Test
    fun `a normal APK within budget is admitted`() {
        assertEquals(Skip.NONE, ApkExtractionPolicy.admit(4L * 1024 * 1024, 0L, 0))
    }

    @Test
    fun `an APK bigger than the per-file cap is rejected`() {
        assertEquals(Skip.TOO_BIG, ApkExtractionPolicy.admit(ApkExtractionPolicy.BUDGET_FILE + 1, 0L, 0))
    }

    @Test
    fun `APKs cannot spend into the native reserve`() {
        // Right at the APK budget, one more byte of APK is rejected — the reserve is protected.
        val atApkBudget = ApkExtractionPolicy.APK_BUDGET - 1024
        assertEquals(Skip.OVER_BUDGET, ApkExtractionPolicy.admit(2048, atApkBudget, 1))
    }

    @Test
    fun `the APK count backstop rejects beyond the max`() {
        assertEquals(Skip.MAX_COUNT, ApkExtractionPolicy.admit(1024, 0L, ApkExtractionPolicy.MAX_COUNT))
    }

    @Test
    fun `the total budget stays under Telegram's 50 MB document ceiling`() {
        assertTrue(ApkExtractionPolicy.BUDGET_TOTAL < 50L * 1024 * 1024)
        assertTrue(ApkExtractionPolicy.BUDGET_FILE < ApkExtractionPolicy.APK_BUDGET)
    }

    // ── native binaries AND .so libraries ───────────────────────────────────────

    @Test
    fun `the native process that returns the -1 is a named tier1 target`() {
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("fission_service"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("fission_screennproject"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("BydClusterManager"))
    }

    @Test
    fun `the projection so libraries are named tier1 native targets`() {
        // RE showed the real routing lives in these libs, not the CLI bins.
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("libxdjacontainerservice_jni.so"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("libfission_services.so"))
        assertEquals(Tier.TIER1, ApkExtractionPolicy.classifyNative("libfission_event.so"))
    }

    @Test
    fun `a fission-family lib matches the native sweep as tier2`() {
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classifyNative("libfission_utils.so"))
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classifyNative("libBydClusterKanzi.so"))
        assertEquals(Tier.TIER2, ApkExtractionPolicy.classifyNative("fission_toolbox"))
    }

    @Test
    fun `unrelated binaries and libraries are excluded from the native sweep`() {
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative("toybox"))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative("libc.so"))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative("libEGL.so"))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative(""))
        assertEquals(Tier.EXCLUDED, ApkExtractionPolicy.classifyNative(null))
    }

    @Test
    fun `the shell pre-filter matches every named native target`() {
        // The daemon enumerates dirs with `grep -iE NATIVE_GREP` BEFORE classifyNative runs; if a
        // named target did not match the grep it would never be seen. Guard that invariant.
        val tokens = ApkExtractionPolicy.NATIVE_GREP.lowercase(Locale.US).split("|")
        for (name in ApkExtractionPolicy.NATIVE_NAMED) {
            val low = name.lowercase(Locale.US)
            assertTrue("NATIVE_GREP must catch named target $name",
                tokens.any { low.contains(it) })
        }
    }

    @Test
    fun `a small native file within budget is admitted`() {
        assertEquals(Skip.NONE, ApkExtractionPolicy.admitNative(30 * 1024, 20L * 1024 * 1024, 0))
    }

    @Test
    fun `the multi-MB Kanzi renderer is skipped by the native per-file cap`() {
        assertEquals(Skip.TOO_BIG, ApkExtractionPolicy.admitNative(8L * 1024 * 1024, 0L, 0))
    }

    @Test
    fun `native draws from the reserve even when APKs filled their budget`() {
        // APKs spent their full budget; native still has NATIVE_RESERVE room.
        val afterApks = ApkExtractionPolicy.APK_BUDGET
        assertEquals(Skip.NONE, ApkExtractionPolicy.admitNative(1L * 1024 * 1024, afterApks, 0))
    }

    @Test
    fun `native cannot overflow the whole-bundle ceiling`() {
        val nearFull = ApkExtractionPolicy.BUDGET_TOTAL - 10 * 1024
        assertEquals(Skip.OVER_BUDGET, ApkExtractionPolicy.admitNative(1L * 1024 * 1024, nearFull, 1))
    }

    @Test
    fun `native count backstop rejects beyond the native max`() {
        assertEquals(Skip.MAX_COUNT,
            ApkExtractionPolicy.admitNative(1024, 0L, ApkExtractionPolicy.NATIVE_MAX_COUNT))
    }
}

package com.byd.dashcast.util

import android.content.ContextWrapper
import com.byd.dashcast.report.BugReportCapture
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Where the sweeper looks, against where the writer writes.
 *
 * These two had drifted apart on exactly one platform family, and it is the family the whole
 * fallback exists for. On DL5.1 / Android 13 ROMs `getExternalFilesDir()` throws SecurityException;
 * `BugReportCapture.newFile` answers that by building the canonical path by hand and writing there
 * successfully, while `pruneOldFiles` answered the same throw with null and skipped external
 * storage altogether. Every report written on those cars was a report never pruned — the unbounded
 * growth the sweeper exists to prevent.
 *
 * No diff-shaped review can catch this: each half is correct on its own, and they live in different
 * files. So the agreement itself is what gets pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SweepRootsTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun throwingCtx() = object : ContextWrapper(ctx) {
        override fun getExternalFilesDir(type: String?): File? =
            throw SecurityException("callingPackage does not match UID")
    }

    @Test
    fun `the sweep covers the canonical path the writer falls back to`() {
        val canonical = BugReportCapture.canonicalExternalFilesDir(ctx)
        val roots = AppLogger.sweepRoots(throwingCtx()).map { it.absolutePath }

        assertTrue("the sweeper must look where a throwing ROM makes the writer write: " +
            "${canonical.absolutePath} not in $roots", roots.contains(canonical.absolutePath))
        assertTrue("and internal storage is still swept",
            roots.contains(ctx.filesDir.absolutePath))
    }

    /**
     * On a healthy ROM getExternalFilesDir() returns exactly the canonical path, so a naive
     * "just add it" would sweep the same directory twice — harmless for the deletions themselves,
     * but it double-counts the number this function's caller reports and re-lists the directory.
     */
    @Test
    fun `a healthy device does not get the same directory twice`() {
        val roots = AppLogger.sweepRoots(ctx)
        val paths = roots.map { it.absolutePath }
        assertEquals("duplicate roots: $paths", paths.size, paths.toSet().size)
    }

    @Test
    fun `internal storage is always swept, whatever external storage does`() {
        listOf(AppLogger.sweepRoots(ctx), AppLogger.sweepRoots(throwingCtx())).forEach { roots ->
            assertTrue("filesDir missing from $roots",
                roots.any { it.absolutePath == ctx.filesDir.absolutePath })
        }
    }

    /**
     * The prefix half of the same contract, already fixed once (51eac609) and pinned here beside
     * the directory half — a sweeper that looks in the right place for the wrong names is no
     * better than one that looks in the wrong place.
     */
    @Test
    fun `the report prefix the writer uses is one the sweeper prunes`() {
        assertTrue("PRUNED_PREFIXES must contain BugReportCapture.PREFIX",
            AppLogger.PRUNED_PREFIXES.contains(BugReportCapture.PREFIX))
    }
}

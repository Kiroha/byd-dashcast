package com.byd.dashcast.report

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * ReportStore — the ring and the space guard.
 *
 * Unlike the PRE-0 seismographs this is a specification, not a pin: ReportStore is new code and
 * these are the rules it is meant to enforce. They matter because nothing else in the app bounds
 * artefact accumulation, and the log-rotation comment in AppLogger still documents a 1.08 GB
 * incident caused by exactly that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ReportStoreTest {

    private val ctx: android.content.Context get() = RuntimeEnvironment.getApplication()

    private fun artefact(name: String, sizeBytes: Int, ageMs: Long = 0): File {
        val f = File(ReportStore.dir(ctx), name)
        f.writeBytes(ByteArray(sizeBytes))
        if (ageMs > 0) f.setLastModified(System.currentTimeMillis() - ageMs)
        return f
    }

    @After
    fun clean() {
        ReportStore.dir(ctx).listFiles()?.forEach { it.delete() }
    }

    // ── directory ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `dir is a reports subdirectory of the external files dir and exists`() {
        val d = ReportStore.dir(ctx)
        assertEquals(ReportStore.DIR_NAME, d.name)
        assertTrue("dir() must create the directory", d.isDirectory)
        // Must sit under the app's external files dir, which file_paths.xml covers recursively —
        // otherwise FileProvider.getUriForFile throws and the share fallback silently dies.
        assertEquals(ctx.getExternalFilesDir(null), d.parentFile)
    }

    // ── space guard ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a zero or negative request always fits`() {
        assertTrue(ReportStore.hasRoomFor(ReportStore.dir(ctx), 0))
        assertTrue(ReportStore.hasRoomFor(ReportStore.dir(ctx), -1))
    }

    @Test
    fun `an absurd request does not fit`() {
        // Far beyond any plausible volume: the guard must say no rather than overflow.
        assertFalse(ReportStore.hasRoomFor(ReportStore.dir(ctx), Long.MAX_VALUE / 4))
    }

    @Test
    fun `usableBytes walks up to an existing ancestor rather than giving up`() {
        val ghost = File(ReportStore.dir(ctx), "does/not/exist/yet")
        assertTrue("must resolve through a non-existent path", ReportStore.usableBytes(ghost) > 0)
    }

    @Test
    fun `usableBytes refuses to answer from a shallow ancestor`() {
        // Writing this test found a real defect: an unbounded walk climbed to "/" and returned the
        // SYSTEM partition's free space for a path headed to emulated storage — a different mount.
        // The guard would then have approved a write on the wrong volume's numbers. A shallow
        // ancestor must read as "cannot tell", not as an answer.
        val nowhere = File("/nonexistent-volume-xyz/reports")
        assertEquals(-1, ReportStore.usableBytes(nowhere))
    }

    @Test
    fun `the guard fails OPEN when free space cannot be established`() {
        // Contract: refusing a diagnostic because a ROM will not report its storage would break the
        // feature on exactly the units that need diagnosing.
        val nowhere = File("/nonexistent-volume-xyz/reports")
        assertTrue(ReportStore.hasRoomFor(nowhere, 10L * 1024 * 1024 * 1024))
    }

    // ── ring ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `prune keeps the newest KEEP_FILES and drops the rest`() {
        repeat(6) { i -> artefact("byd_bugreport_$i.txt", 1024, ageMs = (6 - i) * 60_000L) }
        ReportStore.prune(ctx)
        val left = ReportStore.dir(ctx).listFiles()!!.map { it.name }.sorted()
        assertEquals(ReportStore.KEEP_FILES, left.size)
        // The survivors are the most recent, i.e. the highest indices.
        assertEquals(listOf("byd_bugreport_3.txt", "byd_bugreport_4.txt", "byd_bugreport_5.txt"), left)
    }

    @Test
    fun `prune drops a stale artefact even when it is the newest`() {
        artefact("old_but_newest.zip", 1024, ageMs = ReportStore.MAX_AGE_MS + 60_000L)
        ReportStore.prune(ctx)
        assertEquals(0, ReportStore.dir(ctx).listFiles()!!.size)
    }

    @Test
    fun `prune reports the bytes it reclaimed`() {
        repeat(5) { i -> artefact("byd_bugreport_$i.txt", 2048, ageMs = (5 - i) * 60_000L) }
        val freed = ReportStore.prune(ctx)
        assertEquals("two files of 2048 bytes should be reclaimed", 4096L, freed)
    }

    @Test
    fun `prune on an empty directory is a no-op and does not throw`() {
        assertEquals(0L, ReportStore.prune(ctx))
    }

    @Test
    fun `prune leaves a directory already within bounds untouched`() {
        repeat(ReportStore.KEEP_FILES) { i -> artefact("keep_$i.txt", 512, ageMs = i * 1000L) }
        assertEquals(0L, ReportStore.prune(ctx))
        assertEquals(ReportStore.KEEP_FILES, ReportStore.dir(ctx).listFiles()!!.size)
    }
}

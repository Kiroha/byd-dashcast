package com.byd.dashcast.util

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
 * D1 — the voice-leftover cleanup added to [AppLogger.pruneOldFiles].
 *
 * This is the only code in the D1 removal that DELETES a directory tree on a user's device, and
 * it had never run anywhere when it was written. A wrong root, a missing isDirectory guard or a
 * path built by concatenation instead of File(parent, name) would take real data with it — the
 * app's own databases and shared preferences live under the same filesDir.
 *
 * So the test is written for the failure, not for the feature. It asserts what MUST SURVIVE at
 * least as hard as what must go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceLeftoverCleanupTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun seed(dir: File, vararg names: String): List<File> {
        dir.mkdirs()
        return names.map { n -> File(dir, n).apply { writeText("x") } }
    }

    @Test
    fun `removes the three voice directories and nothing else under filesDir`() {
        val extBase = ctx.getExternalFilesDir(null)!!

        // The three roots the cleanup targets, with a nested level so the recursion is exercised.
        val voskExt = File(extBase, "vosk")
        seed(voskExt, "model.zip", "am.bin")
        seed(File(voskExt, "vosk-model-small-fr"), "final.mdl")
        val voskInt = File(ctx.filesDir, "vosk")
        seed(voskInt, "model.zip")
        val voiceLibs = File(ctx.filesDir, "voice_libs")
        seed(voiceLibs, "libvosk.so", "libonnxruntime.so", ".version")

        // Neighbours that must be untouched. These are the ones a bad path would eat.
        val prefs = seed(File(ctx.filesDir, "shared_prefs"), "byd_app_prefs.xml")
        val db = seed(File(ctx.filesDir, "databases"), "app.db")
        val reports = seed(File(extBase, "reports"), "byd_report_20260101_000000.txt")
        val loose = seed(ctx.filesDir, "some_app_file.dat")
        // A directory whose name merely CONTAINS a target name must survive: the cleanup matches
        // whole names, and this is what a prefix or `contains` test would get wrong.
        val lookalike = seed(File(ctx.filesDir, "vosk_backup"), "keep.me")

        AppLogger.pruneOldFiles(ctx, 5)

        assertFalse("external vosk/ must be gone", voskExt.exists())
        assertFalse("internal vosk/ must be gone", voskInt.exists())
        assertFalse("voice_libs/ must be gone", voiceLibs.exists())

        (prefs + db + reports + loose + lookalike).forEach {
            assertTrue("${it.path} must survive the voice cleanup", it.exists())
        }
    }

    @Test
    fun `does not create the directories it is meant to remove`() {
        // getExternalFilesDir("vosk") would materialise the directory as a side effect. On a
        // device that never ran the voice PoC the sweep must not manufacture the very thing the
        // next sweep would delete.
        //
        // The sweep DOES create one directory, and that is pre-existing behaviour, not D1's:
        // ReportStore.prune() materialises reports/ (commit e9a6c350). Pinned here by name so
        // that if the set of directories this sweep creates ever grows, this test says so.
        val extBase = ctx.getExternalFilesDir(null)!!
        val extBefore = extBase.list()?.toSet() ?: emptySet()
        val intBefore = ctx.filesDir.list()?.toSet() ?: emptySet()

        AppLogger.pruneOldFiles(ctx, 5)

        val extCreated = (extBase.list()?.toSet() ?: emptySet()) - extBefore
        val intCreated = (ctx.filesDir.list()?.toSet() ?: emptySet()) - intBefore
        assertEquals("only reports/ may appear under the external files dir", setOf("reports"), extCreated)
        assertEquals("nothing may appear under filesDir", emptySet<String>(), intCreated)

        assertFalse(File(extBase, "vosk").exists())
        assertFalse(File(ctx.filesDir, "vosk").exists())
        assertFalse(File(ctx.filesDir, "voice_libs").exists())
    }

    @Test
    fun `survives a null context and an empty voice directory`() {
        // pruneOldFiles is called from housekeeping paths that can hand it a null context, and an
        // empty leftover directory is the common case for someone who started a download once.
        File(ctx.filesDir, "voice_libs").mkdirs()

        AppLogger.pruneOldFiles(null, 5)          // must not throw
        AppLogger.pruneOldFiles(ctx, 5)

        assertFalse(File(ctx.filesDir, "voice_libs").exists())
    }
}

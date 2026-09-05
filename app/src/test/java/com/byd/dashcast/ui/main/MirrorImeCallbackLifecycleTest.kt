package com.byd.dashcast.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The post-touch IME probe is a `postDelayed` that outlives the gesture, so every path that
 * tears the mirror down has to cancel it — and `removeCallbacks` matches by IDENTITY, which is
 * why the Runnable must be one field and never an allocation at the call site.
 *
 * This is structural coupling on the SOURCE on purpose: the defect is an absent cancel, and no
 * behavioural test can observe a callback that was never scheduled.
 */
class MirrorImeCallbackLifecycleTest {

    @Test
    fun `post-touch IME probe is owned and cancelled by mirror lifecycle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/MirrorCoordinator.kt").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/MirrorCoordinator.kt").readText()
        val stop = source.substringAfter("fun stopMirror()")
            .substringBefore("private fun stopMirrorForRestart()")
        val restart = source.substringAfter("private fun stopMirrorForRestart()")
            .substringBefore("private fun stopNormalMirror()")
        val destroy = source.substringAfter("fun destroy()")
            .substringBefore("private fun setPlaceholderVisible")
        val recreate = source.substringAfter("fun recreateSurfaceAndRestart()")
            .substringBefore("fun getMirrorSurface()")

        // One Runnable, held in a field — removeCallbacks matches by identity.
        assertTrue(source.contains("private val mPostTouchImeCheck: Runnable"))
        // ...and never re-allocated where it is scheduled, in either Kotlin spelling.
        assertFalse(source.contains("postDelayed(Runnable"))
        assertFalse(source.contains("postDelayed({"))

        assertTrue(stop.contains("cancelPostTouchImeCheck()"))
        assertTrue(restart.contains("cancelPostTouchImeCheck()"))
        assertTrue(destroy.contains("mDestroyed = true"))
        assertTrue(destroy.contains("cancelPostTouchImeCheck()"))
        assertTrue(destroy.contains("setOnTouchListener(null)"))
        assertTrue(recreate.trimStart().startsWith("{\n        if (mDestroyed) return"))
        // The probe itself re-checks the state it was scheduled under, because it can still be
        // in flight when the mirror is hidden between the lift and the 350 ms deadline.
        assertTrue(source.contains(
            "if (mDestroyed || mFrameMirror.visibility != View.VISIBLE) return@Runnable"))
    }
}

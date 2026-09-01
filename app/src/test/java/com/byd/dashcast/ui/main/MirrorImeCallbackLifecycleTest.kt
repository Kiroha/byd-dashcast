package com.byd.dashcast.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MirrorImeCallbackLifecycleTest {

    @Test
    fun `post-touch IME probe is owned and cancelled by mirror lifecycle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it,
                "app/src/main/java/com/byd/dashcast/ui/main/MirrorCoordinator.java").isFile }
        assertTrue("could not locate the repo root", root != null)
        val source = File(root,
            "app/src/main/java/com/byd/dashcast/ui/main/MirrorCoordinator.java").readText()
        val stop = source.substringAfter("public void stopMirror()")
            .substringBefore("private void stopMirrorForRestart()")
        val restart = source.substringAfter("private void stopMirrorForRestart()")
            .substringBefore("private void stopNormalMirror()")
        val destroy = source.substringAfter("public void destroy()")
            .substringBefore("private void setPlaceholderVisible")
        val recreate = source.substringAfter("public void recreateSurfaceAndRestart()")
            .substringBefore("public Surface getMirrorSurface()")

        assertTrue(source.contains("private final Runnable     mPostTouchImeCheck"))
        assertFalse(source.contains("postDelayed(new Runnable()"))
        assertTrue(stop.contains("cancelPostTouchImeCheck()"))
        assertTrue(restart.contains("cancelPostTouchImeCheck()"))
        assertTrue(destroy.contains("mDestroyed = true"))
        assertTrue(destroy.contains("cancelPostTouchImeCheck()"))
        assertTrue(destroy.contains("setOnTouchListener(null)"))
        assertTrue(recreate.trimStart().startsWith("{\n        if (mDestroyed) return"))
        assertTrue(source.contains(
            "if (mDestroyed || mFrameMirror.getVisibility() != View.VISIBLE) return"))
    }
}
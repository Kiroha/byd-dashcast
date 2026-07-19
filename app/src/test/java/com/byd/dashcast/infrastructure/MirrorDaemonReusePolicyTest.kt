package com.byd.dashcast.infrastructure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorDaemonReusePolicyTest {
    @Test
    fun `live daemon from current build is reused`() {
        assertTrue(MirrorDaemonReusePolicy.shouldReuse(true, 15234, "15234:587", 587))
    }

    @Test
    fun `daemon from previous build is restarted after OTA`() {
        assertFalse(MirrorDaemonReusePolicy.shouldReuse(true, 15234, "15234:586", 587))
    }

    @Test
    fun `daemon without version marker is restarted`() {
        assertFalse(MirrorDaemonReusePolicy.shouldReuse(true, 15234, "", 587))
    }

    @Test
    fun `dead binder is never reused`() {
        assertFalse(MirrorDaemonReusePolicy.shouldReuse(false, 15234, "15234:587", 587))
    }

    @Test
    fun `marker belonging to old pid is rejected`() {
        assertFalse(MirrorDaemonReusePolicy.shouldReuse(true, 15234, "8111:587", 587))
    }

    @Test
    fun `single daemon pid is parsed from Android ps output`() {
        val ps = "shell        15234     1 4529000 122660 SyS_epoll_wait 0 S com.byd.dashcast.mirrordaemon"
        assertTrue(MirrorDaemonReusePolicy.singleProcessPid(ps) == 15234)
    }

    @Test
    fun `duplicate daemons force cleanup instead of reuse`() {
        val ps = "shell 15234 1 0 0 x 0 S com.byd.dashcast.mirrordaemon\n" +
            "shell 15235 1 0 0 x 0 S com.byd.dashcast.mirrordaemon"
        assertTrue(MirrorDaemonReusePolicy.singleProcessPid(ps) == -1)
    }
}
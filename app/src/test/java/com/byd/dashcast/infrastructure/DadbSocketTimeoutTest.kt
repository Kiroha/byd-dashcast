package com.byd.dashcast.infrastructure

import dadb.AdbConnectException
import dadb.Dadb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DadbSocketTimeoutTest {
    @Test
    fun `silent ADB peer cannot block a worker forever`() {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val peer = Thread({
                server.accept().use {
                    accepted.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }, "silent-adb-peer").apply { isDaemon = true }
            peer.start()

            val started = System.nanoTime()
            try {
                val error = assertThrows(AdbConnectException::class.java) {
                    Dadb.create("127.0.0.1", server.localPort, null, 500, 200, false).use {
                        it.shell("echo never-runs")
                    }
                }
                assertEquals(
                    AdbLocalClient.XPORT_UNRESPONSIVE,
                    AdbTransportFailure.classify(error)
                )
                assertTrue(accepted.await(1, TimeUnit.SECONDS))
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue("timeout took ${elapsedMs}ms", elapsedMs < 1_500)
            } finally {
                release.countDown()
                peer.join(1_000)
            }
        }
    }
}
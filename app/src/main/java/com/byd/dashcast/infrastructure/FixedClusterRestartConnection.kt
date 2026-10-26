package com.byd.dashcast.infrastructure

import java.util.concurrent.atomic.AtomicBoolean

/** The only admitted service is a fixed ADB OPEN, never caller-supplied shell text. */
internal class FixedClusterRestartConnection(private val open: (String) -> AutoCloseable,
    private val closeConnection: () -> Unit) : ClusterRestartConnection {
    private val sent = AtomicBoolean(false)
    override fun dispatch() {
        check(sent.compareAndSet(false, true)) { "Restart service cannot be replayed" }
        open("reboot:fission-all").close()
    }
    override fun close() = closeConnection()
}

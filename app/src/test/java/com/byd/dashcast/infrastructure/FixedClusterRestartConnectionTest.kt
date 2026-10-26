package com.byd.dashcast.infrastructure

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class FixedClusterRestartConnectionTest {
    @Test fun `production dispatcher opens fixed host service once and closes both resources`() {
        val events = mutableListOf<String>()
        val connection = FixedClusterRestartConnection({ service -> events += service; Closeable { events += "stream-close" } }, { events += "connection-close" })
        connection.dispatch()
        assertThrows(IllegalStateException::class.java) { connection.dispatch() }
        connection.close()
        assertEquals(listOf("reboot:fission-all", "stream-close", "connection-close"), events)
    }
    @Test fun `open and stream close failures never allow a replay`() {
        for (openFailure in listOf(false, true)) {
            var opens = 0
            val connection = FixedClusterRestartConnection({
                opens++
                if (openFailure) throw IOException("disconnect before OPEN reply")
                Closeable { throw IOException("disconnect after OPEN") }
            }, {})
            assertThrows(IOException::class.java) { connection.dispatch() }
            assertThrows(IllegalStateException::class.java) { connection.dispatch() }
            assertEquals(1, opens)
        }
    }
}

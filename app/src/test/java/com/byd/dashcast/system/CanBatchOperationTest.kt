package com.byd.dashcast.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanBatchOperationTest {

    @Test
    fun operationsExecuteInDeclaredOrder() {
        val events = mutableListOf<String>()
        val writer = RecordingWriter(events)
        val operations = listOf(
            CanBatchOperation.naviStatus(2),
            CanBatchOperation.instrumentInt(10, 20),
            CanBatchOperation.instrumentBytes(11, byteArrayOf(1, 2)),
            CanBatchOperation.settingInt(12, 30)
        )

        operations.forEach { it.execute(writer) }

        assertEquals(
            listOf("navi:2", "int:10=20", "bytes:11=1,2", "setting:12=30"),
            events
        )
    }

    @Test
    fun bytePayloadIsDefensivelyCopied() {
        val source = byteArrayOf(1, 2)
        val operation = CanBatchOperation.instrumentBytes(11, source)
        source[0] = 9
        val events = mutableListOf<String>()

        operation.execute(RecordingWriter(events))

        assertEquals(listOf("bytes:11=1,2"), events)
    }

    @Test
    fun navigationActivationKeepsLegacyOrder() {
        val events = mutableListOf<String>()

        CanNavigationBatches.navigationState(true)
            .forEach { it.execute(RecordingWriter(events)) }

        assertEquals(listOf("navi:2", "setting:1276174357=3"), events)
    }

    @Test
    fun navigationStopKeepsStatusThenSevenLegacyClears() {
        val events = mutableListOf<String>()

        CanNavigationBatches.navigationState(false)
            .forEach { it.execute(RecordingWriter(events)) }

        assertEquals(
            listOf(
                "navi:4",
                "int:1139806224=0",
                "int:1139806232=-1",
                "bytes:1140461576=",
                "int:1139810344=-1",
                "int:1139810320=0",
                "int:1139810328=0",
                "int:1139810334=0"
            ),
            events
        )
    }

    @Test
    fun nativeRejectionStopsTheBatchAndIsNotCountedAsApplied() {
        val events = mutableListOf<String>()
        val writer = RecordingWriter(events, ArrayDeque(listOf(0, -7, 0)))
        val operations = listOf(
            CanBatchOperation.naviStatus(2),
            CanBatchOperation.instrumentInt(10, 20),
            CanBatchOperation.settingInt(12, 30)
        )

        val applied = CanBatchOperation.executeAcceptedPrefix(operations, writer)

        assertEquals(1, applied)
        assertEquals(listOf("navi:2", "int:10=20"), events)
    }

    @Test
    fun truthfulBatchingIsNegotiatedAsProtocol24() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/com/byd/dashcast").isDirectory }
        assertTrue("could not locate the repo root", root != null)
        val controller = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/system/CanBusController.java"
        ).readText()
        val daemon = java.io.File(
            root,
            "app/src/main/java/com/byd/dashcast/proxy/daemon/ProxyDaemonMain.java"
        ).readText()

        assertTrue(controller.contains("supportsProtocol(24)"))
        assertTrue(daemon.contains("PROTOCOL_VERSION = \"24\""))
    }

    private class RecordingWriter(
        private val events: MutableList<String>,
        private val results: ArrayDeque<Int> = ArrayDeque(),
    ) :
        CanBatchOperation.Writer {
        private fun result(): Int = results.removeFirstOrNull() ?: 0

        override fun setNaviStatus(status: Int): Int {
            events += "navi:$status"
            return result()
        }
        override fun setInstrumentInt(featureId: Int, value: Int): Int {
            events += "int:$featureId=$value"
            return result()
        }
        override fun setInstrumentBytes(featureId: Int, bytes: ByteArray): Int {
            events += "bytes:$featureId=${bytes.joinToString(",")}"
            return result()
        }
        override fun setSettingInt(featureId: Int, value: Int): Int {
            events += "setting:$featureId=$value"
            return result()
        }
    }
}

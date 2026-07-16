package com.byd.dashcast.system

import org.junit.Assert.assertEquals
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
    fun navigationStopKeepsStatusThenEightLegacyClears() {
        val events = mutableListOf<String>()

        CanNavigationBatches.navigationState(false)
            .forEach { it.execute(RecordingWriter(events)) }

        assertEquals(
            listOf(
                "navi:4",
                "int:1139806224=0",
                "int:1139806256=0",
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

    private class RecordingWriter(private val events: MutableList<String>) :
        CanBatchOperation.Writer {
        override fun setNaviStatus(status: Int) { events += "navi:$status" }
        override fun setInstrumentInt(featureId: Int, value: Int) {
            events += "int:$featureId=$value"
        }
        override fun setInstrumentBytes(featureId: Int, bytes: ByteArray) {
            events += "bytes:$featureId=${bytes.joinToString(",")}" 
        }
        override fun setSettingInt(featureId: Int, value: Int) {
            events += "setting:$featureId=$value"
        }
    }
}

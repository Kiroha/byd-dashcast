package com.byd.dashcast.clusterlive

import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClusterThemeProtocolTest {
    private val name = ClusterThemePolicy.PREFIX + "0123456789abcdef0123456789abcdef.rcc"
    private fun response(source: Int, path: String, version: String = ClusterThemeProtocol.UI_VERSION): ByteArray {
        val b = FlatBufferBuilder(256)
        val ui = b.createString(version); val p = b.createString(path); val resource = b.createString("default")
        b.startTable(13); b.addInt(0, 1001, 0); b.addInt(1, 4, 0); b.addInt(2, 1, 0); b.addInt(5, source, 0)
        b.addOffset(7, ui, 0); b.addOffset(9, p, 0); b.addOffset(11, resource, 0)
        b.finish(b.endTable()); return b.sizedByteArray()
    }
    @Test fun `request binary slots match recovered native accessor ABI`() {
        for ((bytes, cmd, sub, mode) in listOf(
            listOf(ClusterThemeProtocol.query(), 1, 4, 0),
            listOf(ClusterThemeProtocol.restoreDefault(), 5, 0, 1),
            listOf(ClusterThemeProtocol.load(name), 5, 0, 0))) {
            val b = ByteBuffer.wrap(bytes as ByteArray).order(ByteOrder.LITTLE_ENDIAN)
            val root = b.getInt(0); val vt = root - b.getInt(root); val len = b.getShort(vt).toInt()
            fun value(slot: Int): Int {
                val at = 4 + slot * 2
                if (at >= len) return 0
                val off = b.getShort(vt + at).toInt(); return if (off == 0) 0 else b.getInt(root + off)
            }
            assertEquals(cmd, value(0)); assertEquals(sub, value(1)); assertEquals(mode, value(2))
            if (cmd == 5 && mode == 0) assertEquals(name, ClusterThemeProtocol.decode(bytes).strings[0])
        }
    }
    @Test fun `baseline refuses custom source wrong version and ambiguous path`() {
        assertTrue(ClusterThemeProtocol.decode(response(1, "default")).isDefault())
        assertFalse(ClusterThemeProtocol.decode(response(0, "default")).isDefault())
        assertFalse(ClusterThemeProtocol.decode(response(1, "custom.rcc")).isDefault())
        assertFalse(ClusterThemeProtocol.decode(response(1, "default", "other")).isDefault())
        assertTrue(ClusterThemeProtocol.decode(response(0, ClusterThemePolicy.HOST_DIRECTORY + name)).selected(name))
        assertFalse(ClusterThemeProtocol.decode(response(0, ClusterThemePolicy.HOST_DIRECTORY + name)).selected(name.replace("0123", "4567")))
    }
    @Test fun `reported IVI path is independent of native translation source as in the 1551 report`() {
        for (source in listOf(0, 1)) {
            val decoded = ClusterThemeProtocol.decode(response(source, ClusterThemePolicy.HOST_DIRECTORY + name))
            assertTrue(decoded.selected(name)); assertFalse(decoded.isDefault())
        }
        for (source in listOf(-1, 2, Int.MAX_VALUE))
            assertFalse(ClusterThemeProtocol.decode(response(source, ClusterThemePolicy.HOST_DIRECTORY + name)).selected(name))
        assertFalse(ClusterThemeProtocol.decode(response(1, ClusterThemePolicy.HOST_DIRECTORY + name, "other")).selected(name))
        assertFalse(ClusterThemeProtocol.decode(response(1, ClusterThemePolicy.ANDROID_DIRECTORY + name)).selected(name))
        assertFalse(ClusterThemeProtocol.decode(response(1, ClusterThemePolicy.HOST_DIRECTORY + "foreign.rcc")).selected("foreign.rcc"))
    }
    @Test fun `only owned bounded filenames can produce a load`() {
        for (bad in listOf("", "../$name", "/$name", name + ".rcc", name.uppercase(), "user.rcc")) {
            assertFalse(ClusterThemePolicy.ownedName(bad))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeProtocol.load(bad) }
        }
        assertTrue(ClusterThemePolicy.originalHashes.containsKey("/system/lib64/libwindowsurface_android.so"))
    }
    @Test fun `malformed unsolicited FlatBuffers fail closed`() {
        for (bad in listOf(ByteArray(7), ByteArray(70000), ByteArray(12) { -1 }, ByteArray(16)))
            assertThrows(IllegalArgumentException::class.java) { ClusterThemeProtocol.decode(bad) }
        val valid = response(1, "default")
        for (size in listOf(8, 12, valid.size / 2)) {
            assertThrows(RuntimeException::class.java) { ClusterThemeProtocol.decode(valid.copyOf(size)) }
        }
    }
}

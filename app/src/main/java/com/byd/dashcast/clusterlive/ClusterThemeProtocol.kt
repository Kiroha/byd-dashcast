package com.byd.dashcast.clusterlive

import com.google.flatbuffers.FlatBufferBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Closed subset of SX326 PadToClusterReq / ClusterToPadRes, type 8. No arbitrary commands.
 * Slots are bound to libBydCluster 3197abee…: cmd/sub 4/6, ints 8..16, strings 18..26.
 */
object ClusterThemeProtocol {
    const val UI_VERSION = "Di3F_DynastyOceanTranslate_V0001"
    fun query(): ByteArray = request(1, 4, 0, "")
    fun restoreDefault(): ByteArray = request(5, 0, 1, "")
    fun load(filename: String): ByteArray {
        require(ClusterThemePolicy.ownedName(filename))
        return request(5, 0, 0, filename)
    }
    private fun request(cmd: Int, sub: Int, mode: Int, name: String): ByteArray {
        val b = FlatBufferBuilder(256)
        val str = if (name.isEmpty()) 0 else b.createString(name)
        b.startTable(13)
        b.addInt(0, cmd, 0); b.addInt(1, sub, 0); b.addInt(2, mode, 0)
        if (str != 0) b.addOffset(7, str, 0)
        b.finish(b.endTable())
        return b.sizedByteArray()
    }

    data class Response(val cmd: Int, val sub: Int, val ints: List<Int>, val strings: List<String>) {
        fun isDefault(): Boolean = cmd == 1001 && sub == 4 && ints[0] == 1 &&
            ints[3] == 1 && strings[0] == UI_VERSION && strings[2] == "default" && strings[4] == "default"
        // getTranslateSource reports the native RCC field independently of the IVI selection path.
        fun selected(name: String): Boolean = ClusterThemePolicy.ownedName(name) && cmd == 1001 && sub == 4 && ints[0] == 1 &&
            ints[3] in 0..1 && strings[0] == UI_VERSION && strings[2] == ClusterThemePolicy.HOST_DIRECTORY + name
        fun summary(): String = "cmd=$cmd sub=$sub ints=$ints strings=$strings"
    }

    /** Validate every offset before accessing an unsolicited OEM message. */
    fun decode(bytes: ByteArray): Response {
        require(bytes.size in 8..65536) { "Invalid response size" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun span(p: Int, n: Int) { require(p >= 0 && n >= 0 && p.toLong() + n <= bytes.size) }
        fun int(p: Int): Int { span(p, 4); return b.getInt(p) }
        fun ushort(p: Int): Int { span(p, 2); return b.getShort(p).toInt() and 65535 }
        val root = int(0); span(root, 4)
        val vt = root.toLong() - int(root); require(vt in 0..Int.MAX_VALUE.toLong())
        val v = vt.toInt(); val len = ushort(v); val obj = ushort(v + 2)
        require(len in 4..256 && len % 2 == 0 && obj >= 4); span(v, len); span(root, obj)
        fun field(slot: Int): Int {
            val at = 4 + slot * 2
            if (at + 2 > len) return 0
            val off = ushort(v + at)
            if (off == 0) return 0
            require(off >= 4 && off + 4 <= obj); return root + off
        }
        fun scalar(slot: Int): Int = field(slot).let { if (it == 0) 0 else int(it) }
        fun string(slot: Int): String {
            val f = field(slot); if (f == 0) return ""
            val rel = int(f); require(rel >= 4)
            val p = f.toLong() + rel; require(p <= Int.MAX_VALUE)
            val n = int(p.toInt()); require(n in 0..4096)
            span(p.toInt() + 4, n + 1); require(bytes[p.toInt() + 4 + n] == 0.toByte())
            return String(bytes, p.toInt() + 4, n, Charsets.UTF_8)
        }
        return Response(scalar(0), scalar(1), (2..6).map(::scalar), (7..11).map(::string))
    }
}

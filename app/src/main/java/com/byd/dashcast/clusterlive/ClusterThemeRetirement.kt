package com.byd.dashcast.clusterlive

import org.json.JSONObject

/** A durable, explicit removal from OEM lookup; never an OEM restart or a recovery receipt. */
internal data class ClusterThemeRetirement(val directory: String, val device: Long, val inode: Long, val moved: Boolean) {
    fun json() = JSONObject().put("directory", directory).put("device", device).put("inode", inode).put("moved", moved)
    interface Files {
        fun sync()
        fun originalAbsent(): Boolean
        fun targetAbsent(): Boolean
        fun verifyOriginal()
        fun verifyTarget()
        fun rename()
    }
    fun finish(intentDurable: Boolean, files: Files, persist: (ClusterThemeRetirement) -> Unit): ClusterThemeRetirement {
        check(!moved) { "Carrier already retired; use Restore to verify BYD" }
        files.sync()
        if (!intentDurable) files.verifyOriginal()
        if (!files.originalAbsent()) {
            files.verifyOriginal(); check(files.targetAbsent()) { "Retirement target exists; no overwrite" }
            persist(this) // Every rename needs a fresh successful fsync, even after an in-memory failed checkpoint.
            files.verifyOriginal(); check(files.targetAbsent()) { "Retirement target changed during journal persistence" }
            files.rename()
        }
        check(files.originalAbsent()) { "Original lookup name still exists; retirement unconfirmed" }
        files.verifyTarget(); files.sync()
        return copy(moved = true).also(persist)
    }
    companion object {
        fun parse(j: JSONObject): ClusterThemeRetirement {
            require(j.get("directory") is String && j.getString("directory").matches(Regex("\\.dashcast-retired-[0-9a-f]{32}")))
            for (key in listOf("device", "inode")) require((j.get(key) is Int || j.get(key) is Long) && j.getLong(key) >= 0)
            require(j.get("moved") is Boolean)
            return ClusterThemeRetirement(j.getString("directory"), j.getLong("device"), j.getLong("inode"), j.getBoolean("moved"))
        }
        fun eligible(name: String, receipt: ClusterThemeProtocol.Response?, responses: List<ClusterThemeProtocol.Response>,
            native: Int?, attempted: Boolean): Boolean {
            val acks = responses.filter { it.cmd == 1005 && it.sub == 0 }
            val errors = acks.filter { it.ints[0] == 3 }
            return attempted && native == 0 && receipt?.selected(name) == true && receipt.ints[3] == 0 && errors.isNotEmpty() &&
                errors.all { it.ints == listOf(3, 0, 0x2330, 0, 0) && it.strings.all(String::isEmpty) } && acks.none { it.ints[0] == 2 }
        }
    }
}

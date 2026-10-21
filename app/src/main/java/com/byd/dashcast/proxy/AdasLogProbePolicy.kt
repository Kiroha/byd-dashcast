package com.byd.dashcast.proxy

/** SX326 firmware evidence only; these paths need not exist or be readable on another ROM. */
internal object AdasLogProbePolicy {
    val roots = listOf("/data/logs/host/logs", "/data/logs/host/clusterlogs", "/data/logs/host/spilogs")
    const val MAX_ENTRIES = 128
    const val MAX_FILES = 6
    const val TAIL_BYTES = 256 * 1024
    const val MAX_MATCHES = 64
    const val MAX_RESPONSE = 24 * 1024
    const val IO_BUDGET_MS = 5_000L
    const val CAPTURE_BUDGET_MS = 8_000L
    private val fileName = Regex("aplog(?:\\.[0-9]{1,2})?")
    private val callback = Regex("CLUSTER_LOG\\s*:?\\s*(\\[callback(Int|Real)] id=(\\d{3})\\(([A-Za-z0-9]{1,40})\\), value=(-?[0-9]{1,10}(?:\\.[0-9]{1,6})?))\\s*$")
    private val groups = listOf("leftFrontTarget", "rightFrontTarget", "leftRearTarget", "rightRearTarget", "frontTargetOne", "frontTargetTwo", "rearTarget")
    fun allowedName(name: String): Boolean = fileName.matches(name) &&
        (name == "aplog" || name.substringAfter('.').toIntOrNull() in 1..10)

    /** Return only the attested callback payload; never retain unrelated log text or apply raw decoders. */
    fun targetLine(line: String): String? {
        if (line.length > 1024) return null
        val match = callback.find(line) ?: return null
        val id = match.groupValues[3].toInt()
        if (id !in 499..519) return null
        val offset = (id - 499) % 3
        val name = groups[(id - 499) / 3] + listOf("State", "PosX", "PosY")[offset]
        if (match.groupValues[4] != name || match.groupValues[2] != if (offset == 0) "Int" else "Real") return null
        val raw = match.groupValues[5]
        if (offset == 0 && ('.' in raw || raw.toIntOrNull() !in 0..4)) return null
        if (offset != 0 && raw.substringAfter('.', "").length != 6) return null
        if (raw.toDoubleOrNull()?.isFinite() != true) return null
        return match.groupValues[1]
    }
}

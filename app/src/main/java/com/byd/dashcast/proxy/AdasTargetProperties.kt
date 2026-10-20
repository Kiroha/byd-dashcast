package com.byd.dashcast.proxy

/** SX326 firmware cache candidates, not CAN IDs or proof that Android exposes these signals. */
internal object AdasTargetProperties {
    const val DEVICE = 1038
    const val LKS_MODE = 0x3160000e
    enum class Variant(val label: String, val base: Int, val stateOffset: Int, val yOffset: Int) {
        NEW("0x295 decoder hypothesis", 0x29500000, 0x1c, 0x30),
        OLD("0x374 decoder hypothesis", 0x37400000, 0x34, 0x18)
    }
    data class Group(val label: String, val offset: Int)
    val groups = listOf(Group("Left front", 0x1000), Group("Front 1", 0x2000),
        Group("Right front", 0x3000), Group("Front 2", 0x4000))
    fun keys(variant: Variant, group: Group): List<Int> {
        require(group in groups)
        val base = variant.base + group.offset
        return listOf(base, base + variant.stateOffset, base + 0x10, base + variant.yOffset)
    }
    val allowed: List<Int> = Variant.entries.flatMap { variant -> groups.flatMap { keys(variant, it) } } + LKS_MODE
    fun requireAllowed(property: Int) = require(property in allowed) { "Outside the closed ADAS diagnostic allowlist" }
    fun hex(property: Int): String = "0x" + property.toUInt().toString(16).padStart(8, '0')
    fun label(property: Int): String {
        if (property == LKS_MODE) return "LKS mode"
        for (variant in Variant.entries) for (group in groups) {
            val index = keys(variant, group).indexOf(property)
            if (index >= 0) return "${variant.name} / ${group.label} / ${listOf("availability", "type", "X", "Y")[index]}"
        }
        error("Not an allowed property")
    }
}

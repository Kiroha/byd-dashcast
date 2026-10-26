package com.byd.dashcast.clusterlive

/** Fresh proof surrounds the complete lookup inventory; failed enumeration is never empty. */
internal object ClusterRestartFiles {
    interface Storage {
        fun originalAbsent(): Boolean
        fun verifyPreserved()
        fun entries(): Array<String>?
        fun verifyDirectory()
    }
    fun verify(storage: Storage) {
        storage.verifyDirectory()
        check(storage.originalAbsent()) { "Original RCC lookup name reappeared" }
        storage.verifyPreserved()
        val entries = storage.entries() ?: error("Cannot enumerate the RCC lookup directory")
        check(entries.size <= 4096) { "RCC lookup inventory exceeds its bound" }
        check(entries.none { it.startsWith(ClusterThemePolicy.PREFIX) }) { "A DashCast RCC lookup entry remains; no restart permitted" }
        check(storage.originalAbsent()) { "Original RCC lookup name reappeared during inventory" }
        storage.verifyDirectory()
    }
}

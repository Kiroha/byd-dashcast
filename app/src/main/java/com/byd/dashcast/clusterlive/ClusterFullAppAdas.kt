package com.byd.dashcast.clusterlive

/** Fixed diagnostic verbs. Callers never provide shell text or arbitrary SPI bytes. */
internal object ClusterFullAppAdas {
    const val JOURNAL = 0
    const val INITIALIZE = 1
    const val COMMAND = 2
    const val FRAMES = 3
    const val RECOVERY = 4
    const val RESTORED = 5
    val frames = listOf("26,F0,00,28,01,06", "43,20,00,00,01,01",
        "43,20,00,21,01,01", "23,50,00,08,01,00", "23,50,00,08,01,01")
    fun permits(action: Int, value: Int, count: Int, restoring: Boolean): Boolean = when (action) {
        JOURNAL -> !restoring && value == 0 && count == 0
        INITIALIZE, RECOVERY, RESTORED -> value == 0 && count == 0 &&
            (action == INITIALIZE || restoring)
        COMMAND -> count == 0 && if (restoring) value in setOf(12, 53) else value == 13
        FRAMES -> count in 1..10 && if (restoring) value == 4 else value in 0..3
        else -> false
    }
}

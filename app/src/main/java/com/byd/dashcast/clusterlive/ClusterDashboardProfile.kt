package com.byd.dashcast.clusterlive

/** Closed manual Diag profile for the observed 21f7735c / 02dfc7f5 cohort. */
internal object ClusterDashboardProfile {
    const val MIN_PROTOCOL = 43
    const val WIDTH = 1920
    const val HEIGHT = 720
    const val APP_X = 464
    const val APP_Y = 256
    const val APP_WIDTH = 752
    const val APP_HEIGHT = 320
    const val COMMAND_GAP_MS = 1200L
    const val BOOTSTRAP_GAP_MS = 3000L
    const val DISPLAY_WAIT_MS = 15000L
    const val DISPLAY_PROBE_GAP_MS = 1000L
    const val DISPLAY_PROBE_LIMIT = 6
    // A conservative host compatibility interval, not proof that the native
    // translation selector uses the theme-path handler's 15-second throttle.
    const val RCC_SWITCH_GAP_MS = 15500L
    val openCommands: List<Int> = listOf(31, 16, 35)
    val bootstrapCommands: List<Int> = listOf(16, 35)
    val bootstrapCloseCommands: List<Int> = listOf(18, 0)
    val closeCommands: List<Int> = listOf(18, 0, 31)
    fun permitsCommand(command: Int): Boolean = command in openCommands || command in closeCommands
    fun permitsGeometry(width: Int, height: Int, smallPanelLatched: Boolean): Boolean =
        width == WIDTH && height == HEIGHT && !smallPanelLatched
}

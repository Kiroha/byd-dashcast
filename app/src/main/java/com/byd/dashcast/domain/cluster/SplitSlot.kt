package com.byd.dashcast.domain.cluster

/**
 * Logical slot on the cluster display in fission (split-screen) mode.
 *
 * [ordinal] maps to the 0-based slot index used by the daemon's
 * ATTACH_SLOT / REUSE_SLOT verb protocol and by `FissionOrchestrator`.
 */
enum class SplitSlot {
    /** Full-width single-app projection. */
    FULL,
    /** Left half of the cluster display. */
    LEFT,
    /** Right half of the cluster display. */
    RIGHT;

    fun index(): Int = ordinal - 1 // FULL → -1 (unused), LEFT → 0, RIGHT → 1

    companion object {
        /** Sentinel: max slot index accepted by the daemon (inclusive). */
        const val MAX_INDEX = 1 // LEFT=0, RIGHT=1
    }
}

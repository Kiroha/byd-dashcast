package com.byd.dashcast.domain.cluster;

/**
 * Logical slot on the cluster display in fission (split-screen) mode.
 *
 * {@code ordinal()} maps to the 0-based slot index used by the daemon's
 * ATTACH_SLOT / REUSE_SLOT verb protocol and by {@code FissionOrchestrator}.
 */
public enum SplitSlot {
    /** Full-width single-app projection. */
    FULL,
    /** Left half of the cluster display. */
    LEFT,
    /** Right half of the cluster display. */
    RIGHT;

    /** Sentinel: max slot index accepted by the daemon (inclusive). */
    public static final int MAX_INDEX = 1; // LEFT=0, RIGHT=1

    public int index() {
        return ordinal() - 1; // FULL → -1 (unused), LEFT → 0, RIGHT → 1
    }
}

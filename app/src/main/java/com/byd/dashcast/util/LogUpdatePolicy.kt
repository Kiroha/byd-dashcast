package com.byd.dashcast.util

/** Pure policy for deciding whether a Journal update can append without a full replacement. */
internal object LogUpdatePolicy {
    fun canAppend(
        knownGeneration: Long,
        knownFirstSequence: Long,
        knownLastSequence: Long,
        currentGeneration: Long,
        currentFirstSequence: Long,
        currentLastSequence: Long
    ): Boolean =
        knownGeneration == currentGeneration &&
            knownFirstSequence == currentFirstSequence &&
            knownLastSequence in (currentFirstSequence - 1)..currentLastSequence
}
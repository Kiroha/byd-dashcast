package com.byd.dashcast.voice.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CircularPcmCopyTest {

    @Test
    fun copiesContiguousRange() {
        val destination = ShortArray(3)
        CircularPcmCopy.toLinear(shortArrayOf(0, 1, 2, 3, 4), 1, 3, destination)

        assertArrayEquals(shortArrayOf(1, 2, 3), destination)
    }

    @Test
    fun copiesWrappedRangeChronologically() {
        val destination = ShortArray(4)
        CircularPcmCopy.toLinear(shortArrayOf(0, 1, 2, 3, 4), 3, 4, destination)

        assertArrayEquals(shortArrayOf(3, 4, 0, 1), destination)
    }

    @Test
    fun copiesFullWindowFromNonZeroHead() {
        val destination = ShortArray(5)
        CircularPcmCopy.toLinear(shortArrayOf(0, 1, 2, 3, 4), 2, 5, destination)

        assertArrayEquals(shortArrayOf(2, 3, 4, 0, 1), destination)
    }

    @Test
    fun rejectsDestinationThatIsTooSmall() {
        assertThrows(IllegalArgumentException::class.java) {
            CircularPcmCopy.toLinear(shortArrayOf(0, 1, 2), 0, 3, ShortArray(2))
        }
    }

    @Test
    fun writesLinearFrameAcrossRingBoundary() {
        val ring = shortArrayOf(9, 9, 9, 9, 9)
        val next = CircularPcmCopy.fromLinear(shortArrayOf(1, 2, 3, 4), 4, ring, 3)

        assertArrayEquals(shortArrayOf(3, 4, 9, 1, 2), ring)
        org.junit.Assert.assertEquals(2, next)
    }

    @Test
    fun writesContiguousFrameAndReturnsNextHead() {
        val ring = ShortArray(6)
        val next = CircularPcmCopy.fromLinear(shortArrayOf(5, 6), 2, ring, 1)

        assertArrayEquals(shortArrayOf(0, 5, 6, 0, 0, 0), ring)
        org.junit.Assert.assertEquals(3, next)
    }
}
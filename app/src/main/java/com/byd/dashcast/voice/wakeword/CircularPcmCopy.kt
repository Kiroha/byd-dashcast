package com.byd.dashcast.voice.wakeword

/** Copies a chronological range from a circular PCM buffer into a linear work buffer. */
internal object CircularPcmCopy {
    fun toLinear(source: ShortArray, start: Int, length: Int, destination: ShortArray) {
        require(source.isNotEmpty()) { "source is empty" }
        require(start in source.indices) { "start out of range: $start" }
        require(length in 0..source.size) { "invalid length: $length" }
        require(destination.size >= length) { "destination too small" }
        if (length == 0) return

        val firstLength = minOf(length, source.size - start)
        System.arraycopy(source, start, destination, 0, firstLength)
        val wrappedLength = length - firstLength
        if (wrappedLength > 0) {
            System.arraycopy(source, 0, destination, firstLength, wrappedLength)
        }
    }

    fun fromLinear(source: ShortArray, length: Int, destination: ShortArray, start: Int): Int {
        require(destination.isNotEmpty()) { "destination is empty" }
        require(start in destination.indices) { "start out of range: $start" }
        require(length in 0..minOf(source.size, destination.size)) { "invalid length: $length" }
        if (length == 0) return start

        val firstLength = minOf(length, destination.size - start)
        System.arraycopy(source, 0, destination, start, firstLength)
        val wrappedLength = length - firstLength
        if (wrappedLength > 0) {
            System.arraycopy(source, firstLength, destination, 0, wrappedLength)
        }
        return (start + length) % destination.size
    }
}
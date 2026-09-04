package com.byd.dashcast.proxy.daemon

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Atomic ownership check for the recovery nonce published by one proxy daemon process. */
internal object ProxyInstanceMarker {

    @JvmStatic
    @Throws(IOException::class)
    fun ensureOwned(marker: File, token: String): Boolean {
        val expected = token.toByteArray(StandardCharsets.US_ASCII)
        return try {
            Files.write(marker.toPath(), expected,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            true
        } catch (existing: FileAlreadyExistsException) {
            hasExactContents(marker, expected)
        }
    }

    @Throws(IOException::class)
    private fun hasExactContents(marker: File, expected: ByteArray): Boolean {
        val actual = ByteArray(expected.size + 1)
        var count = 0
        FileInputStream(marker).use { input ->
            while (count < actual.size) {
                val read = input.read(actual, count, actual.size - count)
                if (read < 0) break
                count += read
            }
            if (count != expected.size || input.read() >= 0) return false
        }
        for (index in expected.indices) {
            if (actual[index] != expected[index]) return false
        }
        return true
    }
}

package com.byd.dashcast.clusterlive

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ClusterRestartFilesTest {
    private val name = ClusterThemePolicy.PREFIX + "a".repeat(32) + ".rcc"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture(run: (java.nio.file.Path, java.nio.file.Path, ClusterRestartFiles.Storage) -> Unit) {
        val root = Files.createTempDirectory("cluster-restart-test")
        try {
            val preserved = Files.createDirectory(root.resolve(".dashcast-retired-" + "b".repeat(32))).resolve(name)
            Files.write(preserved, "original payload".toByteArray())
            val expected = sha(Files.readAllBytes(preserved))
            val inode = Files.readAttributes(preserved, "unix:ino")["ino"]
            val identity = Files.readAttributes(root, "unix:ino")["ino"]
            val storage = object : ClusterRestartFiles.Storage {
                override fun originalAbsent() = !Files.exists(root.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                override fun verifyPreserved() {
                    check(Files.isRegularFile(preserved, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    check(Files.readAttributes(preserved, "unix:ino")["ino"] == inode)
                    check(sha(Files.readAllBytes(preserved)) == expected)
                }
                override fun entries() = root.toFile().list()
                override fun verifyDirectory() { check(Files.readAttributes(root, "unix:ino")["ino"] == identity) }
            }
            run(root, preserved, storage)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `real preserved payload and absent lookup passes without altering foreign resources`() = fixture { root, preserved, storage ->
        val foreign = root.resolve("BYD_factory.rcc"); Files.write(foreign, byteArrayOf(1, 2))
        ClusterRestartFiles.verify(storage)
        assertEquals("original payload", String(Files.readAllBytes(preserved)))
        assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(foreign))
    }
    @Test fun `source reappearance another owned name malformed owned name or symlink blocks`() {
        for (kind in 0..3) fixture { root, preserved, storage ->
            val target = root.resolve(when (kind) { 0 -> name; 1 -> ClusterThemePolicy.PREFIX + "c".repeat(32) + ".rcc"; else -> ClusterThemePolicy.PREFIX + "unexpected" })
            if (kind == 3) Files.createSymbolicLink(target, preserved) else Files.write(target, byteArrayOf(9))
            assertThrows(IllegalStateException::class.java) { ClusterRestartFiles.verify(storage) }
            assertTrue(Files.exists(preserved))
        }
    }
    @Test fun `preserved hash inode or symlink substitution refuses fresh proof`() {
        for (kind in 0..2) fixture { _, preserved, storage ->
            when (kind) {
                0 -> Files.write(preserved, "changed payload".toByteArray())
                1 -> { val replacement = preserved.resolveSibling("replacement"); Files.write(replacement, "original payload".toByteArray()); Files.move(replacement, preserved, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                else -> { Files.delete(preserved); Files.createSymbolicLink(preserved, preserved.resolveSibling("missing")) }
            }
            assertThrows(IllegalStateException::class.java) { ClusterRestartFiles.verify(storage) }
        }
    }
    @Test fun `failed enumeration directory replacement and source return during scan block`() = fixture { root, _, storage ->
        assertThrows(IllegalStateException::class.java) { ClusterRestartFiles.verify(object : ClusterRestartFiles.Storage by storage {
            override fun entries(): Array<String>? = null
        }) }
        var calls = 0
        assertThrows(IllegalStateException::class.java) { ClusterRestartFiles.verify(object : ClusterRestartFiles.Storage by storage {
            override fun verifyDirectory() { check(++calls == 1) { "generation changed" } }
        }) }
        assertThrows(IllegalStateException::class.java) { ClusterRestartFiles.verify(object : ClusterRestartFiles.Storage by storage {
            override fun entries(): Array<String> { Files.write(root.resolve(name), byteArrayOf(8)); return emptyArray() }
        }) }
    }
}

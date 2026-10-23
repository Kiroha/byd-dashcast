package com.byd.dashcast.proxy.daemon

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.byd.dashcast.clusterlive.ClusterThemePolicy
import com.byd.dashcast.clusterlive.ClusterThemeRetirement
import java.io.File
import java.util.UUID

/** Same-filesystem rename retains the inode and any Qt mapping. No overwrite, unlink or OEM call is intended. */
internal object ClusterThemeRetiredStorage {
    fun originalAbsent(name: String, parentIdentity: String): Boolean = ClusterThemeAndroidStorage.withDirectory { parent, directory ->
        check(ClusterThemePolicy.ownedName(name) && directory.identity == parentIdentity)
        absent(File(parent, name))
    }
    private fun absent(file: File): Boolean = try { Os.lstat(file.path); false } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) true else throw error
    }
    private fun sync(directory: String) {
        val fd = Os.open(directory, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
    private fun checkFile(file: File, device: Long, inode: Long, hash: (File) -> String, expectedHash: String) {
        val before = Os.lstat(file.path)
        check(OsConstants.S_ISREG(before.st_mode) && before.st_dev == device && before.st_ino == inode) { "Retirement carrier inode/type changed" }
        check(hash(file) == expectedHash) { "Retirement carrier hash changed" }
        val after = Os.lstat(file.path)
        check(OsConstants.S_ISREG(after.st_mode) && after.st_dev == device && after.st_ino == inode) { "Retirement carrier replaced" }
    }
    fun move(name: String, parentIdentity: String, device: Long, inode: Long, expectedHash: String,
        prior: ClusterThemeRetirement?, hash: (File) -> String, persist: (ClusterThemeRetirement) -> Unit): ClusterThemeRetirement =
        access(name, parentIdentity, prior, create = prior == null) { parent, target, state ->
            val original = File(parent, name); val destination = File(target, name)
            state.finish(prior != null, object : ClusterThemeRetirement.Files {
                override fun sync() { sync(target); sync(parent) }
                override fun originalAbsent() = absent(original)
                override fun targetAbsent() = absent(destination)
                override fun verifyOriginal() = checkFile(original, device, inode, hash, expectedHash)
                override fun verifyTarget() = checkFile(destination, device, inode, hash, expectedHash)
                override fun rename() {
                    // The last identity/absence checks and Android rename are not atomic against another writer.
                    Os.rename(original.path, destination.path)
                }
            }, persist)
        }

    fun verify(name: String, parentIdentity: String, device: Long, inode: Long, expectedHash: String,
        state: ClusterThemeRetirement, hash: (File) -> String) {
        check(state.moved) { "Retirement intent incomplete; explicit preparation required" }
        access(name, parentIdentity, state, false) { parent, target, _ ->
            check(absent(File(parent, name))) { "Original RCC lookup name reappeared" }
            checkFile(File(target, name), device, inode, hash, expectedHash)
            check(absent(File(parent, name))) { "Original RCC lookup name reappeared during verification" }
        }
    }
    private fun <T> access(name: String, parentIdentity: String, prior: ClusterThemeRetirement?, create: Boolean,
        action: (String, String, ClusterThemeRetirement) -> T): T {
        check(ClusterThemePolicy.ownedName(name))
        return ClusterThemeAndroidStorage.withDirectory { parent, directory ->
            check(directory.identity == parentIdentity) { "Retirement parent generation changed" }
            val dirname = prior?.directory ?: ".dashcast-retired-${UUID.randomUUID().toString().replace("-", "") }"
            val named = File(parent, dirname)
            if (create) Os.mkdir(named.path, 448) // Existing names always refuse; never claim an older directory.
            val fd = Os.open(named.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
            val pinned = try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }
            pinned.use {
                val st = Os.fstat(it.fileDescriptor)
                check(OsConstants.S_ISDIR(st.st_mode) && st.st_dev == Os.lstat(parent).st_dev)
                val state = prior ?: ClusterThemeRetirement(dirname, st.st_dev, st.st_ino, false)
                check(st.st_dev == state.device && st.st_ino == state.inode) { "Retirement directory generation changed" }
                fun current() {
                    val now = Os.lstat(named.path)
                    check(OsConstants.S_ISDIR(now.st_mode) && now.st_dev == state.device && now.st_ino == state.inode &&
                        ClusterThemeAndroidStorage.directory(false) == directory) { "Retirement named directory changed" }
                }
                current()
                val result = action(parent, "/proc/self/fd/${it.fd}/", state)
                current()
                result
            }
        }
    }
}

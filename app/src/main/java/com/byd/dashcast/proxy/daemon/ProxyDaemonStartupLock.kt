package com.byd.dashcast.proxy.daemon

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Serializes daemon ownership publication and never exposes a partial PID value. */
internal class ProxyDaemonStartupLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {

    @Throws(IOException::class)
    fun publishPid(target: File?, pid: String?) {
        val parent = target!!.parentFile
        val staging = File.createTempFile(".dashcast_proxy_pid_", ".tmp", parent)
        try {
            FileOutputStream(staging).use { output ->
                output.write(pid!!.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
                output.getFD().sync()
            }
            Files.move(staging.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging.toPath())
        }
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun tryAcquire(lockFile: File?): ProxyDaemonStartupLock? {
            val channel = FileChannel.open(lockFile!!.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock = channel.tryLock()
                if (lock == null) {
                    channel.close()
                    return null
                }
                return ProxyDaemonStartupLock(channel, lock)
            } catch (busy: OverlappingFileLockException) {
                channel.close()
                return null
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
    }
}

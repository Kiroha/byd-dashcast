package com.byd.dashcast.proxy.daemon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Serializes daemon ownership publication and never exposes a partial PID value. */
final class ProxyDaemonStartupLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ProxyDaemonStartupLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static ProxyDaemonStartupLock tryAcquire(File lockFile) throws IOException {
        FileChannel channel = FileChannel.open(lockFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new ProxyDaemonStartupLock(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            return null;
        } catch (Throwable error) {
            channel.close();
            throw error;
        }
    }

    void publishPid(File target, String pid) throws IOException {
        File parent = target.getParentFile();
        File staging = File.createTempFile(".dashcast_proxy_pid_", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(pid.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                output.getFD().sync();
            }
            Files.deleteIfExists(target.toPath());
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staging.toPath());
        }
    }

    @Override public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
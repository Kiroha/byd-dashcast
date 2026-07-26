package com.byd.dashcast.util.concurrent;

/** Owns one closeable native resource and defers release while callers actively use it. */
public final class CloseableResourceSlot<T> {

    public interface Closer<T> {
        void close(T resource) throws Throwable;
    }

    private final Closer<T> closer;
    private T resource;
    private int users;
    private boolean released;

    public CloseableResourceSlot(Closer<T> closer) {
        if (closer == null) throw new IllegalArgumentException("closer required");
        this.closer = closer;
    }

    /** Publishes the resource or closes it immediately when release already won. */
    public boolean publish(T value) {
        if (value == null) throw new IllegalArgumentException("resource required");
        T closeNow = null;
        synchronized (this) {
            if (released || resource != null) {
                closeNow = value;
            } else {
                resource = value;
                return true;
            }
        }
        closeQuietly(closeNow);
        return false;
    }

    /** Acquires the current resource for use, or null once absent/released. */
    public synchronized T acquire() {
        if (released || resource == null) return null;
        users++;
        return resource;
    }

    /** Ends one use and performs a deferred release when this was the final user. */
    public void releaseUse(T expected) {
        T closeNow = null;
        synchronized (this) {
            if (resource != expected || users <= 0) return;
            users--;
            if (released && users == 0) {
                closeNow = resource;
                resource = null;
            }
        }
        closeQuietly(closeNow);
    }

    /** Marks the slot terminal and closes now or after the final active user. */
    public void release() {
        T closeNow = null;
        synchronized (this) {
            if (released) return;
            released = true;
            if (users == 0) {
                closeNow = resource;
                resource = null;
            }
        }
        closeQuietly(closeNow);
    }

    public synchronized boolean hasResource() {
        return !released && resource != null;
    }

    public synchronized boolean isReleased() {
        return released;
    }

    private void closeQuietly(T value) {
        if (value == null) return;
        try { closer.close(value); } catch (Throwable ignored) {}
    }
}

package com.byd.dashcast.util.concurrent;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Single-worker FIFO executor with explicit bounded backpressure and no caller-thread fallback. */
public final class BoundedSerialExecutor implements Executor {

    private final ThreadPoolExecutor delegate;

    public BoundedSerialExecutor(int queueCapacity, ThreadFactory threadFactory) {
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be positive");
        if (threadFactory == null) throw new IllegalArgumentException("threadFactory required");
        delegate = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }
}

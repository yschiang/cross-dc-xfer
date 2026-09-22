package com.gigaxfer.core.nfs;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedNfsExecutor implements NfsExecutor {
    private final ThreadPoolExecutor pool;
    private final Duration timeout;

    public BoundedNfsExecutor(String name, int slots, Duration timeout) {
        AtomicInteger seq = new AtomicInteger();
        // core == max 且 SynchronousQueue：沒有 idle thread 可接手就直接 reject，不排隊
        this.pool = new ThreadPoolExecutor(slots, slots, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, name + "-nfs-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
        this.timeout = timeout;
    }

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        Future<T> future;
        try {
            future = pool.submit(body::call);
        } catch (RejectedExecutionException e) {
            throw new NfsBusyException(op);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 刻意不 cancel：hard mount 下卡住的 thread 殺不掉，槽位只在 syscall 回來才釋放。
            // 交出 future：呼叫端可據此保留 operation ownership，等它真正結束再下結論（SR-05）
            throw new NfsTimeoutException(op, future);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) throw io;
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error er) throw er;
            throw new IOException(op + " failed", c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NfsTimeoutException(op, future);
        }
    }

    /** 目前被占用的槽位數（含已 timeout 但 syscall 未返回者）。 */
    public int inUse() {
        return pool.getActiveCount();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}

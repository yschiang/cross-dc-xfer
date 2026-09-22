package com.gigaxfer.core.nfs;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedNfsExecutor implements NfsExecutor {
    private final ThreadPoolExecutor pool;
    private final int slots;
    /**
     * 槽位 = permit，在 body 返回的同一個 finally 釋放，早於 future 完成。不能用 thread 是否閒置當槽位：
     * worker 在 future 完成後才回到池中，緊接著的下一個 op 會被誤判池滿。
     */
    private final Semaphore permits;
    private final Duration timeout;

    public BoundedNfsExecutor(String name, int slots, Duration timeout) {
        AtomicInteger seq = new AtomicInteger();
        // 不排隊由 permit 保證：拿不到 permit 立即 NfsBusy。佇列內最多 slots 個剛拿到 permit、
        // 等「已釋放 permit、正要回池」的 worker 接手的任務，只停留微秒級。
        this.slots = slots;
        this.permits = new Semaphore(slots);
        this.pool = new ThreadPoolExecutor(slots, slots, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
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
        if (!permits.tryAcquire()) throw new NfsBusyException(op);
        Future<T> future;
        try {
            future = pool.submit(() -> {
                try {
                    return body.call();
                } finally {
                    permits.release(); // syscall 真的返回才釋放（D51）
                }
            });
        } catch (RejectedExecutionException e) { // 已 close
            permits.release();
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
        return slots - permits.availablePermits();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}

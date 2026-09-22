package com.gigaxfer.core.store;

import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性故障：dropBefore = 操作根本沒送出；dropAfter = 操作已完成但回覆遺失；
 * failBefore = 操作根本沒送出且是硬失敗（ENOSPC/EIO 之類的一般 IOException，不是 timeout）；
 * dropBeforeNth = 同名 op 的第 N 次呼叫沒送出（用來打中「逐塊」操作的中段）；
 * hang = 操作已送出但卡住（hard mount）：呼叫端立刻拿到帶著 in-flight future 的 timeout，
 * 操作要等 release 放行後才真正執行、生效；送出的 future 依序記在 {@link #hung}。
 * pause = 呼叫端執行緒在送出前同步停住（arrived 通知、等 go），用來排出兩個 handle 的交錯。
 */
final class FaultInjectingNfs implements NfsExecutor {
    private final NfsExecutor inner;
    private final Map<String, Boolean> before = new ConcurrentHashMap<>();
    private final Map<String, Boolean> after = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hardFail = new ConcurrentHashMap<>();
    private final Map<String, Integer> beforeNth = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> hangs = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch[]> pauses = new ConcurrentHashMap<>();
    final List<Future<?>> hung = new CopyOnWriteArrayList<>();

    FaultInjectingNfs(NfsExecutor inner) {
        this.inner = inner;
    }

    void dropBefore(String op) {
        before.put(op, Boolean.TRUE);
    }

    void dropAfter(String op) {
        after.put(op, Boolean.TRUE);
    }

    void failBefore(String op) {
        hardFail.put(op, Boolean.TRUE);
    }

    /** n 從 1 起算，只針對這個 FaultInjectingNfs 實例看到的第 n 次同名 op。 */
    void dropBeforeNth(String op, int n) {
        beforeNth.put(op, n);
    }

    void hang(String op, CountDownLatch release) {
        hangs.put(op, release);
    }

    void pause(String op, CountDownLatch arrived, CountDownLatch go) {
        pauses.put(op, new CountDownLatch[]{arrived, go});
    }

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        CountDownLatch[] p = pauses.remove(op);
        if (p != null) {
            p[0].countDown();
            try {
                p[1].await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NfsTimeoutException(op);
            }
        }
        if (before.remove(op) != null) throw new NfsTimeoutException(op);
        CountDownLatch release = hangs.remove(op);
        if (release != null) {
            FutureTask<T> stuck = new FutureTask<>(() -> {
                release.await();
                return body.call();
            });
            Thread t = new Thread(stuck, "hung-" + op);
            t.setDaemon(true);
            t.start();
            hung.add(stuck);
            throw new NfsTimeoutException(op, stuck);
        }
        if (hardFail.remove(op) != null) throw new IOException(op + ": EIO");
        Integer nth = beforeNth.get(op);
        if (nth != null && seen.computeIfAbsent(op, k -> new AtomicInteger()).incrementAndGet() == nth) {
            beforeNth.remove(op);
            throw new NfsTimeoutException(op);
        }
        T result = inner.call(op, body);
        if (after.remove(op) != null) throw new NfsTimeoutException(op);
        return result;
    }

    @Override
    public void close() {
        inner.close();
    }
}

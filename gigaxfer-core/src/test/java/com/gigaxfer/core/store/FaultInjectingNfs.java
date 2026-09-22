package com.gigaxfer.core.store;

import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性故障：dropBefore = 操作根本沒送出；dropAfter = 操作已完成但回覆遺失；
 * failBefore = 操作根本沒送出且是硬失敗（ENOSPC/EIO 之類的一般 IOException，不是 timeout）；
 * dropBeforeNth = 同名 op 的第 N 次呼叫沒送出（用來打中「逐塊」操作的中段）。
 */
final class FaultInjectingNfs implements NfsExecutor {
    private final NfsExecutor inner;
    private final Map<String, Boolean> before = new ConcurrentHashMap<>();
    private final Map<String, Boolean> after = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hardFail = new ConcurrentHashMap<>();
    private final Map<String, Integer> beforeNth = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();

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

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        if (before.remove(op) != null) throw new NfsTimeoutException(op);
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

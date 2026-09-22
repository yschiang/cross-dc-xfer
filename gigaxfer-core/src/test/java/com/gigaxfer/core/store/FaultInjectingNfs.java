package com.gigaxfer.core.store;

import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 一次性故障：dropBefore = 操作根本沒送出；dropAfter = 操作已完成但回覆遺失。 */
final class FaultInjectingNfs implements NfsExecutor {
    private final NfsExecutor inner;
    private final Map<String, Boolean> before = new ConcurrentHashMap<>();
    private final Map<String, Boolean> after = new ConcurrentHashMap<>();

    FaultInjectingNfs(NfsExecutor inner) {
        this.inner = inner;
    }

    void dropBefore(String op) {
        before.put(op, Boolean.TRUE);
    }

    void dropAfter(String op) {
        after.put(op, Boolean.TRUE);
    }

    @Override
    public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
        if (before.remove(op) != null) throw new NfsTimeoutException(op);
        T result = inner.call(op, body);
        if (after.remove(op) != null) throw new NfsTimeoutException(op);
        return result;
    }

    @Override
    public void close() {
        inner.close();
    }
}

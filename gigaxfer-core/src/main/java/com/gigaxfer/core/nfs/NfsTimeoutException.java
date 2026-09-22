package com.gigaxfer.core.nfs;

/** 操作結果未知：呼叫者不再等待，但底層 syscall 仍在進行、槽位仍被占用（D51、SR-04）。 */
public final class NfsTimeoutException extends NfsException {

    public NfsTimeoutException(String op) {
        super(op, "nfs operation timed out: " + op);
    }
}

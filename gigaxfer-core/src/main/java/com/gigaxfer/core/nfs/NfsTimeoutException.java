package com.gigaxfer.core.nfs;

/** 操作結果未知：呼叫者不再等待，但底層 syscall 仍在進行、槽位仍被占用（D51、SR-04）。 */
public final class NfsTimeoutException extends Exception {
    private final String op;

    public NfsTimeoutException(String op) {
        super("nfs operation timed out: " + op);
        this.op = op;
    }

    public String op() {
        return op;
    }
}

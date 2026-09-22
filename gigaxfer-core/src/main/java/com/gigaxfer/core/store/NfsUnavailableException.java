package com.gigaxfer.core.store;

import java.io.IOException;

/**
 * 串流寫入或 discard 過程中 NFS 有界執行器回報 busy/timeout（D51）。
 * Task 8 的 finalize() 會攔截此例外並回傳 PendingConfirmation(op)。
 */
public final class NfsUnavailableException extends IOException {
    private final String op;

    public NfsUnavailableException(String op, Exception cause) {
        super("nfs unavailable at " + op, cause);
        this.op = op;
    }

    public String op() {
        return op;
    }
}

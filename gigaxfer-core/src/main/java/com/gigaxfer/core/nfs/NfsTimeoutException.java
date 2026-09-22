package com.gigaxfer.core.nfs;

import java.util.concurrent.Future;

/** 操作結果未知：呼叫者不再等待，但底層 syscall 仍在進行、槽位仍被占用（D51、SR-04）。 */
public final class NfsTimeoutException extends NfsException {
    private final transient Future<?> inFlight;

    public NfsTimeoutException(String op) {
        this(op, null);
    }

    public NfsTimeoutException(String op, Future<?> inFlight) {
        super(op, "nfs operation timed out: " + op);
        this.inFlight = inFlight;
    }

    /**
     * 呼叫者放棄等待、但可能仍在執行的那個操作；結束（isDone）後它對檔案系統的效果才確定。
     * null 表示沒有可追蹤的操作（例如故障注入的「根本沒送出」）。
     */
    public Future<?> inFlight() {
        return inFlight;
    }
}

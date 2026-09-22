package com.gigaxfer.core.store;

public final class WriteRejectedException extends Exception {
    public enum Reason { REJECTED, UNAVAILABLE, IO }

    private final Reason reason;

    public WriteRejectedException(Reason reason, String detail) {
        this(reason, detail, null);
    }

    public WriteRejectedException(Reason reason, String detail, Throwable cause) {
        super(reason + ": " + detail, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

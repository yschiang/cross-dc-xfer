package com.gigaxfer.core.store;

public final class WriteRejectedException extends Exception {
    public enum Reason { REJECTED, UNAVAILABLE, IO }

    private final Reason reason;

    public WriteRejectedException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

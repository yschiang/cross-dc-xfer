package com.gigaxfer.core.nfs;

/** 有界執行器已滿：呼叫者立即得知，不排隊（D51）。 */
public final class NfsBusyException extends Exception {
    private final String op;

    public NfsBusyException(String op) {
        super("nfs pool exhausted at " + op);
        this.op = op;
    }

    public String op() {
        return op;
    }
}

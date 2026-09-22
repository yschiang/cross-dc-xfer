package com.gigaxfer.core.nfs;

/** 有界執行器已滿：呼叫者立即得知，不排隊（D51）。 */
public final class NfsBusyException extends NfsException {

    public NfsBusyException(String op) {
        super(op, "nfs pool exhausted at " + op);
    }
}

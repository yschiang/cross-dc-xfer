package com.gigaxfer.core.nfs;

/** 共同基底：所有經 {@link NfsExecutor} 回報的失敗都帶著觸發它的 op 名稱。 */
public abstract class NfsException extends Exception {
    private final String op;

    protected NfsException(String op, String message) {
        super(message);
        this.op = op;
    }

    public String op() {
        return op;
    }
}

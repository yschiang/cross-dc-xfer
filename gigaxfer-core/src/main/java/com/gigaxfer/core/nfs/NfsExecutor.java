package com.gigaxfer.core.nfs;

import java.io.IOException;

/**
 * 所有檔案系統操作的唯一入口（D51、SR-05）。
 * 實作必須：固定槽位、無佇列、滿了立即丟 NfsBusyException、timeout 丟 NfsTimeoutException 但不釋放槽位。
 */
public interface NfsExecutor extends AutoCloseable {

    <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException;

    default void run(String op, IoRunnable body) throws NfsBusyException, NfsTimeoutException, IOException {
        call(op, () -> {
            body.run();
            return null;
        });
    }

    @Override
    void close();

    @FunctionalInterface
    interface IoCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    interface IoRunnable {
        void run() throws Exception;
    }
}

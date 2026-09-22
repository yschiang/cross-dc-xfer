package com.gigaxfer.sync.db;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** DB 就緒狀態：migration 與 bootstrap 列完成後才 ready；health 與後續排程都看這個旗標（D34 修）。 */
public final class DbState {
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile String lastError;
    private final Runnable bootstrap;

    DbState(Runnable bootstrap) {
        this.bootstrap = bootstrap;
    }

    public boolean ready() {
        return ready.getCount() == 0;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public boolean awaitReady(Duration timeout) {
        try {
            return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 執行一次 migration + bootstrap；成功即 ready。冪等：可重複呼叫（測試與重試共用）。 */
    public void runOnce() {
        try {
            bootstrap.run();
            lastError = null;
            ready.countDown();
        } catch (RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        }
    }
}

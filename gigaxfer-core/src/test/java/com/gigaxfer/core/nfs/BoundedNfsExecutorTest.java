package com.gigaxfer.core.nfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedNfsExecutorTest {

    @Test
    void returns_value_and_propagates_io_exception() throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 2, Duration.ofSeconds(1))) {
            assertThat(nfs.call("op", () -> 42)).isEqualTo(42);
            assertThatThrownBy(() -> nfs.call("op", () -> { throw new IOException("boom"); }))
                .isInstanceOf(IOException.class).hasMessage("boom");
        }
    }

    @Test
    void timeout_does_not_release_slot_and_full_pool_rejects_immediately() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            assertThatThrownBy(() -> nfs.call("slow", () -> {
                release.await();
                finished.countDown();
                return null;
            })).isInstanceOf(NfsTimeoutException.class);

            // 槽位仍被卡住的 syscall 占用（D51：timeout 只是呼叫者不等）
            assertThat(nfs.inUse()).isEqualTo(1);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> nfs.call("next", () -> 1)).isInstanceOf(NfsBusyException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(50));

            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(20); // 讓 worker 回到 idle
            assertThat(nfs.call("after", () -> 7)).isEqualTo(7);
        }
    }

    @Test
    void exceptions_carry_op_name() {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(50))) {
            assertThatThrownBy(() -> nfs.call("link-key", () -> { Thread.sleep(500); return null; }))
                .isInstanceOf(NfsTimeoutException.class)
                .satisfies(e -> assertThat(((NfsTimeoutException) e).op()).isEqualTo("link-key"));
        }
    }
}

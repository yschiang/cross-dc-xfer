package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizeUnderPressureTest {
    final Clock clock = Clock.fixed(Instant.parse("2026-09-22T08:15:03Z"), ZoneOffset.UTC);

    /** 占滿唯一槽位，回傳釋放用 latch。 */
    static CountDownLatch occupy(BoundedNfsExecutor nfs) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                nfs.call("hang", () -> { started.countDown(); release.await(); return null; });
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        started.await();
        return release;
    }

    @Test
    void beginWrite_when_pool_full_is_unavailable(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            CountDownLatch release = occupy(nfs);
            assertThatThrownBy(() -> store.beginWrite("mes", "metrology", "L1"))
                .isInstanceOf(WriteRejectedException.class)
                .satisfies(e -> assertThat(((WriteRejectedException) e).reason()).isEqualTo(WriteRejectedException.Reason.UNAVAILABLE));
            release.countDown();
        }
    }

    @Test
    void write_when_pool_full_throws_io_exception(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            WriteHandle h = store.beginWrite("mes", "metrology", "L1");
            CountDownLatch release = occupy(nfs);
            byte[] big = new byte[128 * 1024]; // 超過 64 KB buffer，強制底層 write
            assertThatThrownBy(() -> h.stream().write(big)).isInstanceOf(IOException.class);
            release.countDown();
        }
    }

    @Test
    void finalize_when_pool_full_is_pending_confirmation_and_retry_succeeds(@TempDir Path root) throws Exception {
        try (BoundedNfsExecutor nfs = new BoundedNfsExecutor("t", 1, Duration.ofMillis(100))) {
            LocalStore store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
            WriteHandle h = store.beginWrite("mes", "metrology", "L1");
            // 用超過 64 KB buffer 的內容，讓 write() 在占滿池前就把資料送到底層，
            // 使緩衝區在 finalizeWrite() 時是空的：第一個踩到池滿的 executor op 是 fsync-writing。
            // 若改成短內容，stream.flush() 會先撞池滿，handle 會被 poison（見 WriteHandleUnavailableTest）。
            h.stream().write(new byte[128 * 1024]);
            CountDownLatch release = occupy(nfs);
            FinalizeResult r = h.finalizeWrite();
            assertThat(r).isInstanceOf(FinalizeResult.PendingConfirmation.class);
            assertThat(((FinalizeResult.PendingConfirmation) r).op()).isEqualTo("fsync-writing");
            release.countDown();
            // 重試到收斂為止，別靠固定 sleep 猜槽位何時釋放
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            FinalizeResult second;
            do {
                second = h.finalizeWrite();
            } while (second instanceof FinalizeResult.PendingConfirmation && System.nanoTime() < deadline);
            assertThat(second).isInstanceOf(FinalizeResult.Success.class);
            assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/L1")).hasSize(128 * 1024);
        }
    }
}

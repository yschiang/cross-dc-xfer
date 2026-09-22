package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LinkOwnershipWithBoundedExecutorTest {
    @TempDir Path root;

    /**
     * 真正把 link body 卡在 BoundedNfsExecutor worker 裡；timeout 與 completion Future 都由 production
     * executor 產生，不由 fault injector 合成。
     */
    @Test
    void retry_keeps_real_timed_out_link_ownership_until_worker_finishes() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
        PathLayout layout = new PathLayout(root, ZoneOffset.UTC);
        try (BoundedNfsExecutor bounded = new BoundedNfsExecutor("blocking-link", 4, Duration.ofMillis(50))) {
            BlockingLinkExecutor nfs = new BlockingLinkExecutor(bounded);
            LocalStore store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
            WriteHandle first = store.beginWrite("mes", "metrology", "L1");
            first.stream().write("payload".getBytes(StandardCharsets.UTF_8));

            FinalizeResult timedOut = first.finalizeWrite();
            assertThat(timedOut).isInstanceOf(FinalizeResult.PendingConfirmation.class);
            assertThat(nfs.linkCalls).hasValue(1);
            assertThat(nfs.started.await(1, TimeUnit.SECONDS)).isTrue();

            FileIdentity id = new FileIdentity("P3", "mes", "L1");
            Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
            clock.advance(Duration.ofDays(8));

            assertThat(first.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
            WriteHandle retry = store.beginWrite("mes", "metrology", "L1");
            retry.stream().write("payload".getBytes(StandardCharsets.UTF_8));
            assertThat(retry.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
            assertThat(nfs.linkCalls).hasValue(1);
            assertThat(first.writingPath()).exists();

            nfs.release.countDown();
            assertThat(nfs.finished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(finalizeUntilSettled(first)).isInstanceOf(FinalizeResult.Success.class);
            assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/L1")).hasContent("payload");
        }
    }

    private static FinalizeResult finalizeUntilSettled(WriteHandle handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        FinalizeResult result;
        do {
            result = handle.finalizeWrite();
            if (result instanceof FinalizeResult.PendingConfirmation) Thread.sleep(5);
        } while (result instanceof FinalizeResult.PendingConfirmation && System.nanoTime() < deadline);
        return result;
    }

    private static final class BlockingLinkExecutor implements NfsExecutor {
        private final BoundedNfsExecutor delegate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicInteger linkCalls = new AtomicInteger();

        private BlockingLinkExecutor(BoundedNfsExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T call(String op, IoCallable<T> body)
                throws NfsBusyException, NfsTimeoutException, IOException {
            if (!op.equals("link-key")) return delegate.call(op, body);
            linkCalls.incrementAndGet();
            return delegate.call(op, () -> {
                started.countDown();
                try {
                    release.await();
                    return body.call();
                } finally {
                    finished.countDown();
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}

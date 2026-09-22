package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WriteHandleUnavailableTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor real;
    PathLayout layout;

    @BeforeEach
    void setUp() {
        real = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        layout = new PathLayout(root, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    /** 第一個 call（open-writing）委派給真的 executor成功建立 handle；之後每個 op 都丟 NfsBusyException。 */
    private NfsExecutor flakyAfterFirstCall() {
        return new NfsExecutor() {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override
            public <T> T call(String op, IoCallable<T> body) throws NfsBusyException, NfsTimeoutException, IOException {
                if (first.compareAndSet(true, false)) {
                    return real.call(op, body);
                }
                throw new NfsBusyException(op);
            }

            @Override
            public void close() {
                // no-op: 底層的 real executor 由 tearDown() 關閉
            }
        };
    }

    LocalStore store() {
        return new LocalStore("P3", layout, flakyAfterFirstCall(), WriteGate.open(), clock);
    }

    @Test
    void write_failure_poisons_handle_and_finalize_reports_it() throws Exception {
        WriteHandle h = store().beginWrite("mes", "metrology", "L1");

        assertThatThrownBy(() -> h.stream().write(new byte[128 * 1024]))
            .isInstanceOf(NfsUnavailableException.class)
            .satisfies(e -> assertThat(((NfsUnavailableException) e).op()).isEqualTo("write"));

        assertThat(h.finalizeWrite())
            .isEqualTo(new FinalizeResult.Failure(FailureReason.IO, "stream failed at write"));
    }

    /** C1：一般 IOException（ENOSPC/EIO…）也必須毒化 handle，否則會發布位元組與宣告不一致的檔。 */
    @Test
    void write_io_error_poisons_handle_and_finalize_publishes_nothing() throws Exception {
        FaultInjectingNfs nfs = new FaultInjectingNfs(real);
        LocalStore store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
        WriteHandle h = store.beginWrite("mes", "metrology", "L3");

        nfs.failBefore("write"); // ENOSPC：位元組沒落盤，md/size 也沒更新
        assertThatThrownBy(() -> h.stream().write(new byte[128 * 1024]))
            .isInstanceOf(IOException.class)
            .isNotInstanceOf(NfsUnavailableException.class);

        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
        assertThat(layout.manifestDir(new FileIdentity("P3", "mes", "L3"))).doesNotExist();
        assertThat(h.writingPath()).doesNotExist();
    }

    @Test
    void discard_failure_surfaces_as_nfs_unavailable() throws Exception {
        WriteHandle h = store().beginWrite("mes", "metrology", "L2");

        assertThatThrownBy(h::discard)
            .isInstanceOf(NfsUnavailableException.class)
            .satisfies(e -> assertThat(((NfsUnavailableException) e).op()).isEqualTo("discard-writing"));
    }
}

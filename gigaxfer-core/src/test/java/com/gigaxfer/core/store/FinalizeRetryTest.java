package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** SR-04：結果未知的一步之後，重呼 finalizeWrite() 必須還能走完。 */
class FinalizeRetryTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    FaultInjectingNfs nfs;
    LocalStore store;

    @BeforeEach
    void setUp() {
        nfs = new FaultInjectingNfs(new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5)));
        store = new LocalStore("P3", new PathLayout(root, ZoneOffset.UTC), nfs, WriteGate.open(), clock);
    }

    @AfterEach
    void tearDown() {
        nfs.close();
    }

    @Test
    void fsync_timeout_is_pending_then_retry_publishes() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "R1");
        h.stream().write("retry".getBytes(StandardCharsets.UTF_8));
        nfs.dropAfter("fsync-writing"); // syscall 做完才丟 timeout＝呼叫端拿不到回覆（D51）

        FinalizeResult first = h.finalizeWrite();

        assertThat(first).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) first).op()).isEqualTo("fsync-writing");

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/R1")).hasContent("retry");
    }
}

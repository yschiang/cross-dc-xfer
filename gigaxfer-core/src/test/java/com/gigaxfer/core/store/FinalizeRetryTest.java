package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** close() 後 PendingConfirmation 的重試仍要收斂：重開 writing 完成 fsync，不能把暫存刪掉。 */
    @Test
    void fsync_timeout_then_close_then_retry_still_publishes() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "R2");
        h.stream().write(new byte[128 * 1024]);
        nfs.dropAfter("fsync-writing");

        FinalizeResult first = h.finalizeWrite();
        assertThat(first).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) first).op()).isEqualTo("fsync-writing");

        h.close();

        FinalizeResult second = h.finalizeWrite();
        assertThat(second).isInstanceOf(FinalizeResult.Success.class);
        Path published = root.resolve("P3/mes/metrology/2026-09-22/08/R2");
        assertThat(published).hasSize(128 * 1024);
        assertThat(h.writingPath()).doesNotExist();
    }

    /**
     * P01-04／P01-08：SUCCESS 後正式內容不可變。close-writing 失敗（通道仍開著、指向已發布的 inode）時，
     * 經 handle 的任何寫入都必須被拒，正式檔大小與內容不變；被拒的寫入也不得把已發布的 handle 毒化成 FAILURE。
     */
    @Test
    void writes_after_success_are_rejected_even_if_close_failed() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "R3");
        h.stream().write("payload".getBytes(StandardCharsets.UTF_8));
        nfs.dropBefore("close-writing");

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        Path published = root.resolve("P3/mes/metrology/2026-09-22/08/R3");

        assertThatThrownBy(() -> h.stream().write(new byte[128 * 1024])).isInstanceOf(IOException.class); // 超過緩衝，直達通道
        assertThatThrownBy(() -> {
            h.stream().write('x'); // 小於緩衝
            h.stream().flush();
        }).isInstanceOf(IOException.class);

        assertThat(published).hasContent("payload");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
    }
}

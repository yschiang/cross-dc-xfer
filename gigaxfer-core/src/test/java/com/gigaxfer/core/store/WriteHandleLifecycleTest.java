package com.gigaxfer.core.store;

import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR #3 合併後稽核的兩個 lifecycle P1 與 link future 殘留：終態要在事實發生後才進入。 */
class WriteHandleLifecycleTest {
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

    /** discard 的刪除失敗後不得假裝成功：重呼要再刪一次，刪掉才算 DISCARDED。 */
    @Test
    void discard_retries_delete_after_nfs_failure() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "D1");
        h.stream().write("x".getBytes(StandardCharsets.UTF_8));
        nfs.dropBefore("discard-writing");

        assertThatThrownBy(h::discard).isInstanceOf(NfsUnavailableException.class);
        assertThat(h.writingPath()).exists();
        FinalizeResult r = h.finalizeWrite(); // 刪除結果未定，不可發布；原因是 discard，不是 stream 中毒
        assertThat(((FinalizeResult.Failure) r).detail()).startsWith("discard delete unresolved at discard-writing");
        assertThat(h.writingPath()).exists(); // finalizeWrite 不代 discard 清暫存

        h.discard(); // 真的再刪一次
        assertThat(h.writingPath()).doesNotExist();
        h.discard(); // 已刪：冪等
        assertThat(h.finalizeWrite()).isSameAs(r);
    }

    /** 刪除遇到一般 IOException（EACCES、EIO…）也不算已放棄：handle 中毒，finalizeWrite 不得發布。 */
    @Test
    void discard_hard_io_failure_poisons_handle_and_never_publishes() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "D2");
        h.stream().write("x".getBytes(StandardCharsets.UTF_8));
        nfs.failBefore("discard-writing");

        assertThatThrownBy(h::discard).isInstanceOf(java.io.IOException.class)
            .isNotInstanceOf(NfsUnavailableException.class);
        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).detail()).startsWith("discard delete unresolved at discard-writing");
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/D2")).doesNotExist();

        h.discard();
        assertThat(h.writingPath()).doesNotExist();
    }

    /** README 規則 5：Finalize 回 Failure 的 handle 可 discard；重呼 finalizeWrite 回同一個 Failure。 */
    @Test
    void failure_is_terminal_and_discardable() throws Exception {
        WriteHandle a = store.beginWrite("mes", "metrology", "F1");
        a.stream().write("one".getBytes(StandardCharsets.UTF_8));
        assertThat(a.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);

        WriteHandle b = store.beginWrite("mes", "metrology", "F1");
        b.stream().write("two".getBytes(StandardCharsets.UTF_8));
        FinalizeResult first = b.finalizeWrite();
        assertThat(first).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) first).reason()).isEqualTo(FailureReason.CONFLICT);

        assertThat(b.finalizeWrite()).isSameAs(first);
        assertThatThrownBy(() -> b.stream().write('x')).hasMessageContaining("handle failed");
        b.discard(); // 不丟 IllegalStateException
        assertThat(b.writingPath()).doesNotExist();
        assertThat(b.finalizeWrite()).isSameAs(first); // discard 之後結論仍不變
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/F1")).hasContent("one");
    }

    /** 放棄的 Pending handle 其 link future 結束後，下一次 linkSent 會把它回收（兩個 handle 都在任何 timeout 前建立，排除 beginWrite 的回收）。 */
    @Test
    void completed_link_futures_are_reclaimed_on_next_link_sent() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L1");
        WriteHandle other = store.beginWrite("mes", "metrology", "L2");
        h.stream().write("l".getBytes(StandardCharsets.UTF_8));
        other.stream().write("m".getBytes(StandardCharsets.UTF_8));

        CountDownLatch release = new CountDownLatch(1);
        nfs.hang("link-key", release);
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        release.countDown();
        nfs.hung.get(0).get(); // 放棄 h：沒有人再對 L1 呼叫 finalizeWrite
        assertThat(store.inFlightIdentities()).isEqualTo(1); // 已結束但沒人回收

        CountDownLatch release2 = new CountDownLatch(1);
        nfs.hang("link-key", release2);
        assertThat(other.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(store.inFlightIdentities()).isEqualTo(1); // linkSent 回收了 L1，只剩 L2

        release2.countDown();
        nfs.hung.get(1).get();
        assertThat(other.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(Files.exists(root.resolve("P3/mes/metrology/2026-09-22/08/L1"))).isTrue();
    }

    /** 沒有再發生 timeout 時，最後一批已結束的 future 由下一次 beginWrite 回收。 */
    @Test
    void completed_link_futures_are_reclaimed_on_begin_write() throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L1");
        h.stream().write("l".getBytes(StandardCharsets.UTF_8));
        CountDownLatch release = new CountDownLatch(1);
        nfs.hang("link-key", release);
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        release.countDown();
        nfs.hung.get(0).get();
        assertThat(store.inFlightIdentities()).isEqualTo(1); // 已結束但沒人回收

        store.beginWrite("mes", "metrology", "L2").discard();
        assertThat(store.inFlightIdentities()).isZero();
    }
}

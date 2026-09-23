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

    /** 放棄的 Pending handle 其 link future 結束後，下一次登記或 beginWrite 會把它回收，不永久留在記憶體。 */
    @Test
    void completed_link_futures_are_reclaimed_on_next_link_sent() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        WriteHandle h = store.beginWrite("mes", "metrology", "L1");
        h.stream().write("l".getBytes(StandardCharsets.UTF_8));
        nfs.hang("link-key", release);
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(store.inFlightIdentities()).isEqualTo(1);

        release.countDown();
        nfs.hung.get(0).get(); // 放棄 h：沒有人再對 L1 呼叫 finalizeWrite

        WriteHandle other = store.beginWrite("mes", "metrology", "L2");
        other.stream().write("m".getBytes(StandardCharsets.UTF_8));
        CountDownLatch release2 = new CountDownLatch(1);
        nfs.hang("link-key", release2);
        assertThat(other.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(store.inFlightIdentities()).isEqualTo(1); // 只剩 L2

        release2.countDown();
        nfs.hung.get(1).get();
        assertThat(other.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        store.beginWrite("mes", "metrology", "L3").discard(); // 沒有新 timeout 也會回收最後一批
        assertThat(store.inFlightIdentities()).isZero();
        assertThat(Files.exists(root.resolve("P3/mes/metrology/2026-09-22/08/L1"))).isTrue();
    }
}

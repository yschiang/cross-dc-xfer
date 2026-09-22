package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FinalizeRecoveryTest {
    @TempDir Path root;
    MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:15:03Z"));
    BoundedNfsExecutor real;
    FaultInjectingNfs nfs;
    PathLayout layout;
    LocalStore store;
    final FileIdentity id = new FileIdentity("P3", "mes", "L1");
    final byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
    final Path key08 = Path.of("P3/mes/metrology/2026-09-22/08/L1");

    @BeforeEach
    void setUp() {
        real = new BoundedNfsExecutor("t", 4, Duration.ofSeconds(5));
        nfs = new FaultInjectingNfs(real);
        layout = new PathLayout(root, ZoneOffset.UTC);
        store = new LocalStore("P3", layout, nfs, WriteGate.open(), clock);
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    WriteHandle write(byte[] bytes) throws Exception {
        WriteHandle h = store.beginWrite("mes", "metrology", "L1");
        h.stream().write(bytes);
        return h;
    }

    long regularFiles() throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void F2_link_not_sent_then_retry_publishes() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        FinalizeResult first = h.finalizeWrite();
        assertThat(first).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) first).op()).isEqualTo("link-key");
        assertThat(layout.manifestPath(id)).exists();
        assertThat(root.resolve(key08)).doesNotExist();
        assertThat(h.writingPath()).exists();

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(h.writingPath()).doesNotExist();
    }

    @Test
    void F3_link_done_but_reply_lost_then_retry_is_success_without_duplicate() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-key");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(regularFiles()).isEqualTo(2); // <key> + manifest，無殘留
    }

    @Test
    void F5b_manifest_link_reply_lost_then_retry_publishes_at_declared_content_path() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-manifest");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        clock.advance(Duration.ofHours(3)); // 重試落在不同小時，content_path 仍以第一次宣告為準（D48 修）
        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        assertThat(((FinalizeResult.Success) r).contentPath()).isEqualTo(key08.toString());
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void F5_same_identity_different_content_is_conflict_and_leaves_original() throws Exception {
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        WriteHandle h2 = write("different".getBytes(StandardCharsets.UTF_8));
        FinalizeResult r = h2.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.CONFLICT);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
        assertThat(h2.writingPath()).doesNotExist();
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void same_identity_same_content_from_new_handle_next_day_is_idempotent_success() throws Exception {
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        clock.advance(Duration.ofDays(1));
        WriteHandle h2 = write(content);
        FinalizeResult r = h2.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Success.class);
        assertThat(((FinalizeResult.Success) r).contentPath()).isEqualTo(key08.toString());
        assertThat(h2.writingPath()).doesNotExist();
        assertThat(regularFiles()).isEqualTo(2);
    }

    @Test
    void F2b_retry_after_declaration_older_than_7_days_is_expired() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);

        Instant declared = clock.instant();
        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(declared));
        clock.advance(Duration.ofDays(8));

        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.DECLARATION_EXPIRED);
        assertThat(root.resolve(key08)).doesNotExist();
        assertThat(layout.manifestPath(id)).exists(); // manifest 永不刪（D53）
    }

    @Test
    void scenario_11_published_day_1_retry_day_8_is_success_not_expired() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("link-key");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
        clock.advance(Duration.ofDays(8));
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
    }

    @Test
    void D44_published_file_corrupted_then_same_content_retry_is_conflict() throws Exception {
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        Files.write(root.resolve(key08), "corrupt".getBytes(StandardCharsets.UTF_8));
        FinalizeResult r = write(content).finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.CONFLICT);
    }

    /** I3：digest-key 逐塊送出，中段 timeout 仍是 PendingConfirmation 且重試收斂（整檔一次讀完到不了第 3 次 op）。 */
    @Test
    void digest_key_is_chunked_so_a_mid_file_timeout_is_pending_and_retry_converges() throws Exception {
        byte[] big = new byte[200 * 1024]; // 4 個 64 KB chunk
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        WriteHandle h = store.beginWrite("mes", "metrology", "BIG");
        h.stream().write(big);

        nfs.dropAfter("link-key"); // link 已完成、回覆遺失 → 重試走 verifyPublished
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);

        nfs.dropBeforeNth("digest-key", 3); // open=1、read=2、read=3 → 落在檔案中段
        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) r).op()).isEqualTo("digest-key");

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve("P3/mes/metrology/2026-09-22/08/BIG")).hasBinaryContent(big);
    }

    /** I4：stat-key 讀取失敗不得被當成「不存在」——那會把已發布的 key 判成 DECLARATION_EXPIRED。 */
    @Test
    void stat_key_read_error_is_io_failure_not_declaration_expired() throws Exception {
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);

        // 讓 stat(<key>) 失敗但不是 ENOENT：把 content_path 的小時目錄換成自指 symlink → ELOOP
        Path hour = root.resolve("P3/mes/metrology/2026-09-22/08");
        Files.delete(root.resolve(key08));
        Files.delete(hour);
        Files.createSymbolicLink(hour, hour);

        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
        clock.advance(Duration.ofDays(8)); // 宣告已逾期：吞掉讀取錯誤的實作會回 DECLARATION_EXPIRED

        FinalizeResult r = write(content).finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
    }

    @Test
    void F1b_writing_file_removed_before_link_is_failure_not_pending() throws Exception {
        WriteHandle h = write(content);
        nfs.dropBefore("link-key");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Files.delete(h.writingPath()); // 模擬清道夫依 TTL 刪除
        FinalizeResult r = h.finalizeWrite();
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
        assertThat(root.resolve(key08)).doesNotExist();
    }

    @Test
    void F4_half_written_tmp_from_previous_attempt_is_replaced_not_linked() throws Exception {
        WriteHandle h = write(content);
        nfs.dropAfter("write-manifest-tmp");
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        Path tmp = layout.manifestTmpPath(id, h.uuid());
        Files.write(tmp, "{\"schema_version\":1,\"sou".getBytes(StandardCharsets.UTF_8)); // 半截
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        assertThat(store.codec.decode(Files.readAllBytes(layout.manifestPath(id))).digest())
            .isEqualTo(com.gigaxfer.core.digest.Sha256.ofBytes(content));
    }
}

package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.Manifest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    /** P01-03：既有宣告的 content_path 指向別的 identity（P4/other/...）時不得依它發布、不得回 SUCCESS。 */
    @Test
    void declared_content_path_of_another_identity_is_not_published() throws Exception {
        Manifest forged = new Manifest(Manifest.SCHEMA_VERSION, "P3", "mes", "metrology", "L1", content.length,
            Sha256.ofBytes(content), "11111111-2222-3333-4444-555555555555", clock.instant(),
            "P4/other/metrology/2026-09-22/08/L1");
        Files.createDirectories(layout.manifestDir(id));
        Files.write(layout.manifestPath(id), store.codec.encode(forged));

        FinalizeResult r = write(content).finalizeWrite();

        assertThat(r).isNotInstanceOf(FinalizeResult.Success.class).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(root.resolve("P4")).doesNotExist();
        assertThat(root.resolve(key08)).doesNotExist();
    }

    /** 既有 manifest 超過上限（此例 1 MB，JSON 仍可解析）視為損壞宣告：不得回 SUCCESS。 */
    @Test
    void oversized_existing_manifest_is_not_a_valid_declaration() throws Exception {
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.Success.class);
        byte[] line = Files.readAllBytes(layout.manifestPath(id));
        byte[] padded = new byte[1024 * 1024];
        java.util.Arrays.fill(padded, (byte) ' ');
        System.arraycopy(line, 0, padded, 0, line.length - 1); // 去掉換行，尾端全是空白
        Files.write(layout.manifestPath(id), padded);

        FinalizeResult r = write(content).finalizeWrite();

        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
    }

    /** 重呼直到不再是 PendingConfirmation（等 in-flight 的 link 真正結束），不靠固定 sleep。 */
    FinalizeResult finalizeUntilSettled(WriteHandle h) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        FinalizeResult r;
        do {
            r = h.finalizeWrite();
            if (r instanceof FinalizeResult.PendingConfirmation) Thread.sleep(5);
        } while (r instanceof FinalizeResult.PendingConfirmation && System.nanoTime() < deadline);
        return r;
    }

    /**
     * P01-06／P01-07（D51 修 2）：link-key 已送出但卡住。宣告過期後重試不得回 DECLARATION_EXPIRED、
     * 不得刪暫存——舊 link 之後完成會讓 FAILURE 的交易變成 Source Ready。link 結束前一律 PENDING，
     * 同 identity 的新 handle 也一樣；結束後依 NAS 上的結果判定。
     */
    @Test
    void F1b_link_in_flight_then_declaration_expires_is_pending_until_link_settles() throws Exception {
        WriteHandle h = write(content);
        CountDownLatch release = new CountDownLatch(1);
        nfs.hang("link-key", release);
        FinalizeResult first = h.finalizeWrite();
        assertThat(first).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(((FinalizeResult.PendingConfirmation) first).op()).isEqualTo("link-key");

        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
        clock.advance(Duration.ofDays(8));

        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(write(content).finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class); // 新 handle 同 identity
        assertThat(h.writingPath()).exists();
        assertThat(root.resolve(key08)).doesNotExist();

        release.countDown(); // 舊 link 終於生效
        assertThat(finalizeUntilSettled(h)).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
    }

    /** link 在飛時清道夫刪了暫存：不得推論未發布而回 FAILURE；link 結束且確定沒生效後才 FAILURE。 */
    @Test
    void F1b_writing_removed_while_link_in_flight_is_pending_until_link_settles() throws Exception {
        WriteHandle h = write(content);
        CountDownLatch release = new CountDownLatch(1);
        nfs.hang("link-key", release);
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);

        Files.delete(h.writingPath()); // 模擬清道夫依 TTL 刪除
        assertThat(h.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);

        release.countDown(); // 此 fake 的 link 依名字執行 → ENOENT，確定未生效
        FinalizeResult r = finalizeUntilSettled(h);
        assertThat(r).isInstanceOf(FinalizeResult.Failure.class);
        assertThat(((FinalizeResult.Failure) r).reason()).isEqualTo(FailureReason.IO);
        assertThat(root.resolve(key08)).doesNotExist();
    }

    /**
     * 兩個 handle 都在對方登記前通過檢查、各自送出同 identity 的 link 且都卡住。後送的那個先結束
     * （此處暫存被清道夫刪 → 確定沒生效）；先送的仍在飛時，任何重呼都不得據「後一個已結束」下結論——
     * 否則宣告過期會回 DECLARATION_EXPIRED 並刪暫存，而先送的 link 之後照樣落地。
     */
    @Test
    void F1b_two_links_in_flight_stays_pending_until_every_link_settles() throws Exception {
        WriteHandle a = write(content);
        WriteHandle b = write(content);
        CountDownLatch bArrived = new CountDownLatch(1);
        CountDownLatch bGo = new CountDownLatch(1);
        nfs.pause("write-manifest-tmp", bArrived, bGo); // b 已過 in-flight 檢查，停在宣告前
        java.util.concurrent.CompletableFuture<FinalizeResult> bFirst = java.util.concurrent.CompletableFuture.supplyAsync(b::finalizeWrite);
        assertThat(bArrived.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch releaseA = new CountDownLatch(1);
        nfs.hang("link-key", releaseA);
        assertThat(a.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class); // a 的 link 在飛
        CountDownLatch releaseB = new CountDownLatch(1);
        nfs.hang("link-key", releaseB);
        bGo.countDown();
        assertThat(bFirst.get(5, TimeUnit.SECONDS)).isInstanceOf(FinalizeResult.PendingConfirmation.class); // b 的 link 也在飛
        assertThat(nfs.hung).hasSize(2);

        Files.delete(b.writingPath()); // 清道夫刪了 b 的暫存
        releaseB.countDown();
        assertThat(nfs.hung.get(1)).failsWithin(5, TimeUnit.SECONDS); // b 的 link 以 ENOENT 結束、未生效；a 的仍卡著

        Files.setLastModifiedTime(layout.manifestPath(id), FileTime.from(clock.instant()));
        clock.advance(Duration.ofDays(8));
        assertThat(a.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(b.finalizeWrite()).isInstanceOf(FinalizeResult.PendingConfirmation.class);
        assertThat(a.writingPath()).exists();
        assertThat(root.resolve(key08)).doesNotExist();

        releaseA.countDown(); // a 的 link 生效
        assertThat(finalizeUntilSettled(a)).isInstanceOf(FinalizeResult.Success.class);
        assertThat(root.resolve(key08)).hasBinaryContent(content);
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

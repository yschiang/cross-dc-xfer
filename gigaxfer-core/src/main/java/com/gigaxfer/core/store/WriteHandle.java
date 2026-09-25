package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.manifest.Manifest;
import com.gigaxfer.core.manifest.MalformedManifestException;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsException;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次寫入的生命週期：Writing → Finalize → Source Ready，或 Discard。
 *
 * <p>單執行緒使用，不可跨執行緒共用：lifecycle 有同步，但 digest、size 與 MessageDigest 沒有。
 */
public final class WriteHandle implements AutoCloseable {
    private static final int BUFFER = 64 * 1024;

    private enum Lifecycle {
        WRITING,
        FINALIZING,
        FINALIZED,
        DISCARDED,
        FAILED
    }

    private final LocalStore store;
    private final FileIdentity id;
    private final String dataClass;
    private final UUID uuid;
    private final Path writing;
    private final FileChannel channel;
    private final MessageDigest md = Sha256.newDigest();
    private final OutputStream stream;
    private long size;
    private String digest; // 第①步 fsync 後固定，之後不再改（IR-01）
    private volatile Lifecycle lifecycle = Lifecycle.WRITING;
    private volatile String failedOp;
    private volatile FinalizeResult.Failure failure; // Finalize 的終態結果；之後每次 finalizeWrite 原樣回
    private boolean linkOutcomeUnknown; // 本 handle 送出的 link-key 逾時、結果未知（D51 修 2）；單執行緒使用

    WriteHandle(LocalStore store, FileIdentity id, String dataClass, UUID uuid, Path writing, FileChannel channel) {
        this.store = store;
        this.id = id;
        this.dataClass = dataClass;
        this.uuid = uuid;
        this.writing = writing;
        this.channel = channel;
        // 檢查放在 Application 拿到的外層：小於緩衝的寫入不會碰到 ChannelStream，只在內層擋會讓
        // SUCCESS 後的位元組先進緩衝、下次 flush 再寫進已發布的 inode。
        this.stream = new java.io.BufferedOutputStream(new ChannelStream(), BUFFER) {
            @Override
            public synchronized void write(int b) throws IOException {
                requireWritable();
                super.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                requireWritable();
                super.write(b, off, len);
            }
        };
    }

    /** Finalize 一開始就凍結內容；守衛在 Application 拿到的外層 stream，連小寫入也不會進 buffer。 */
    private void requireWritable() throws IOException {
        switch (lifecycle) {
            case WRITING -> { }
            case FINALIZING -> throw new IOException("handle is finalizing; content is fixed");
            case FINALIZED -> throw new IOException("handle finalized; content is fixed at " + digest);
            case DISCARDED -> throw new IOException("handle discarded");
            case FAILED -> throw new IOException("handle failed: " + (failure != null ? failure.detail() : "stream failed at " + failedOp));
        }
    }

    public FileIdentity identity() {
        return id;
    }

    public Path writingPath() {
        return writing;
    }

    /**
     * Application 寫內容的串流；每次底層 write 經有界執行器。
     * 內部緩衝只在 {@link #finalizeWrite()} 時被 flush；{@link #close()} 不 flush。
     */
    public OutputStream stream() {
        return stream;
    }

    /**
     * 只允許 Finalize 前（CONTEXT.md Discard）。write 失敗（poisoned）或 Finalize 已回 Failure 的 handle 仍允許。
     * 刪除真正完成才進 DISCARDED；刪除只要沒有確定完成（池滿、timeout、EACCES、EIO、ESTALE 或任何例外），
     * handle 就中毒（finalizeWrite 只回 Failure、不發布），可重呼 discard 再刪一次。
     */
    public void discard() throws IOException {
        synchronized (stream) {
            if (lifecycle == Lifecycle.FINALIZING || lifecycle == Lifecycle.FINALIZED) {
                throw new IllegalStateException("cannot discard after finalize started");
            }
            if (lifecycle == Lifecycle.DISCARDED) return;
        }
        try {
            store.nfs.run("discard-writing", () -> {
                channel.close();
                Files.deleteIfExists(writing);
            });
        } catch (NfsException | IOException | RuntimeException | Error e) {
            // 任何「刪除沒有確定完成」（池滿、timeout、EACCES、EIO、ESTALE…）都讓 handle 中毒：
            // 停在 WRITING 的話，之後的 finalizeWrite 會發布 Application 已放棄的內容。
            synchronized (stream) {
                if (failure == null) {
                    failure = new FinalizeResult.Failure(FailureReason.IO, failedOp != null
                        ? "stream failed at " + failedOp
                        : "discard delete unresolved at discard-writing: " + e);
                }
                lifecycle = Lifecycle.FAILED;
            }
            if (e instanceof NfsException ne) throw new NfsUnavailableException(ne.op(), ne);
            if (e instanceof IOException io) throw io;
            if (e instanceof Error err) throw err;
            throw (RuntimeException) e;
        }
        lifecycle = Lifecycle.DISCARDED;
    }

    /**
     * D3（v2）+ D44 + D48 修 + D53 修：
     * ① fsync 暫存、固定 digest
     * ② 宣告：tmp manifest + fsync → link 成 &lt;key&gt;.manifest（EEXIST → 四項比對 → 沿用既有 content_path）
     *    → rediscovery：&lt;key&gt; 已在且 digest 符 → SUCCESS；不在且宣告超過 N → DECLARATION_EXPIRED
     * ③ link(.writing → content_path/&lt;key&gt;) = commit point
     * ④ best-effort 清暫存
     * ① 的 flush 屬於寫入：失敗或 timeout → handle 中毒，本次與之後都回 FAILURE(IO)。
     * 其餘步驟（fsync 起）timeout / pool 滿 → PENDING_CONFIRMATION；重呼走同一序列。
     *
     * <p>ponytail: 名為 finalizeWrite() 而非 finalize()——具體類別上 public 非 void 的
     * "finalize()" 是編譯錯誤（回傳型別不可替代 Object.finalize() 的 void）。
     */
    public FinalizeResult finalizeWrite() {
        synchronized (stream) {
            if (failure != null) return failure; // 終態結果不因之後的 discard() 改變
            if (lifecycle == Lifecycle.FAILED) return poisoned();
            if (lifecycle == Lifecycle.DISCARDED) {
                return new FinalizeResult.Failure(FailureReason.IO, "handle discarded");
            }
            if (lifecycle == Lifecycle.WRITING) lifecycle = Lifecycle.FINALIZING;
        }
        // 先前送出的 link（任一個）還沒結束：NAS 上的結果未定，任何結論（EXPIRED、暫存不在、CONFLICT 以外的 FAILURE）
        // 都可能被它稍後推翻。全部結束後照原序列重跑——那時 stat-key 看到的就是確定的結果。
        if (store.linkInFlight(id)) return new FinalizeResult.PendingConfirmation("link-key", "earlier link still in flight");
        try {
            // ①：digest != null 代表已 fsync 過，重呼時不再 flush（通道已關，flush 會丟 ClosedChannelException）。
            // force 單獨一個 op 且不關通道：fsync-writing timeout 後通道仍開著，重呼可以再 force 一次
            // （關通道的 thread 若被遺棄就再也 force 不了 → 永久 Failure）。耐久性由 force 保證，
            // close 只是還資源，失敗無所謂，所以走 best-effort。
            if (digest == null) {
                // flush 屬於寫入：失敗或 timeout 都已由 ChannelStream 毒化 handle → 直接 FAILURE，
                // 不先回 PendingConfirmation。commit point 從未被踩到，交易重來是安全的。
                try {
                    stream.flush();
                } catch (IOException e) {
                    return poisoned();
                }
                // 若上一次呼叫已經 close() 過（例如 PendingConfirmation 後 Application 用了
                // try-with-resources），channel 已關閉：重新在 writing 上開一個臨時 channel 來
                // force。POSIX fsync 對同一 inode 的任何 descriptor 都會把資料落盤，durability
                // 不變。writing 若已不存在，NoSuchFileException 原樣往上丟，走既有的
                // Failure(IO) 路徑（D51 修 2：來源已不存在）。
                store.nfs.run("fsync-writing", () -> {
                    if (channel.isOpen()) {
                        channel.force(true);
                    } else {
                        try (FileChannel c = FileChannel.open(writing, StandardOpenOption.WRITE)) {
                            c.force(true);
                        }
                    }
                });
                digest = Sha256.format(md); // md.digest() 會重設狀態，只能呼叫一次；此後 requireWritable() 拒寫
                // close 失敗（池滿／timeout）仍照常發布：channel 是 private，唯一的寫入者是 stream()，
                // 而 digest 已固定 → requireWritable() 拒絕一切後續寫入；也不會有更早的 write 還在飛——
                // write 一旦 timeout 就毒化 handle，永不走到這裡。把 close 失敗當成不可發布反而讓 hard mount
                // 卡住的 close 永久擋住發布，換不到任何安全性。
                bestEffort("close-writing", channel::close);
            }

            // ②
            Path manifestPath = store.layout.manifestPath(id);
            Path tmp = store.layout.manifestTmpPath(id, uuid);
            Instant declaredAt = store.clock.instant();
            Manifest mine = new Manifest(Manifest.SCHEMA_VERSION, id.sourceNode(), id.namespace(), dataClass, id.logicalKey(),
                size, digest, uuid.toString(), declaredAt, store.layout.expectedContentPath(id, dataClass, declaredAt));
            byte[] bytes = store.codec.encode(mine);
            store.nfs.run("write-manifest-tmp", () -> {
                Files.createDirectories(manifestPath.getParent());
                Files.deleteIfExists(tmp); // 重試時舊 tmp 可能半截；刪名字不影響已 link 的 manifest inode
                try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer b = ByteBuffer.wrap(bytes);
                    while (b.hasRemaining()) c.write(b);
                    c.force(true);
                }
            });

            Manifest declared;
            boolean preexisting = false; // 只有「先前就存在的宣告」才受年齡約束（D53 修）
            try {
                store.nfs.run("link-manifest", () -> Files.createLink(manifestPath, tmp));
                declared = mine;
            } catch (FileAlreadyExistsException e) {
                preexisting = true;
                declared = store.nfs.call("read-manifest", () -> readManifest(manifestPath));
                if (!declared.sameDeclaration(id, dataClass, size, digest)) {
                    return fail(FailureReason.CONFLICT,
                        "identity already declared with different content: " + declared.digest() + " size=" + declared.size());
                }
            }
            Path contentPath = store.layout.fromContentPath(declared.contentPath());

            // rediscovery：已發布？只有既有宣告才可能已發布——本次剛建立的宣告不會有 <key>，
            // 殘留情況仍由 ③ link-key 的 EEXIST 分支涵蓋。
            // Files.exists 會把讀取失敗吞成「不存在」，那會讓 EIO/ESTALE 被誤判成未發布（甚至 EXPIRED），
            // 所以用 readAttributes：只有 NoSuchFileException 算不存在，其餘 IOException 往外傳成 Failure(IO)。
            if (preexisting) {
                boolean published = store.nfs.call("stat-key", () -> {
                    try {
                        Files.readAttributes(contentPath, BasicFileAttributes.class);
                        return true;
                    } catch (NoSuchFileException e) {
                        return false;
                    }
                });
                if (published) {
                    return verifyPublished(contentPath, declared);
                }
                // 本 handle 先前送出的 link 已全部結束（進入前 linkInFlight 已確認），正式檔又確定不存在：
                // 那些 link 確定未生效，結果已知。之後的 I/O 錯誤是確定的 Failure；新 link 再逾時才重新標記未知。
                linkOutcomeUnknown = false;
            }

            // 需要再次嘗試發布：年齡只約束「再次嘗試一個早先的宣告」（D53 修）。
            // 本次呼叫剛剛建立的宣告永遠不算過期。
            // 年齡依既有 manifest 內的 source_ready_at（宣告時刻，Source 時鐘），不用 NAS mtime（D58 ③）。
            if (preexisting) {
                Instant priorDeclaredAt = declared.sourceReadyAt();
                if (Duration.between(priorDeclaredAt, store.clock.instant()).compareTo(LocalStore.DECLARATION_MAX_AGE) > 0) {
                    return fail(FailureReason.DECLARATION_EXPIRED, "declared at " + priorDeclaredAt + ", use a new logical key");
                }
            }

            // ③ commit point
            try {
                store.nfs.run("link-key", () -> {
                    Files.createDirectories(contentPath.getParent());
                    Files.createLink(contentPath, writing);
                });
            } catch (NfsTimeoutException e) {
                // 已送出、結果未知：保留 operation ownership 直到它真正結束（D51 修 2）。
                linkOutcomeUnknown = true;
                if (e.inFlight() != null) store.linkSent(id, e.inFlight());
                throw e;
            } catch (FileAlreadyExistsException e) {
                return verifyPublished(contentPath, declared);
            } catch (NoSuchFileException e) {
                // D51 修 2：link 尚未送出且來源已不存在（例如超 TTL 被清道夫刪）→ FAILURE
                return fail(FailureReason.IO, "writing file missing before link: " + writing);
            }

            // ④
            cleanupTemps();
            return success(declared.contentPath());

        } catch (NfsException e) {
            return new FinalizeResult.PendingConfirmation(e.op(),
                e instanceof NfsBusyException ? "nfs pool exhausted" : "nfs timeout");
        } catch (IOException e) {
            if (linkOutcomeUnknown) {
                // 本 handle 送出的 link 可能已發布（SR-04、D51 修 2、D53 修）：查證途中的 I/O 錯誤（stat-key、
                // read-manifest、digest-key…）不是結論，維持 PENDING、不清暫存，故障解除後重呼再查。
                return new FinalizeResult.PendingConfirmation("verify-link", e.toString());
            }
            return fail(FailureReason.IO, e.toString());
        }
    }

    /** 寫入（含 finalize 第①步的 flush）失敗過的 handle：內容不可信，永不發布。 */
    private FinalizeResult poisoned() {
        return fail(FailureReason.IO, "stream failed at " + failedOp);
    }

    /**
     * Failure 是終態：commit point 未踩到（或已被別人踩到），本 handle 永不發布。
     * 結果保存起來，之後的 finalizeWrite 原樣回，重試不會改變已回報的結論；discard 仍允許（README 規則 5）。
     */
    private FinalizeResult.Failure fail(FailureReason reason, String detail) {
        FinalizeResult.Failure f = new FinalizeResult.Failure(reason, detail);
        synchronized (stream) {
            lifecycle = Lifecycle.FAILED;
            failure = f;
        }
        cleanupTemps();
        return f;
    }

    /** 固定 schema 解碼後，再以本 store 的 PathLayout 時區驗證精確正式路徑。 */
    private Manifest readManifest(Path manifestPath) throws IOException {
        Manifest declared;
        try (InputStream in = Files.newInputStream(manifestPath)) {
            declared = store.codec.read(in); // 有上限，不整檔配置
        }
        String expected = store.layout.expectedContentPath(
            declared.identity(), declared.dataClass(), declared.sourceReadyAt());
        if (!expected.equals(declared.contentPath())) {
            throw new MalformedManifestException(
                "content_path " + declared.contentPath() + " != expected " + expected);
        }
        return declared;
    }

    /** 重試路徑的當下證據（D44）：重讀 &lt;key&gt; 算 digest 對 manifest。 */
    private FinalizeResult verifyPublished(Path contentPath, Manifest declared) throws NfsException, IOException {
        String actual = Sha256.ofFile(store.nfs, "digest-key", contentPath);
        if (!actual.equals(declared.digest())) {
            return fail(FailureReason.CONFLICT, "published content " + actual + " != declared " + declared.digest());
        }
        cleanupTemps();
        return success(declared.contentPath());
    }

    private FinalizeResult.Success success(String contentPath) {
        lifecycle = Lifecycle.FINALIZED;
        return new FinalizeResult.Success(id, contentPath);
    }

    private void cleanupTemps() {
        bestEffort("unlink-writing", () -> Files.deleteIfExists(writing));
        bestEffort("unlink-manifest-tmp", () -> Files.deleteIfExists(store.layout.manifestTmpPath(id, uuid)));
    }

    private void bestEffort(String op, com.gigaxfer.core.nfs.NfsExecutor.IoRunnable r) {
        try {
            store.nfs.run(op, r);
        } catch (Exception ignored) {
            // 清道夫兜底（D35）
        }
    }

    /**
     * 只關通道，不刪任何檔：PENDING_CONFIRMATION 後 Application 仍可重呼 finalizeWrite。
     *
     * <p>注意：關通道本身也經有界執行器，池滿／timeout 時丟 {@link NfsUnavailableException}，
     * 此時 fd 並未關閉（刻意不繞過執行器——繞過就等於在 hard mount 卡住時無界地占用呼叫端執行緒）。
     * fd 會在 JVM 結束或 handle 被 GC 時才還給 OS；呼叫端可稍後重呼 close()。
     */
    @Override
    public void close() throws IOException {
        try {
            store.nfs.run("close-writing", channel::close);
        } catch (NfsException e) {
            throw new NfsUnavailableException(e.op(), e);
        }
    }

    private final class ChannelStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            try {
                store.nfs.run("write", () -> {
                    while (buf.hasRemaining()) channel.write(buf);
                });
            } catch (NfsException e) {
                lifecycle = Lifecycle.FAILED;
                failedOp = e.op();
                throw new NfsUnavailableException(e.op(), e);
            } catch (Exception e) {
                // ENOSPC / EDQUOT / EIO / ESTALE 等一般 IOException 也必須毒化 handle：
                // md 與 size 沒更新，落盤位元組已與宣告不一致，不能讓 finalizeWrite() 回 Success。
                lifecycle = Lifecycle.FAILED;
                failedOp = "write";
                if (e instanceof IOException io) throw io;
                if (e instanceof RuntimeException re) throw re;
                throw new IOException("write failed", e);
            }
            md.update(b, off, len);
            size += len;
        }
    }

    UUID uuid() { return uuid; }
}

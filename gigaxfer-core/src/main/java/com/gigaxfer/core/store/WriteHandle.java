package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.manifest.Manifest;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsException;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 一次寫入的生命週期：Writing → Finalize → Source Ready，或 Discard。 */
public final class WriteHandle implements AutoCloseable {
    private static final int BUFFER = 64 * 1024;

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
    private boolean discarded;
    private boolean failed;
    private String failedOp;

    WriteHandle(LocalStore store, FileIdentity id, String dataClass, UUID uuid, Path writing, FileChannel channel) {
        this.store = store;
        this.id = id;
        this.dataClass = dataClass;
        this.uuid = uuid;
        this.writing = writing;
        this.channel = channel;
        this.stream = new java.io.BufferedOutputStream(new ChannelStream(), BUFFER);
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

    /** 只允許 Finalize 前（CONTEXT.md Discard）。write 失敗（poisoned）後仍允許。 */
    public void discard() throws IOException {
        if (digest != null) throw new IllegalStateException("cannot discard after finalize started");
        discarded = true;
        try {
            store.nfs.run("discard-writing", () -> {
                channel.close();
                Files.deleteIfExists(writing);
            });
        } catch (NfsException e) {
            throw new NfsUnavailableException(e.op(), e);
        }
    }

    /**
     * D3（v2）+ D44 + D48 修 + D53 修：
     * ① fsync 暫存、固定 digest
     * ② 宣告：tmp manifest + fsync → link 成 &lt;key&gt;.manifest（EEXIST → 四項比對 → 沿用既有 content_path）
     *    → rediscovery：&lt;key&gt; 已在且 digest 符 → SUCCESS；不在且宣告超過 N → DECLARATION_EXPIRED
     * ③ link(.writing → content_path/&lt;key&gt;) = commit point
     * ④ best-effort 清暫存
     * 任一步 timeout / pool 滿 → PENDING_CONFIRMATION；重呼走同一序列。
     *
     * <p>ponytail: 名為 finalizeWrite() 而非 finalize()——具體類別上 public 非 void 的
     * "finalize()" 是編譯錯誤（回傳型別不可替代 Object.finalize() 的 void）。
     */
    public FinalizeResult finalizeWrite() {
        if (failed) {
            cleanupTemps();
            return new FinalizeResult.Failure(FailureReason.IO, "stream failed at " + failedOp);
        }
        if (discarded) return new FinalizeResult.Failure(FailureReason.IO, "handle discarded");
        try {
            // ①：digest != null 代表已 fsync 過，重呼時不再 flush（通道已關，flush 會丟 ClosedChannelException）。
            // force 單獨一個 op 且不關通道：fsync-writing timeout 後通道仍開著，重呼可以再 force 一次
            // （關通道的 thread 若被遺棄就再也 force 不了 → 永久 Failure）。耐久性由 force 保證，
            // close 只是還資源，失敗無所謂，所以走 best-effort。
            if (digest == null) {
                stream.flush();
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
                digest = Sha256.format(md); // md.digest() 會重設狀態，只能呼叫一次
                bestEffort("close-writing", channel::close);
            }

            // ②
            Path manifestPath = store.layout.manifestPath(id);
            Path tmp = store.layout.manifestTmpPath(id, uuid);
            Instant declaredAt = store.clock.instant();
            Path myContentDir = store.layout.contentDir(id, dataClass, declaredAt);
            Manifest mine = new Manifest(Manifest.SCHEMA_VERSION, id.sourceNode(), id.namespace(), dataClass, id.logicalKey(),
                size, digest, uuid.toString(), declaredAt, store.layout.toContentPath(myContentDir.resolve(id.logicalKey())));
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
                declared = store.nfs.call("read-manifest", () -> store.codec.decode(Files.readAllBytes(manifestPath)));
                if (!declared.sameDeclaration(id, dataClass, size, digest)) {
                    cleanupTemps();
                    return new FinalizeResult.Failure(FailureReason.CONFLICT,
                        "identity already declared with different content: " + declared.digest() + " size=" + declared.size());
                }
            }
            bestEffort("unlink-manifest-tmp", () -> Files.deleteIfExists(tmp));

            Path contentPath = store.layout.fromContentPath(declared.contentPath());

            // rediscovery：已發布？
            if (store.nfs.call("stat-key", () -> Files.exists(contentPath))) {
                return verifyPublished(contentPath, declared);
            }

            // 需要再次嘗試發布：年齡只約束「再次嘗試一個早先的宣告」（D53 修）。
            // 本次呼叫剛剛建立的宣告永遠不算過期。
            if (preexisting) {
                Instant mtime = store.nfs.call("stat-manifest", () -> Files.getLastModifiedTime(manifestPath).toInstant());
                if (Duration.between(mtime, store.clock.instant()).compareTo(LocalStore.DECLARATION_MAX_AGE) > 0) {
                    cleanupTemps();
                    return new FinalizeResult.Failure(FailureReason.DECLARATION_EXPIRED, "declared at " + mtime + ", use a new logical key");
                }
            }

            // ③ commit point
            try {
                store.nfs.run("link-key", () -> {
                    Files.createDirectories(contentPath.getParent());
                    Files.createLink(contentPath, writing);
                });
            } catch (FileAlreadyExistsException e) {
                return verifyPublished(contentPath, declared);
            } catch (NoSuchFileException e) {
                // D51 修 2：link 尚未送出且來源已不存在（例如超 TTL 被清道夫刪）→ FAILURE
                cleanupTemps();
                return new FinalizeResult.Failure(FailureReason.IO, "writing file missing before link: " + writing);
            }

            // ④
            cleanupTemps();
            return new FinalizeResult.Success(id, declared.contentPath());

        } catch (NfsException e) {
            return new FinalizeResult.PendingConfirmation(e.op(),
                e instanceof NfsBusyException ? "nfs pool exhausted" : "nfs timeout");
        } catch (NfsUnavailableException e) {
            // ① 的 stream.flush() 踩到 busy/timeout：結果未知，不等於失敗（SR-04）。PendingConfirmation 不清暫存
            return new FinalizeResult.PendingConfirmation(e.op(),
                e.getCause() instanceof NfsTimeoutException ? "nfs timeout" : "nfs pool exhausted");
        } catch (IOException e) {
            cleanupTemps();
            return new FinalizeResult.Failure(FailureReason.IO, e.toString());
        }
    }

    /** 重試路徑的當下證據（D44）：重讀 &lt;key&gt; 算 digest 對 manifest。 */
    private FinalizeResult verifyPublished(Path contentPath, Manifest declared) throws NfsException, IOException {
        String actual = store.nfs.call("digest-key", () -> Sha256.ofFile(contentPath));
        cleanupTemps();
        if (!actual.equals(declared.digest())) {
            return new FinalizeResult.Failure(FailureReason.CONFLICT, "published content " + actual + " != declared " + declared.digest());
        }
        return new FinalizeResult.Success(id, declared.contentPath());
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

    /** 只關通道，不刪任何檔：PENDING_CONFIRMATION 後 Application 仍可重呼 finalizeWrite。 */
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
            if (discarded) throw new IOException("handle discarded");
            if (failed) throw new IOException("handle failed");
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            try {
                store.nfs.run("write", () -> {
                    while (buf.hasRemaining()) channel.write(buf);
                });
            } catch (NfsException e) {
                failed = true;
                failedOp = e.op();
                throw new NfsUnavailableException(e.op(), e);
            }
            md.update(b, off, len);
            size += len;
        }
    }

    UUID uuid() { return uuid; }
}

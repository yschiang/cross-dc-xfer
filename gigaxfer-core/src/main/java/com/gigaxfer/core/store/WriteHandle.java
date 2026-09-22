package com.gigaxfer.core.store;

import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.nfs.NfsException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

    // ponytail: named finalizeWrite(), not finalize() — a public, non-void
    // "finalize()" on a concrete class is a compile error (return type not
    // substitutable for Object.finalize()'s void). Task 8 implements the body.
    public FinalizeResult finalizeWrite() {
        if (failed) return new FinalizeResult.Failure(FailureReason.IO, "stream failed at " + failedOp);
        throw new UnsupportedOperationException("Task 8");
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

    // 供 Task 8 使用
    String dataClass() { return dataClass; }
    UUID uuid() { return uuid; }
    FileChannel channel() { return channel; }
    MessageDigest md() { return md; }
    long size() { return size; }
    String digest() { return digest; }
    void digest(String d) { this.digest = d; }
}

package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.ManifestCodec;
import com.gigaxfer.core.nfs.NfsBusyException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Application 端 Storage Access contract 的核心（SR-01、D23）。 */
public final class LocalStore {
    /** v1 固定常數（D30 修 5、D53 修）：宣告後超過此年齡不得再次嘗試發布。 */
    public static final Duration DECLARATION_MAX_AGE = Duration.ofDays(7);

    final String sourceNode;
    final PathLayout layout;
    final NfsExecutor nfs;
    final Clock clock;
    final ManifestCodec codec = new ManifestCodec();
    private final WriteGate gate;

    public LocalStore(String sourceNode, PathLayout layout, NfsExecutor nfs, WriteGate gate, Clock clock) {
        this.sourceNode = sourceNode;
        this.layout = layout;
        this.nfs = nfs;
        this.gate = gate;
        this.clock = clock;
    }

    public WriteHandle beginWrite(String namespace, String dataClass, String logicalKey) throws WriteRejectedException {
        FileIdentity id = new FileIdentity(sourceNode, namespace, logicalKey); // 命名違約在碰 NFS 前就丟 IllegalArgumentException
        Optional<String> reject = gate.rejectReason(namespace, dataClass);
        if (reject.isPresent()) throw new WriteRejectedException(WriteRejectedException.Reason.REJECTED, reject.get());

        UUID uuid = UUID.randomUUID();
        Path dir = layout.contentDir(id, dataClass, clock.instant());
        Path writing = layout.writingPath(dir, id, uuid);
        try {
            FileChannel channel = nfs.call("open-writing", () -> {
                Files.createDirectories(dir);
                return FileChannel.open(writing, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            });
            return new WriteHandle(this, id, dataClass, uuid, writing, channel);
        } catch (NfsBusyException | NfsTimeoutException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.UNAVAILABLE, e.getMessage());
        } catch (IOException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.IO, e.toString());
        }
    }
}

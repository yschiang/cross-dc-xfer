package com.gigaxfer.core.store;

import com.gigaxfer.core.identity.FileIdentity;
import com.gigaxfer.core.layout.PathLayout;
import com.gigaxfer.core.manifest.ManifestCodec;
import com.gigaxfer.core.nfs.NfsException;
import com.gigaxfer.core.nfs.NfsExecutor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/** Application 端 Storage Access contract 的核心（SR-01、D23）。 */
public final class LocalStore {
    /** v1 固定常數（D30 修 5、D53 修）：宣告後超過此年齡不得再次嘗試發布。 */
    public static final Duration DECLARATION_MAX_AGE = Duration.ofDays(7);

    final String sourceNode;
    final PathLayout layout;
    final NfsExecutor nfs;
    final Clock clock;
    final ManifestCodec codec = new ManifestCodec();
    /**
     * link-key timeout 後仍可能在執行的 link（D51 修 2、SR-05）。結束前同 identity 的任何 finalizeWrite
     * 都只回 PENDING_CONFIRMATION，不判年齡、不刪暫存、不送新 link。
     * ponytail: 以 LocalStore 實例為範圍——同一 mount 在同一 process 開多個 LocalStore 時彼此看不到；
     * 跨 process 不需要（process 死了它的 link 也不會再生效）。
     */
    final Map<FileIdentity, Future<?>> linksInFlight = new ConcurrentHashMap<>();
    private final WriteGate gate;

    public LocalStore(String sourceNode, PathLayout layout, NfsExecutor nfs, WriteGate gate, Clock clock) {
        this.sourceNode = Objects.requireNonNull(sourceNode, "sourceNode");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.nfs = Objects.requireNonNull(nfs, "nfs");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WriteHandle beginWrite(String namespace, String dataClass, String logicalKey) throws WriteRejectedException {
        // 命名違約在碰 NFS 前就丟 IllegalArgumentException。dataClass 同樣是原始路徑片段
        // （contentDir 直接 resolve 它），沒檢查的話 "../.." 會逃出 namespace 樹。
        FileIdentity id = new FileIdentity(sourceNode, namespace, logicalKey);
        FileIdentity.requireSegment(dataClass, "dataClass");
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
        } catch (NfsException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.UNAVAILABLE, e.getMessage(), e);
        } catch (IOException e) {
            throw new WriteRejectedException(WriteRejectedException.Reason.IO, e.toString(), e);
        }
    }
}

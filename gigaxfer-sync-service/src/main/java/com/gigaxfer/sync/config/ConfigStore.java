package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.InvalidConfigException;
import com.gigaxfer.core.config.NodeConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * candidate.json → active.json → lkg.json 啟用協定（D17、D45、spec §13）。
 * 只在啟動時呼叫一次；設定 process 內不可變。
 *
 * <p>順序：讀 active（讀不到或不合法都視為缺，用 lkg 並計失敗）→ 有 candidate 則讀一次 bytes 並驗證
 * （schema、版本遞增、policy 相同、fixed 相同）→ 通過：把<b>驗證過的 bytes</b> 寫成 active.json.tmp 並 fsync、
 * active→lkg、tmp→active（皆 ATOMIC_MOVE），最後 candidate 仍是同一份 bytes 才刪。
 * candidate 讀不到、驗證不過、或安裝途中任何 I/O 失敗，都算「candidate 被拒」：留 candidate、記原因、
 * 用磁碟上仍生效的那份繼續啟動。
 *
 * <p>不搬 candidate 檔本身：CD 以 tmp→rename 發布，可能正好落在「讀完 candidate」與「安裝」之間；
 * 搬檔會讓那份未驗證的版本成為 active（D17 繞過）。現在被換上的新 candidate 只會留給下次啟動重驗。
 *
 * <p>crash 復原：active→lkg 後、tmp→active 前 crash → active 缺、lkg = 舊 active、candidate 仍在，
 * 下次以 lkg 為基準重驗即收斂；tmp→active 後、刪 candidate 前 crash → candidate 與 active 同 bytes，
 * 下次啟動直接補刪、不算失敗。
 */
public final class ConfigStore {
    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    public static final String CANDIDATE = "candidate.json";
    public static final String ACTIVE = "active.json";
    public static final String LKG = "lkg.json";
    static final String ACTIVE_TMP = "active.json.tmp";

    private final Path dir;
    private final Runnable afterValidation;

    public ConfigStore(Path dir) {
        this(dir, () -> { });
    }

    /** 測試用：在驗證完成、安裝開始前執行，用來排出 CD 換檔的時序。 */
    ConfigStore(Path dir, Runnable afterValidation) {
        this.dir = Objects.requireNonNull(dir);
        this.afterValidation = Objects.requireNonNull(afterValidation);
    }

    public ConfigActivation load() throws ConfigUnavailableException {
        Path candidate = dir.resolve(CANDIDATE);
        Path active = dir.resolve(ACTIVE);
        Path lkg = dir.resolve(LKG);

        Optional<String> failure = Optional.empty();
        Optional<NodeConfig> current = read(active);
        if (current.isEmpty()) {
            failure = Optional.of(Files.exists(active)
                ? "active.json unreadable, using lkg"
                : "active.json missing, using lkg");
        }
        Optional<NodeConfig> baseline = current.isPresent() ? current : read(lkg);
        if (baseline.isEmpty()) {
            throw new ConfigUnavailableException("no valid active or lkg config; initialise active.json first");
        }

        if (Files.exists(candidate)) {
            byte[] bytes;
            NodeConfig accepted;
            try {
                bytes = Files.readAllBytes(candidate);
                if (current.isPresent() && Arrays.equals(bytes, Files.readAllBytes(active))) {
                    // 上次啟動已安裝這份 bytes，只差刪 candidate 就 crash：補刪，不算失敗。
                    Files.deleteIfExists(candidate);
                    return new ConfigActivation(current.get(), ConfigActivation.Source.ACTIVE, failure);
                }
                accepted = validateCandidate(bytes, baseline.get());
            } catch (IOException e) { // 含 InvalidConfigException：讀不到與驗證不過同樣是「被拒」
                return rejected(current, baseline.get(), active, "candidate.json rejected, left in place: " + e.getMessage());
            }
            afterValidation.run();
            try {
                install(bytes, current.isPresent(), active, lkg);
            } catch (IOException e) {
                return rejected(current, baseline.get(), active, "candidate.json activation failed, left in place: " + e);
            }
            removeCandidateIfUnchanged(candidate, bytes);
            log.info("activated config version {} (previous {})", accepted.version(), baseline.get().version());
            // failure 保留 active 損毀／缺失的信號（D45：用 lkg 並計失敗），即使 candidate 啟用成功。
            return new ConfigActivation(accepted, ConfigActivation.Source.ACTIVE, failure);
        }

        if (current.isPresent()) {
            return new ConfigActivation(current.get(), ConfigActivation.Source.ACTIVE, failure);
        }
        log.warn("active.json missing or unreadable, running on lkg.json version {}", baseline.get().version());
        return new ConfigActivation(baseline.get(), ConfigActivation.Source.LKG, failure);
    }

    /** candidate 被拒：跑磁碟上仍生效的那份（active 還在就是 active，否則是 lkg，內容都等於 baseline）。 */
    private static ConfigActivation rejected(Optional<NodeConfig> current, NodeConfig baseline, Path active, String reason) {
        log.warn(reason);
        ConfigActivation.Source source = current.isPresent() && Files.exists(active)
            ? ConfigActivation.Source.ACTIVE : ConfigActivation.Source.LKG;
        return new ConfigActivation(baseline, source, Optional.of(reason));
    }

    private void install(byte[] bytes, boolean rotateActive, Path active, Path lkg) throws IOException {
        Path tmp = dir.resolve(ACTIVE_TMP); // 殘留的 tmp 在下次安裝時被截斷覆寫
        try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) c.write(b);
            c.force(true);
        }
        if (rotateActive) {
            Files.move(active, lkg, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmp, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 只刪剛驗證的那份；CD 已換上更新的 candidate 就留給下次啟動重驗。
     * ponytail: 比對與刪除之間 CD 再換檔，那份新 candidate 會被刪掉（遺失一次發布，不會未驗證就生效）；
     * 要收掉這個窗口需 CD 與 sync service 協調鎖，v1 不做。
     */
    private static void removeCandidateIfUnchanged(Path candidate, byte[] validated) {
        try {
            if (Arrays.equals(Files.readAllBytes(candidate), validated)) {
                Files.delete(candidate);
            } else {
                log.warn("candidate.json changed during activation; kept for validation on next start");
            }
        } catch (IOException e) {
            // 啟用已完成；殘留的同 bytes candidate 下次啟動會被補刪。
            log.warn("could not remove activated candidate.json: {}", e.toString());
        }
    }

    /** 通過則回已解碼的 candidate；拒絕原因以 InvalidConfigException 帶出。 */
    private static NodeConfig validateCandidate(byte[] bytes, NodeConfig baseline) throws IOException {
        NodeConfig c;
        try {
            c = ConfigCodec.decode(bytes);
        } catch (InvalidConfigException e) {
            throw new InvalidConfigException("candidate invalid: " + e.getMessage(), e);
        }
        if (c.version() <= baseline.version()) {
            throw new InvalidConfigException(
                "candidate version " + c.version() + " is not greater than current version " + baseline.version());
        }
        if (!c.policy().equals(baseline.policy())) {
            throw new InvalidConfigException(
                "candidate policy segment differs from current version " + baseline.version() + " (policy is immutable in v1)");
        }
        return c;
    }

    /** 讀不到（權限、I/O 錯誤）與內容不合法同樣視為缺，讓呼叫端退回 lkg（D45）。 */
    private static Optional<NodeConfig> read(Path p) {
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ConfigCodec.decode(Files.readAllBytes(p)));
        } catch (IOException e) {
            log.error("{} is unreadable or not a valid config: {}", p, e.toString());
            return Optional.empty();
        }
    }
}

package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.InvalidConfigException;
import com.gigaxfer.core.config.NodeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * candidate.json → active.json → lkg.json 啟用協定（D17、D45、spec §13）。
 * 只在啟動時呼叫一次；設定 process 內不可變。
 * 順序：讀 active（壞則視為缺）→ 有 candidate 則驗證（schema、版本遞增、policy 相同、fixed 相同）
 * → 通過：active→lkg、candidate→active（皆 ATOMIC_MOVE）→ 失敗：留 candidate、記原因 → active 缺則用 lkg。
 * crash 於兩次 rename 之間：active 缺、lkg = 舊 active、candidate 仍在；下次啟動以 lkg 為基準重驗 candidate 即可收斂。
 */
public final class ConfigStore {
    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    public static final String CANDIDATE = "candidate.json";
    public static final String ACTIVE = "active.json";
    public static final String LKG = "lkg.json";

    private final Path dir;

    public ConfigStore(Path dir) {
        this.dir = Objects.requireNonNull(dir);
    }

    public ConfigActivation load() throws ConfigUnavailableException, IOException {
        Path candidate = dir.resolve(CANDIDATE);
        Path active = dir.resolve(ACTIVE);
        Path lkg = dir.resolve(LKG);

        Optional<String> failure = Optional.empty();
        Optional<NodeConfig> current = read(active);
        if (current.isEmpty()) {
            failure = Files.exists(active)
                ? Optional.of("active.json unreadable, using lkg")
                : Optional.of("active.json missing, using lkg");
        }
        Optional<NodeConfig> baseline = current.isPresent() ? current : read(lkg);

        if (baseline.isEmpty()) {
            throw new ConfigUnavailableException("no valid active or lkg config; initialise active.json first");
        }

        if (Files.exists(candidate)) {
            try {
                // 驗證與啟用用同一份解碼結果：不重讀檔案，CD 在中間換掉 candidate 也繞不過 D17。
                NodeConfig accepted = validateCandidate(candidate, baseline.orElseThrow());
                if (current.isPresent()) {
                    Files.move(active, lkg, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(candidate, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("activated config version {} (previous {})", accepted.version(), baseline.orElseThrow().version());
                // failure 保留 active 損毀／缺失的信號（D45：用 lkg 並計失敗），即使 candidate 啟用成功。
                return new ConfigActivation(accepted, ConfigActivation.Source.ACTIVE, failure);
            } catch (InvalidConfigException e) {
                log.warn("candidate.json rejected, left in place: {}", e.getMessage());
                failure = Optional.of(e.getMessage());
            }
        }

        if (current.isPresent()) {
            return new ConfigActivation(current.get(), ConfigActivation.Source.ACTIVE, failure);
        }
        // current 缺 → baseline 就是 lkg，且已在上面確認非空。
        NodeConfig fromLkg = baseline.orElseThrow();
        log.warn("active.json missing or unreadable, running on lkg.json version {}", fromLkg.version());
        return new ConfigActivation(fromLkg, ConfigActivation.Source.LKG, failure);
    }

    /** 通過則回已解碼的 candidate；拒絕原因以 InvalidConfigException 帶出。呼叫點已保證 baseline 非空。 */
    private static NodeConfig validateCandidate(Path candidate, NodeConfig baseline) throws IOException {
        NodeConfig c;
        try {
            c = ConfigCodec.decode(Files.readAllBytes(candidate));
        } catch (InvalidConfigException e) {
            throw new InvalidConfigException("candidate invalid: " + e.getMessage(), e);
        }
        if (c.version() <= baseline.version()) {
            throw new InvalidConfigException("candidate version " + c.version() + " is not greater than current version " + baseline.version());
        }
        if (!c.policy().equals(baseline.policy())) {
            throw new InvalidConfigException("candidate policy segment differs from current version " + baseline.version() + " (policy is immutable in v1)");
        }
        return c;
    }

    private static Optional<NodeConfig> read(Path p) throws IOException {
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ConfigCodec.decode(Files.readAllBytes(p)));
        } catch (InvalidConfigException e) {
            log.error("{} is not a valid config: {}", p, e.getMessage());
            return Optional.empty();
        }
    }
}

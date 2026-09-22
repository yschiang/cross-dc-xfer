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
        ConfigActivation.Source source = current.isPresent() ? ConfigActivation.Source.ACTIVE : ConfigActivation.Source.LKG;
        Optional<NodeConfig> baseline = current.isPresent() ? current : read(lkg);

        if (baseline.isEmpty()) {
            throw new ConfigUnavailableException("no valid active or lkg config; initialise active.json first");
        }

        if (Files.exists(candidate)) {
            String reason = validateCandidate(candidate, baseline.orElseThrow());
            if (reason == null) {
                NodeConfig accepted;
                try {
                    accepted = ConfigCodec.decode(Files.readAllBytes(candidate));
                } catch (InvalidConfigException e) {
                    throw new IllegalStateException("candidate validated a moment ago", e);
                }
                if (current.isPresent()) {
                    Files.move(active, lkg, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(candidate, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("activated config version {} (previous {})", accepted.version(), baseline.orElseThrow().version());
                return new ConfigActivation(accepted, ConfigActivation.Source.ACTIVE, Optional.empty());
            }
            log.warn("candidate.json rejected, left in place: {}", reason);
            failure = Optional.of(reason);
        }

        if (current.isPresent()) {
            return new ConfigActivation(current.get(), source, failure);
        }
        // current 缺 → baseline 就是 lkg，且已在上面確認非空。
        NodeConfig fromLkg = baseline.orElseThrow();
        log.warn("active.json missing or unreadable, running on lkg.json version {}", fromLkg.version());
        return new ConfigActivation(fromLkg, ConfigActivation.Source.LKG, failure);
    }

    /** 回 null = 通過；否則為拒絕原因。呼叫點已保證 baseline 非空。 */
    private static String validateCandidate(Path candidate, NodeConfig baseline) throws IOException {
        NodeConfig c;
        try {
            c = ConfigCodec.decode(Files.readAllBytes(candidate));
        } catch (InvalidConfigException e) {
            return "candidate invalid: " + e.getMessage();
        }
        if (c.version() <= baseline.version()) {
            return "candidate version " + c.version() + " is not greater than current version " + baseline.version();
        }
        if (!c.policy().equals(baseline.policy())) {
            return "candidate policy segment differs from current version " + baseline.version() + " (policy is immutable in v1)";
        }
        return null;
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

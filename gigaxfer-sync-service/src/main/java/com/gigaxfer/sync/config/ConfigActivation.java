package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.NodeConfig;
import java.util.Objects;
import java.util.Optional;

/** 啟動時一次載入的結果：生效設定、來源、以及本次 candidate 啟用失敗原因（有則 activation_failure_count +1）。 */
public record ConfigActivation(NodeConfig config, Source source, Optional<String> activationFailure) {
    public enum Source { ACTIVE, LKG }

    public ConfigActivation {
        Objects.requireNonNull(config);
        Objects.requireNonNull(source);
        Objects.requireNonNull(activationFailure);
    }
}

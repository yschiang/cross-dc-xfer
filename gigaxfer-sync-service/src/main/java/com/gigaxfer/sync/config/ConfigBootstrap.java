package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.sync.SyncProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 啟動序列第一步（D34）：讀一次設定，之後 process 內不可變（D45）。
 * 失敗即讓 context 啟動失敗（D17：active 與 lkg 皆缺拒絕啟動）；systemd 會重啟，但沒有設定就沒有可服務的東西。
 */
@Configuration
public class ConfigBootstrap {

    @Bean
    ConfigActivation configActivation(SyncProperties props) throws ConfigUnavailableException, IOException {
        if (props.configDir() == null) {
            throw new ConfigUnavailableException("gigaxfer.config-dir is not set");
        }
        return new ConfigStore(props.configDir()).load();
    }

    @Bean
    NodeConfig nodeConfig(ConfigActivation activation, SyncProperties props) throws ConfigUnavailableException {
        NodeConfig c = activation.config();
        if (props.node() == null || !c.policy().nodes().contains(props.node())) {
            throw new ConfigUnavailableException("gigaxfer.node '" + props.node() + "' is not in policy.nodes " + c.policy().nodes());
        }
        return c;
    }

    @Bean
    ConfigMetrics configMetrics(MeterRegistry registry, ConfigActivation activation, NodeConfig config) {
        Gauge.builder("active_config_version", config, c -> (double) c.version())
            .description("version of the config this process is running on (D17)")
            .register(registry);
        Gauge.builder("activation_failure_count", activation, a -> a.activationFailure().isPresent() ? 1.0 : 0.0)
            .description("1 if a candidate.json was rejected at this start (D45)")
            .register(registry);
        return new ConfigMetrics();
    }

    /** 標記 bean，讓指標註冊有明確的生命週期。 */
    public static final class ConfigMetrics {}
}

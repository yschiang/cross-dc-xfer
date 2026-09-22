package com.gigaxfer.sync.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.gigaxfer.sync.SyncTestSupport;
import com.gigaxfer.sync.db.DbState;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 獨立 context（見 SyncTestSupport）：確保沒有其他測試先打過 /actuator/health/* 讓 gauge 意外已被填值。 */
class HealthGaugeTest extends SyncTestSupport {
    @Autowired DbState db;
    @Autowired MeterRegistry meters;

    @Test
    void gauges_reflect_state_without_prior_health_request() throws Exception {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();

        assertThat(meters.get("db_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);

        Path root = nfsRoot();
        Path moved = root.resolveSibling("nfs-gone-gauge");
        Files.move(root, moved);
        try {
            assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(0.0);
        } finally {
            Files.move(moved, root);
        }
    }
}

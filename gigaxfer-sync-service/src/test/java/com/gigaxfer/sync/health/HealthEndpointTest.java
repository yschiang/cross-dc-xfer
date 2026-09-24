package com.gigaxfer.sync.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.sync.SyncTestSupport;
import com.gigaxfer.sync.db.DbState;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HealthEndpointTest extends SyncTestSupport {
    @Autowired MockMvc mvc;
    @Autowired DbState db;
    @Autowired MeterRegistry meters;

    @Test
    void readiness_is_up_when_db_migrated_and_nfs_root_reachable() throws Exception {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"))
            .andExpect(jsonPath("$.components.nfs.status").value("UP"));
        assertThat(meters.get("db_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void nfs_component_is_down_when_root_disappears_and_recovers() throws Exception {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        Path root = nfsRoot();
        Path moved = root.resolveSibling("nfs-gone");
        Files.move(root, moved);
        try {
            mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.nfs.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
            assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(0.0);
        } finally {
            Files.move(moved, root);
        }
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.nfs.status").value("UP"));
    }

    @Test
    void liveness_does_not_depend_on_db_or_nfs() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}

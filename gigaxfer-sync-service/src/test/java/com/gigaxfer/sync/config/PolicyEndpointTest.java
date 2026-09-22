package com.gigaxfer.sync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.sync.SyncTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PolicyEndpointTest extends SyncTestSupport {
    @Autowired MockMvc mvc;
    @Autowired NodeConfig config;
    @Autowired MeterRegistry meters;

    @Test
    void active_config_is_the_fixture() {
        assertThat(config.version()).isEqualTo(3L);
        assertThat(config.policy().nodes()).contains("P1");
    }

    @Test
    void policy_endpoint_returns_version_and_normalised_policy_without_auth() throws Exception {
        mvc.perform(get("/policy"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.policy.deployment").value("example-deployment"))
            .andExpect(jsonPath("$.policy.nodes[0]").value("P1"))
            .andExpect(jsonPath("$.policy.namespaces[0]").value("analytics"))
            .andExpect(jsonPath("$.policy.namespaces[1]").value("transactions"))
            .andExpect(jsonPath("$.policy.required_targets[0].source_node").value("P1"))
            .andExpect(jsonPath("$.policy.required_targets[0].data_class").value("local-only"))
            .andExpect(jsonPath("$.policy.required_targets[1].targets[1]").value("P3"))
            .andExpect(jsonPath("$.operational").doesNotExist())
            .andExpect(jsonPath("$.policy.peer_token_sha256").doesNotExist());
    }

    @Test
    void config_metrics_are_registered_with_node_tag() {
        assertThat(meters.get("active_config_version").tag("node", "P1").gauge().value()).isEqualTo(3.0);
        assertThat(meters.get("activation_failure_count").tag("node", "P1").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void config_is_immutable_while_process_runs() throws Exception {
        java.nio.file.Path active = configDir().resolve("active.json");
        String original = java.nio.file.Files.readString(active);
        java.nio.file.Files.writeString(active, original.replace("\"version\": 3", "\"version\": 9"));
        try {
            mvc.perform(get("/policy")).andExpect(jsonPath("$.version").value(3));
            assertThat(meters.get("active_config_version").tag("node", "P1").gauge().value()).isEqualTo(3.0);
        } finally {
            java.nio.file.Files.writeString(active, original);
        }
    }
}

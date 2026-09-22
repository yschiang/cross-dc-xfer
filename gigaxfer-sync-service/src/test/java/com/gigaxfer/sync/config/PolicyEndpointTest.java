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
            .andExpect(jsonPath("$.policy.required_targets[0].source_node").value("P1"))
            .andExpect(jsonPath("$.policy.required_targets[0].data_class").value("local-only"))
            .andExpect(jsonPath("$.policy.required_targets[1].targets[1]").value("P3"));
    }

    @Test
    void config_metrics_are_registered_with_node_tag() {
        assertThat(meters.get("active_config_version").tag("node", "P1").gauge().value()).isEqualTo(3.0);
        assertThat(meters.get("activation_failure_count").tag("node", "P1").gauge().value()).isEqualTo(0.0);
    }
}

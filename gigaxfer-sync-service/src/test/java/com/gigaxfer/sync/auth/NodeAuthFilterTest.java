package com.gigaxfer.sync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.sync.SyncTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(EchoCallerController.class)
class NodeAuthFilterTest extends SyncTestSupport {
    @Autowired MockMvc mvc;
    @Autowired OwnToken own;

    @Test
    void own_token_is_read_from_secret_file_and_trimmed() {
        assertThat(own.value()).isEqualTo(P1_TOKEN);
    }

    @Test
    void missing_authorization_is_401() throws Exception {
        mvc.perform(get("/pending")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknown_token_is_401() throws Exception {
        mvc.perform(get("/pending").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void non_bearer_scheme_is_401() throws Exception {
        mvc.perform(get("/pending").header("Authorization", "Basic " + P2_TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    void known_token_resolves_caller_node() throws Exception {
        mvc.perform(get("/pending").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caller").value("P2"));
    }

    @Test
    void target_param_equal_to_caller_is_allowed() throws Exception {
        mvc.perform(get("/pending").param("target", "P2").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isOk());
    }

    @Test
    void target_param_different_from_caller_is_403() throws Exception {
        mvc.perform(get("/pending").param("target", "P3").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isForbidden());
    }

    @Test
    void node_internal_endpoints_need_no_token() throws Exception {
        mvc.perform(get("/policy")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void protected_prefixes_cover_file_subpaths() throws Exception {
        mvc.perform(get("/file/P1/mes/k1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/received")).andExpect(status().isUnauthorized());
        mvc.perform(get("/report")).andExpect(status().isUnauthorized());
    }

    @Test
    void received_does_not_apply_target_equals_caller_rule() throws Exception {
        mvc.perform(get("/received").param("target", "P3").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caller").value("P2"));
    }
}

package com.gigaxfer.sync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    void lowercase_bearer_scheme_is_accepted() throws Exception {
        mvc.perform(get("/pending").header("Authorization", "bearer " + P2_TOKEN)).andExpect(status().isOk());
    }

    @Test
    void unauthorized_response_carries_www_authenticate_and_forbidden_hides_node_names() throws Exception {
        mvc.perform(get("/pending")).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"));
        String body = mvc.perform(get("/pending").param("target", "P3").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("P2").doesNotContain("P3");
    }

    /** P02-11、D14 修 2：三個 Target 端點的 target 取自認證身分；不帶或相同 → 以呼叫者為 Target，不同 → 403。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"GET, /pending", "GET, /file/P1/mes/k1", "POST, /report"})
    void target_endpoints_take_target_from_caller_identity(String method, String path) throws Exception {
        org.springframework.http.HttpMethod m = org.springframework.http.HttpMethod.valueOf(method);
        mvc.perform(request(m, path).header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.caller").value("P2"));
        mvc.perform(request(m, path).param("target", "P2").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.caller").value("P2"));
        mvc.perform(request(m, path).param("target", "P3").header("Authorization", "Bearer " + P2_TOKEN))
            .andExpect(status().isForbidden());
    }
}

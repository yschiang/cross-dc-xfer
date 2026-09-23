package com.gigaxfer.sync.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.sync.SyncTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P02-12：README「正式服務現在能驗的」認證範例，以不載入測試 controller 的正式產物 context 驗證回應碼。
 * P02 尚無 Node 間端點 handler：通過認證後是 404（不是 401）；P04 加上端點後此測試要改成 200。
 */
@AutoConfigureMockMvc
class ProductionAuthResponsesTest extends SyncTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void readme_auth_examples_match_production_artifact() throws Exception {
        mvc.perform(get("/pending")).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/pending").header("Authorization", "Bearer not-a-node-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/pending").header("Authorization", "Bearer " + P2_TOKEN)).andExpect(status().isNotFound());
        mvc.perform(get("/policy")).andExpect(status().isOk());
    }
}

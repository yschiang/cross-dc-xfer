package com.gigaxfer.sync.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.core.nfs.NfsTimeoutException;
import com.gigaxfer.sync.SyncTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** nfs probe timeout → DOWN，detail op = 原始呼叫的操作名（D51）。 */
@AutoConfigureMockMvc
@Import(NfsTimeoutHealthTest.TimeoutNfsConfig.class)
class NfsTimeoutHealthTest extends SyncTestSupport {
    @Autowired MockMvc mvc;

    @TestConfiguration
    static class TimeoutNfsConfig {
        @Bean
        @Primary
        NfsExecutor timeoutNfsExecutor() {
            return new NfsExecutor() {
                @Override
                public <T> T call(String op, IoCallable<T> body) throws NfsTimeoutException {
                    throw new NfsTimeoutException("stat-root");
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @Test
    void nfs_component_is_down_when_probe_times_out() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.components.nfs.status").value("DOWN"))
            .andExpect(jsonPath("$.components.nfs.details.op").value("stat-root"));
    }
}

package com.gigaxfer.sync.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.sync.SyncTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** P02-08：DB 不可用時 process 不退出、/policy 可回應；DB 恢復後不重啟即就緒（D34 修）。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "gigaxfer.db-retry-millis=200")
@Import(DbOutageRecoveryTest.FlakyDataSourceConfig.class)
class DbOutageRecoveryTest extends SyncTestSupport {
    @Autowired DbState db;
    @Autowired MockMvc mvc;

    @TestConfiguration
    static class FlakyDataSourceConfig {
        static final AtomicBoolean down = new AtomicBoolean(true);

        @Bean
        @Primary
        DataSource flakyDataSource(DataSourceProperties props) {
            DataSource real = props.initializeDataSourceBuilder().build();
            return new DelegatingDataSource(real) {
                @Override
                public Connection getConnection() throws SQLException {
                    if (down.get()) {
                        throw new SQLException("simulated outage");
                    }
                    return super.getConnection();
                }

                @Override
                public Connection getConnection(String u, String p) throws SQLException {
                    if (down.get()) {
                        throw new SQLException("simulated outage");
                    }
                    return super.getConnection(u, p);
                }
            };
        }
    }

    @Test
    void db_becomes_ready_without_restart_after_outage() throws Exception {
        Thread.sleep(600); // ≥ 2 個 200 ms 重試週期
        assertThat(db.ready()).isFalse();
        assertThat(db.lastError()).isPresent();
        mvc.perform(get("/policy")).andExpect(status().isOk());
        FlakyDataSourceConfig.down.set(false);
        assertThat(db.awaitReady(Duration.ofSeconds(10))).isTrue();
        assertThat(db.lastError()).isEmpty();
    }
}

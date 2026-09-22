package com.gigaxfer.sync.health;

import com.gigaxfer.sync.db.DbState;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** db：migration 未完成 → DOWN（帶最後錯誤）；完成後以 SELECT 1 FROM DUAL 探測連線。取代 Boot 內建的 DataSourceHealthIndicator。 */
@Component("db")
public class DbHealthIndicator implements HealthIndicator {
    private final DbState state;
    private final JdbcTemplate jdbc;
    private final HealthMetrics metrics;

    public DbHealthIndicator(DbState state, DataSource ds, HealthMetrics metrics) {
        this.state = state;
        this.jdbc = new JdbcTemplate(ds);
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        if (!state.ready()) {
            metrics.db(false);
            return Health.down().withDetail("reason", "migration not complete")
                .withDetail("lastError", state.lastError().orElse("")).build();
        }
        try {
            jdbc.queryForObject("SELECT 1 FROM DUAL", Integer.class);
            metrics.db(true);
            return Health.up().build();
        } catch (RuntimeException e) {
            metrics.db(false);
            return Health.down(e).build();
        }
    }
}

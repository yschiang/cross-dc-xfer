package com.gigaxfer.sync.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** D58 ⑦：寫入與讀回在不同 JVM 預設時區下都是同一個時刻，DB 裡存的是 UTC 牆上時間。 */
class DbTimeTest {
    private final TimeZone original = TimeZone.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    void round_trips_across_jvm_time_zones_and_stores_utc_wall_clock() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:dbtime;MODE=Oracle;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "sa", ""));
        jdbc.execute("CREATE TABLE t (id INT PRIMARY KEY, at TIMESTAMP)");
        // 2026-03-08 02:30 在 America/New_York 不存在（夏令時間跳過）；以 UTC 儲存時仍須原樣往返。
        Instant inGap = Instant.parse("2026-03-08T02:30:00Z");
        Instant plain = Instant.parse("2026-09-20T07:30:00Z");

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        jdbc.update("INSERT INTO t VALUES (1, ?)", DbTime.utc(inGap));
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
        jdbc.update("INSERT INTO t VALUES (2, ?)", DbTime.utc(plain));
        jdbc.update("INSERT INTO t VALUES (3, ?)", DbTime.utc(null));

        assertThat(jdbc.queryForObject("SELECT CAST(at AS VARCHAR) FROM t WHERE id = 1", String.class))
            .startsWith("2026-03-08 02:30:00");
        assertThat(jdbc.queryForObject("SELECT CAST(at AS VARCHAR) FROM t WHERE id = 2", String.class))
            .startsWith("2026-09-20 07:30:00");

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        RowMapper<Instant> at = (rs, n) -> DbTime.read(rs, "at");
        assertThat(jdbc.queryForObject("SELECT at FROM t WHERE id = 1", at)).isEqualTo(inGap);
        assertThat(jdbc.queryForObject("SELECT at FROM t WHERE id = 2", at)).isEqualTo(plain);
        assertThat(jdbc.queryForObject("SELECT at FROM t WHERE id = 3", at)).isNull();
    }
}

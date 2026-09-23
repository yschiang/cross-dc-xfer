package com.gigaxfer.sync.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P02-08：首次 V1 migration 在 DDL 中途斷線（Oracle／H2 的 DDL 不可回滾，會留下半套物件）後，
 * 同一個 DbState 的下一次重試必須自動接續到 ready，不刪任何既存物件或資料。
 */
class MigrationResumeTest {
    private static final List<String> TABLES = List.of("file_identity", "obligation", "received", "rebuild_progress",
        "node_meta", "seq_counter", "inspection", "ops_audit", "target_control", "obligation_history");

    /** 讓第一句含 {@code failOn} 的 DDL 丟一次連線錯誤，模擬 migration 途中 DB 斷線。 */
    private static DataSource dropOnce(String db, String failOn) {
        DataSource real = DataSourceBuilder.create()
            .url("jdbc:h2:mem:" + db + ";MODE=Oracle;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")
            .username("sa").password("").build();
        AtomicBoolean armed = new AtomicBoolean(true);
        return new DelegatingDataSource(real) {
            @Override
            public Connection getConnection() throws SQLException {
                return (Connection) wrap(super.getConnection(), Connection.class, failOn, armed);
            }
        };
    }

    private static Object wrap(Object target, Class<?> iface, String failOn, AtomicBoolean armed) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, (p, m, args) -> {
            if (target instanceof Statement && m.getName().startsWith("execute") && args != null && args.length > 0
                && args[0] instanceof String sql && sql.contains(failOn) && armed.getAndSet(false)) {
                throw new SQLException("simulated: connection reset during migration", "08006");
            }
            try {
                Object r = m.invoke(target, args);
                return r instanceof Statement s && !(r instanceof java.sql.PreparedStatement)
                    ? wrap(s, Statement.class, failOn, armed) : r;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private static DbState state(DataSource ds) {
        return new DbBootstrap().dbState(ds, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private static void assertFullSchema(JdbcTemplate jdbc) {
        for (String t : TABLES) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE 1 = 0", Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = 'ix_history_ack_kind'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void ddl_failure_mid_v1_resumes_on_next_attempt_and_keeps_existing_rows() {
        DataSource ds = dropOnce("resume-failed-record", "CREATE TABLE obligation");
        DbState state = state(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        assertThatThrownBy(state::runOnce).isInstanceOf(RuntimeException.class);
        assertThat(state.ready()).isFalse();
        // 斷線前已建的表留下；放一列資料，接續後必須還在
        jdbc.update("INSERT INTO file_identity (source_node, namespace, logical_key, data_class, size_bytes, digest, "
            + "source_ready_at, content_path) VALUES ('P1','mes','keep','lot-log',1,'sha256:" + "0".repeat(64)
            + "',CURRENT_TIMESTAMP,'P1/mes/lot-log/2026-09-20/02/keep')");

        state.runOnce();

        assertThat(state.ready()).isTrue();
        assertFullSchema(jdbc);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_identity WHERE logical_key = 'keep'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_meta", Integer.class)).isEqualTo(1);
    }

    /** 連線斷在 Flyway 寫失敗紀錄之前：沒有任何紀錄，重跑 V1 時已存在的物件逐句跳過。 */
    @Test
    void v1_rerun_without_history_record_skips_existing_objects() {
        DataSource ds = dropOnce("resume-no-record", "CREATE TABLE inspection");
        DbState state = state(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        assertThatThrownBy(state::runOnce).isInstanceOf(RuntimeException.class);
        jdbc.update("DELETE FROM \"flyway_schema_history\"");

        state.runOnce();

        assertThat(state.ready()).isTrue();
        assertFullSchema(jdbc);
    }
}

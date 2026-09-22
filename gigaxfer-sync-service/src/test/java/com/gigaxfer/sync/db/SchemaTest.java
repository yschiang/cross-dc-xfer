package com.gigaxfer.sync.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.gigaxfer.sync.SyncTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaTest extends SyncTestSupport {
    @Autowired DbState db;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migration_runs_in_background_and_creates_all_tables() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        List<String> tables = jdbc.queryForList(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class)
            .stream().map(String::toLowerCase).sorted().toList();
        assertThat(tables).contains(
            "file_identity", "obligation", "received", "rebuild_progress", "node_meta",
            "seq_counter", "inspection", "ops_audit", "target_control", "obligation_history");
    }

    @Test
    void node_meta_has_one_incarnation_and_seq_counters_start_at_zero() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        List<Map<String, Object>> meta = jdbc.queryForList("SELECT incarnation, rebuild_in_progress FROM node_meta");
        assertThat(meta).hasSize(1);
        assertThat(meta.get(0).get("incarnation").toString()).hasSize(36);
        assertThat(((Number) meta.get(0).get("rebuild_in_progress")).intValue()).isZero();
        List<Map<String, Object>> seq = jdbc.queryForList("SELECT name, last FROM seq_counter ORDER BY name");
        assertThat(seq).extracting(m -> m.get("name")).containsExactly("change", "completed");
        assertThat(seq).allSatisfy(m -> assertThat(((Number) m.get("last")).longValue()).isZero());
    }

    @Test
    void bootstrap_is_idempotent_across_restarts() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        String before = jdbc.queryForObject("SELECT incarnation FROM node_meta", String.class);
        jdbc.update("UPDATE seq_counter SET last=42 WHERE name='completed'");
        jdbc.update("UPDATE node_meta SET rebuild_in_progress=1");
        try {
            db.runOnce();
            assertThat(jdbc.queryForObject("SELECT incarnation FROM node_meta", String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT last FROM seq_counter WHERE name='completed'", Long.class)).isEqualTo(42L);
            assertThat(jdbc.queryForObject("SELECT rebuild_in_progress FROM node_meta", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seq_counter", Integer.class)).isEqualTo(2);
        } finally {
            jdbc.update("UPDATE seq_counter SET last=0 WHERE name='completed'");
            jdbc.update("UPDATE node_meta SET rebuild_in_progress=0");
        }
    }

    @Test
    void obligation_state_check_constraint_rejects_unknown_state() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        jdbc.update("INSERT INTO file_identity (source_node, namespace, logical_key, data_class, size, digest, source_ready_at, content_path) "
            + "VALUES ('P1','mes','k1','lot-log',1,'sha256:" + "0".repeat(64) + "',CURRENT_TIMESTAMP,'P1/mes/lot-log/2026-09-20/02/k1')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO obligation (source_node, namespace, logical_key, target_node, state, epoch, attempts) "
            + "VALUES ('P1','mes','k1','P2','BOGUS',1,0)"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM file_identity WHERE logical_key = 'k1'");
    }

    @Test
    void remote_received_keeps_source_selected_path_without_local_source_row() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        String path = "P2/transactions/lot-log/2026-09-20/02/remote-path-test";
        jdbc.update("INSERT INTO received (source_node, namespace, logical_key, data_class, content_path, size, digest, "
            + "source_ready_at, valid, incarnation, epoch, report_pending, recovery_pending, change_seq) "
            + "VALUES ('P2','transactions','remote-path-test','lot-log',?,1,?,CURRENT_TIMESTAMP,1,?,1,0,0,999)",
            path, "sha256:" + "0".repeat(64), "00000000-0000-0000-0000-000000000002");
        try {
            assertThat(jdbc.queryForObject("SELECT content_path FROM received WHERE logical_key='remote-path-test'", String.class))
                .isEqualTo(path);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_identity WHERE logical_key='remote-path-test'", Integer.class))
                .isZero();
        } finally {
            jdbc.update("DELETE FROM received WHERE logical_key='remote-path-test'");
        }
    }
}

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

    /**
     * P02-07：再次 bootstrap（同一 DB 上重跑 migration + bootstrap，即重啟時做的事）保留既有 incarnation、
     * 兩個非零計數器、rebuild／recovery 旗標、恢復進度、控制狀態、義務與歷史資料，且不重複建列。
     */
    @Test
    void bootstrap_is_idempotent_across_restarts() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        String before = jdbc.queryForObject("SELECT incarnation FROM node_meta", String.class);
        String digest = "sha256:" + "0".repeat(64);
        jdbc.update("UPDATE seq_counter SET last=42 WHERE name='completed'");
        jdbc.update("UPDATE seq_counter SET last=77 WHERE name='change'");
        jdbc.update("UPDATE node_meta SET rebuild_in_progress=1");
        jdbc.update("INSERT INTO file_identity (source_node, namespace, logical_key, data_class, size_bytes, digest, "
            + "source_ready_at, content_path) VALUES ('P1','mes','idem','lot-log',1,?,CURRENT_TIMESTAMP,"
            + "'P1/mes/lot-log/2026-09-20/02/idem')", digest);
        jdbc.update("INSERT INTO obligation (source_node, namespace, logical_key, target_node, state, epoch, attempts, completed_seq) "
            + "VALUES ('P1','mes','idem','P2','COMPLETED',3,2,42)");
        jdbc.update("INSERT INTO obligation_history (history_id, source_node, namespace, logical_key, target_node, kind, "
            + "event_id, epoch_after, at, acknowledged) VALUES ('h-idem','P1','mes','idem','P2','LOST','e-idem',3,CURRENT_TIMESTAMP,0)");
        jdbc.update("INSERT INTO received (source_node, namespace, logical_key, data_class, content_path, size_bytes, digest, "
            + "source_ready_at, valid, incarnation, epoch, report_pending, recovery_pending, change_seq) "
            + "VALUES ('P2','mes','idem','lot-log','P2/mes/lot-log/2026-09-20/02/idem',1,?,CURRENT_TIMESTAMP,1,?,3,1,1,77)",
            digest, "00000000-0000-0000-0000-000000000002");
        jdbc.update("INSERT INTO rebuild_progress (source_node, incarnation, cursor_seq) VALUES ('P2',?,55)",
            "00000000-0000-0000-0000-000000000002");
        jdbc.update("INSERT INTO target_control (target_node, paused, paused_by, reason) VALUES ('P3',1,'ops','maint')");
        try {
            db.runOnce();
            assertThat(jdbc.queryForObject("SELECT incarnation FROM node_meta", String.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM node_meta", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last FROM seq_counter WHERE name='completed'", Long.class)).isEqualTo(42L);
            assertThat(jdbc.queryForObject("SELECT last FROM seq_counter WHERE name='change'", Long.class)).isEqualTo(77L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seq_counter", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT rebuild_in_progress FROM node_meta", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT report_pending, recovery_pending, change_seq FROM received WHERE logical_key='idem'"))
                .extractingByKeys("report_pending", "recovery_pending", "change_seq")
                .map(v -> ((Number) v).longValue()).containsExactly(1L, 1L, 77L);
            assertThat(jdbc.queryForObject("SELECT cursor_seq FROM rebuild_progress WHERE source_node='P2'", Long.class)).isEqualTo(55L);
            assertThat(jdbc.queryForObject("SELECT paused FROM target_control WHERE target_node='P3'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT state, epoch, completed_seq FROM obligation WHERE logical_key='idem'"))
                .extractingByKeys("state", "epoch", "completed_seq")
                .map(v -> v instanceof Number n ? (Object) n.longValue() : v).containsExactly("COMPLETED", 3L, 42L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obligation_history WHERE event_id='e-idem'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM file_identity WHERE logical_key='idem'", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM target_control WHERE target_node='P3'");
            jdbc.update("DELETE FROM rebuild_progress WHERE source_node='P2'");
            jdbc.update("DELETE FROM received WHERE logical_key='idem'");
            jdbc.update("DELETE FROM obligation_history WHERE history_id='h-idem'");
            jdbc.update("DELETE FROM obligation WHERE logical_key='idem'");
            jdbc.update("DELETE FROM file_identity WHERE logical_key='idem'");
            jdbc.update("UPDATE seq_counter SET last=0");
            jdbc.update("UPDATE node_meta SET rebuild_in_progress=0");
        }
    }

    /** P02-07「必要索引」：V1 的九個索引全部建立。 */
    @Test
    void all_v1_indexes_exist() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        List<String> names = jdbc.queryForList(
            "SELECT LOWER(INDEX_NAME) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class);
        assertThat(names).contains(
            "ix_obligation_target_state_next", "ix_obligation_target_completed_seq", "ux_received_change_seq",
            "ix_received_report_pending", "ix_inspection_finished_at", "ix_ops_audit_at", "ux_history_event_id",
            "ix_history_identity_target", "ix_history_ack_kind");
    }

    @Test
    void obligation_state_check_constraint_rejects_unknown_state() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        jdbc.update("INSERT INTO file_identity (source_node, namespace, logical_key, data_class, size_bytes, digest, source_ready_at, content_path) "
            + "VALUES ('P1','mes','k1','lot-log',1,'sha256:" + "0".repeat(64) + "',CURRENT_TIMESTAMP,'P1/mes/lot-log/2026-09-20/02/k1')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO obligation (source_node, namespace, logical_key, target_node, state, epoch, attempts) "
            + "VALUES ('P1','mes','k1','P2','BOGUS',1,0)"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM file_identity WHERE logical_key = 'k1'");
    }

    /** 欄寬與 core 的片段上限一致：邊界長度寫得進去，超一格就被 DB 拒絕。 */
    @Test
    void identity_column_widths_match_core_segment_limits() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        String key512 = "w".repeat(com.gigaxfer.core.identity.FileIdentity.MAX_LOGICAL_KEY_BYTES);
        String sql = "INSERT INTO file_identity (source_node, namespace, logical_key, data_class, size_bytes, digest, source_ready_at, content_path) "
            + "VALUES ('P1','mes',?,'lot-log',1,'sha256:" + "0".repeat(64) + "',CURRENT_TIMESTAMP,?)";
        jdbc.update(sql, key512, "P1/mes/lot-log/2026-09-20/02/" + key512);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, key512 + "x", "P1/mes/lot-log/2026-09-20/02/" + key512 + "x"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM file_identity WHERE logical_key = ?", key512);
    }

    @Test
    void remote_received_keeps_source_selected_path_without_local_source_row() {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        String path = "P2/transactions/lot-log/2026-09-20/02/remote-path-test";
        jdbc.update("INSERT INTO received (source_node, namespace, logical_key, data_class, content_path, size_bytes, digest, "
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

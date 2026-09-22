-- gigaxfer sync-service schema V1 (system-design §3, D24 修, D29 修 11, D33 修 3, D54, D55, D56)
-- 可攜 SQL：Oracle 與 H2 MODE=Oracle 皆可執行。布林用 NUMERIC(1)。

CREATE TABLE file_identity (
  source_node     VARCHAR(64)   NOT NULL,
  namespace       VARCHAR(128)  NOT NULL,
  logical_key     VARCHAR(512)  NOT NULL,
  data_class      VARCHAR(128)  NOT NULL,
  size_bytes      NUMERIC(19)   NOT NULL,
  digest          VARCHAR(71)   NOT NULL,
  source_ready_at TIMESTAMP     NOT NULL,
  content_path    VARCHAR(1024) NOT NULL,
  CONSTRAINT pk_file_identity PRIMARY KEY (source_node, namespace, logical_key)
);

CREATE TABLE obligation (
  source_node     VARCHAR(64)   NOT NULL,
  namespace       VARCHAR(128)  NOT NULL,
  logical_key     VARCHAR(512)  NOT NULL,
  target_node     VARCHAR(64)   NOT NULL,
  state           VARCHAR(16)   NOT NULL,
  epoch           NUMERIC(10)   NOT NULL,
  attempts        NUMERIC(10)   NOT NULL,
  next_attempt_at TIMESTAMP,
  last_error      VARCHAR(1024),
  completed_at    TIMESTAMP,
  completed_seq   NUMERIC(19),
  CONSTRAINT pk_obligation PRIMARY KEY (source_node, namespace, logical_key, target_node),
  CONSTRAINT fk_obligation_identity FOREIGN KEY (source_node, namespace, logical_key)
    REFERENCES file_identity (source_node, namespace, logical_key),
  CONSTRAINT ck_obligation_state CHECK (state IN ('PENDING', 'QUARANTINED', 'COMPLETED'))
);
CREATE INDEX ix_obligation_target_state_next ON obligation (target_node, state, next_attempt_at);
CREATE INDEX ix_obligation_target_completed_seq ON obligation (target_node, completed_seq);

CREATE TABLE received (
  source_node      VARCHAR(64)   NOT NULL,
  namespace        VARCHAR(128)  NOT NULL,
  logical_key      VARCHAR(512)  NOT NULL,
  data_class       VARCHAR(128)  NOT NULL,
  content_path     VARCHAR(1024) NOT NULL,
  size_bytes       NUMERIC(19)   NOT NULL,
  digest           VARCHAR(71)   NOT NULL,
  source_ready_at  TIMESTAMP     NOT NULL,
  published_at     TIMESTAMP,
  valid            NUMERIC(1)    NOT NULL,
  invalid_reason   VARCHAR(16),
  observed_digest  VARCHAR(71),
  event_id         VARCHAR(36),
  incarnation      VARCHAR(36)   NOT NULL,
  epoch            NUMERIC(10)   NOT NULL,
  report_pending   NUMERIC(1)    NOT NULL,
  recovery_pending NUMERIC(1)    NOT NULL,
  change_seq       NUMERIC(19)   NOT NULL,
  CONSTRAINT pk_received PRIMARY KEY (source_node, namespace, logical_key),
  CONSTRAINT ck_received_valid CHECK (valid IN (0, 1)),
  CONSTRAINT ck_received_report_pending CHECK (report_pending IN (0, 1)),
  CONSTRAINT ck_received_recovery_pending CHECK (recovery_pending IN (0, 1)),
  CONSTRAINT ck_received_invalid_reason CHECK (invalid_reason IS NULL OR invalid_reason IN ('LOST', 'CORRUPT'))
);
CREATE UNIQUE INDEX ux_received_change_seq ON received (change_seq);
CREATE INDEX ix_received_report_pending ON received (report_pending);

CREATE TABLE rebuild_progress (
  source_node   VARCHAR(64)  NOT NULL,
  incarnation   VARCHAR(36)  NOT NULL,
  cursor_seq    NUMERIC(19)  NOT NULL,
  caught_up_at  TIMESTAMP,
  CONSTRAINT pk_rebuild_progress PRIMARY KEY (source_node)
);

CREATE TABLE node_meta (
  singleton            NUMERIC(1)  NOT NULL,
  incarnation          VARCHAR(36) NOT NULL,
  rebuild_in_progress  NUMERIC(1)  NOT NULL,
  CONSTRAINT pk_node_meta PRIMARY KEY (singleton),
  CONSTRAINT ck_node_meta_singleton CHECK (singleton = 1),
  CONSTRAINT ck_node_meta_rebuild CHECK (rebuild_in_progress IN (0, 1))
);

CREATE TABLE seq_counter (
  name  VARCHAR(16)  NOT NULL,
  last  NUMERIC(19)  NOT NULL,
  CONSTRAINT pk_seq_counter PRIMARY KEY (name),
  CONSTRAINT ck_seq_counter_name CHECK (name IN ('completed', 'change'))
);

CREATE TABLE inspection (
  inspection_id VARCHAR(36)  NOT NULL,
  scope         VARCHAR(64)  NOT NULL,
  cutoff        TIMESTAMP,
  started_at    TIMESTAMP    NOT NULL,
  finished_at   TIMESTAMP,
  checked       NUMERIC(19)  NOT NULL,
  unknown_count NUMERIC(19)  NOT NULL,
  diffs         NUMERIC(19)  NOT NULL,
  repairs       NUMERIC(19)  NOT NULL,
  CONSTRAINT pk_inspection PRIMARY KEY (inspection_id)
);
CREATE INDEX ix_inspection_finished_at ON inspection (finished_at);

CREATE TABLE ops_audit (
  audit_id  VARCHAR(36)   NOT NULL,
  who       VARCHAR(128)  NOT NULL,
  at        TIMESTAMP     NOT NULL,
  action    VARCHAR(32)   NOT NULL,
  scope     VARCHAR(1024),
  reason    VARCHAR(1024),
  result    VARCHAR(1024),
  CONSTRAINT pk_ops_audit PRIMARY KEY (audit_id)
);
CREATE INDEX ix_ops_audit_at ON ops_audit (at);

CREATE TABLE target_control (
  target_node VARCHAR(64)   NOT NULL,
  paused      NUMERIC(1)    NOT NULL,
  paused_by   VARCHAR(128),
  paused_at   TIMESTAMP,
  reason      VARCHAR(1024),
  CONSTRAINT pk_target_control PRIMARY KEY (target_node),
  CONSTRAINT ck_target_control_paused CHECK (paused IN (0, 1))
);

CREATE TABLE obligation_history (
  history_id      VARCHAR(36)  NOT NULL,
  source_node     VARCHAR(64)  NOT NULL,
  namespace       VARCHAR(128) NOT NULL,
  logical_key     VARCHAR(512) NOT NULL,
  target_node     VARCHAR(64)  NOT NULL,
  kind            VARCHAR(24)  NOT NULL,
  event_id        VARCHAR(36),
  epoch_after     NUMERIC(10),
  expected_digest VARCHAR(71),
  observed_digest VARCHAR(71),
  at              TIMESTAMP    NOT NULL,
  acknowledged    NUMERIC(1)   NOT NULL,
  CONSTRAINT pk_obligation_history PRIMARY KEY (history_id),
  CONSTRAINT ck_history_kind CHECK (kind IN ('LOST', 'CORRUPT', 'REOPEN', 'RELEASE', 'UNRECOVERABLE', 'INTEGRITY_FAILURE', 'IDENTITY_CONFLICT')),
  CONSTRAINT ck_history_ack CHECK (acknowledged IN (0, 1))
);
CREATE UNIQUE INDEX ux_history_event_id ON obligation_history (event_id);
CREATE INDEX ix_history_identity_target ON obligation_history (source_node, namespace, logical_key, target_node);
CREATE INDEX ix_history_ack_kind ON obligation_history (acknowledged, kind);

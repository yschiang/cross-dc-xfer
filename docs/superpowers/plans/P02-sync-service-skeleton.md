# P02 — sync-service 骨架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 沿用既有成果並完成 `gigaxfer-sync-service` 模組的可啟動骨架：config 資料模型與驗證（放 core）、candidate → active → lkg 啟用協定、Flyway 全表 schema、每 Node token 認證 filter、Actuator `/health` readiness（DB、NFS）、`GET /policy`；DB 不可用時不退出、health 回 DOWN。

**Architecture:** Spring Boot 3.x app，process 無狀態（D34）。設定物件啟動時讀一次、process 內不可變（D45）；啟動序列 = 讀 active.json → 起 HTTP → 背景重試 DB migration（D34 修）。Node 間端點以 `Authorization: Bearer <token>` 認證，伺服端只持有各 Node token 的 sha256（D14 修 2）。本計畫不做掃描、傳輸、對帳；只交付後續 P03–P09 掛上去的骨架。

**Tech Stack:** Java 21、Maven 多模組、Spring Boot 3.4.4（`spring-boot-starter-web`、`-actuator`、`-jdbc`、`micrometer-registry-prometheus`）、Flyway 10（`flyway-core` + `flyway-database-oracle`）、H2 Oracle mode（測試）、ojdbc11（runtime）、JUnit 5、AssertJ、MockMvc。

**Ticket:** [P02：Node 本地同步服務基礎 #1](https://github.com/yschiang/cross-dc-xfer/issues/1)（依既有實作補建）。

**Spec:** `docs/design/system-design.md` §3（持久化）、§4（Control Plane 與設定）、§8（v1 固定常數）、§9；`docs/design/design-decisions.md` D9、D14 修 2、D17、D18、D24 修、D30、D30 修 5、D30 修 6、D34、D34 修、D45、D29 修 11（seq_counter）、D55 (5)（node_meta）；`docs/design/file-inventory.md`「sync 主機本機磁碟」；`docs/spec.md` §11–§13（AC-CP-01～03、AC-CFG-01～05）；`CONTEXT.md`（Policy、Required targets、Data class）。

## 本輪接續基準

本票在 P02 已開始實作後補建。這份修訂保留原本七個 tasks，補齊 Namespace 契約並回收已完成的修正；不是要求從 Task 1 全部重做。

- 基準：`p02-sync-service-skeleton` 的 `0f3f916`，上游 P01 為 `86360ac`。本輪只準備文件，不修改該工作分支的程式碼或執行中 plan。
- 本檔是後續交接的 P02 計畫修訂稿。套用前先讓當前執行者完成正在處理的 task，再比較最新 HEAD／ledger，把已完成的修正保留下來；接續時使用同一份合併後的計畫。
- Task 1 基本模型與 Task 2 設定啟用已完成；Task 3 已有程式碼、當時審查中。Task 4–7 依最新 ledger 決定續行點，不憑本表覆寫進度。
- 本輪新增工作：Task 1 的 Namespace 登錄與查詢、通用 `deployment` 識別欄位與測試；Task 3 的 Policy 輸出；Task 4 的 Target 路徑持久化；Task 7 的契約交接與驗收證據。
- 分支既有 Java package 與 Maven module 路徑在下文如實保留；本輪不搬 module、不重命名整個程式庫。

| Ticket 驗收能力 | 計畫 | 下游交接 |
| --- | --- | --- |
| 有效設定可啟動、無效設定保留有效版本 | Task 1–3 | immutable `NodeConfig`、啟用結果 |
| Namespace 與 Data class 可判定是否登錄 | Task 1、3 | P03 掃描範圍；P10 WriteGate 使用 `Policy.allowsWrite` |
| DB 初始化可重試且不重設既有 incarnation／序號 | Task 4 | P03–P08 schema 與 `DbState` |
| readiness 反映 DB／NAS、liveness 不因儲存故障失效 | Task 5 | 後續排程與端點的就緒閘門 |
| Node 憑證解析出唯一身分 | Task 6 | P04 handlers 使用 `CallerIdentity` |
| 可重現的功能驗收與 PR 交接 | Task 7 | 測試命令、版本、環境與限制 |

P02 不實作 `/locate`、掃描、傳輸與 ops 業務動作；`/locate` 的 server／client 交付歸 P10，讀取所需資料依賴 P03／P05，恢復進度整合依 P07／P08。P04 負責端點的義務授權與 `/received` Source 過濾；P02 提供可測的認證介面，不能用測試 echo endpoint 宣稱 P04 已完成。

## Global Constraints

- 所有候選 plan 細節以 spec／system-design 現行契約為準。配置模型在 core，供 sync-service 與 library 共用。
- Namespace 為 Policy 的明確集合；沒有登錄即不允許 write，空 targets 表示已登錄且僅本地使用。
- 本輪定義尚未部署的 v1 初始化格式；不把既有運行中的 active.json 靜默補上預設 Namespace，不提供 Policy 線上遷移。
- Java 21；`gigaxfer-core` 仍不得依賴 Spring 或任何 DB driver；config 資料模型與驗證放 `gigaxfer-core`（P00 roadmap 模組表），sync-service 與日後 library 共用。
- Spring Boot 3.4.4 為基準；implementer 不得升到 4.x。不引入 Spring Security、JPA、Hibernate；DB 存取只用 `JdbcTemplate` / 標準 SQL（D9）。
- SQL 可攜：DDL 只用 `VARCHAR(n)`、`NUMERIC(p[,s])`、`TIMESTAMP`、`CHECK`、`PRIMARY KEY`、`UNIQUE`、`CREATE INDEX`；不用 `VARCHAR2`、`BOOLEAN`、`IDENTITY`、`SEQUENCE`、`SYS_GUID()`。布林用 `NUMERIC(1)` + CHECK。測試用 H2 `MODE=Oracle`（D9）。
- 設定物件 process 內不可變、不熱載入；啟用 = 驗證 → rename → 使用（D45）。候選設定驗證四條：schema 合法、`version` 嚴格遞增、`policy` 段與現行（active，缺則 lkg）**完全相同**、`fixed` 段若存在必須等於 v1 固定常數（D17、D30 修 5、D30 修 6）。任一不符 → 留 candidate 原地、`activation_failure_count` +1、用現行設定。
- active 與 lkg 皆缺 → 拒絕啟動（D17）；DB 連不上 → 不退出、`/actuator/health` 回 DOWN、背景每 5 s 重試（D34 修）。
- v1 固定常數（D30 修 5）：`declaration_max_age_days=7`、`abandoned_ttl_hours=24`、`cleaner_interval_hours=24`、`full_reconcile_hours=6`、`search_window_days=30`、`late_publish_tolerance_days=19`、`clock_margin_days=1`。程式碼中為常數，不從設定讀。
- Node 間端點（`/pending`、`/file/**`、`/report`、`/received`）一律需認證；驗證端只持有各 Node token 的 sha256 hex（D14 修 2）。本 Node 自己的明文 token 由 `gigaxfer.token-file` 指定的本機秘密檔提供，不進 git、不進 config（file-inventory）。Node 內端點（`/policy`、`/locate`、`/actuator/**`）不認證。
- 指標名稱固定（monitoring.md）：`active_config_version{node}`、`activation_failure_count{node}`、`storage_health{node}`、`db_health{node}`；標籤 `node` = `gigaxfer.node`。
- 每個 task 以 `mvn -q test`（根目錄，兩模組）綠燈結束並 commit；commit 作者與協作者記錄實際執行者，不預填模型署名。
- 每個 shell 先執行：`export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH`（arm64 Mac，`/usr/local` 工具鏈為 x86_64 不可用）。

## 設定檔格式（本計畫定義，後續計畫沿用）

`configs/v<N>.json`（git）與 sync 主機的 `candidate.json` / `active.json` / `lkg.json` 同一格式，snake_case，未知欄位拒絕：

```json
{
  "schema_version": 1,
  "version": 3,
  "published_by": "alice",
  "published_at": "2026-09-20T02:00:00Z",
  "policy": {
    "deployment": "example-deployment",
    "nodes": ["P1", "P2", "P3"],
    "namespaces": ["transactions", "analytics"],
    "required_targets": [
      { "source_node": "P1", "data_class": "lot-log", "targets": ["P2", "P3"] },
      { "source_node": "P1", "data_class": "local-only", "targets": [] },
      { "source_node": "P2", "data_class": "lot-log", "targets": ["P1"] }
    ]
  },
  "operational": {
    "scan_interval_seconds": 10,
    "poll_interval_seconds": 5,
    "pending_limit": 200,
    "transfer_timeout_base_seconds": 30,
    "transfer_timeout_bytes_per_second": 1048576,
    "target_concurrency": 4,
    "target_rate_limit_bytes_per_second": 52428800,
    "file_work_budget": 48,
    "unreachable_polls": 12,
    "backoff_max_seconds": 900,
    "capacity_reject_bytes": 10995116277760,
    "capacity_alert_bytes": 21990232555520,
    "peer_token_sha256": {
      "P1": "a770a998b69adca5f88498fcd315c7d49d54b3ab2091c6f8afab58390bb1da1a",
      "P2": "cd080f7d82024533203a52d5493255eba01280b4b2c56e025f7d90bd0dcb8590",
      "P3": "fd61a03af4f77d870fc21e05e7e80678095c92d808cfb3b5c279ee04c74aca13"
    }
  },
  "fixed": {
    "declaration_max_age_days": 7,
    "abandoned_ttl_hours": 24,
    "cleaner_interval_hours": 24,
    "full_reconcile_hours": 6,
    "search_window_days": 30,
    "late_publish_tolerance_days": 19,
    "clock_margin_days": 1
  }
}
```

`fixed` 段可省略；存在則必須逐欄等於 v1 常數（D30 修 6）。`peer_token_sha256` 屬 operational 段（D30「每 Node 憑證輪替」為可調項），鍵集合必須 ⊆ `policy.nodes`。

---

### Task 1: Config 資料模型與驗證（gigaxfer-core）

**Files:**
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/Policy.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/RequiredTargets.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/OperationalPolicy.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/FixedConstants.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/NodeConfig.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/ConfigCodec.java`
- Create: `gigaxfer-core/src/main/java/com/gigaxfer/core/config/InvalidConfigException.java`
- Test: `gigaxfer-core/src/test/java/com/gigaxfer/core/config/ConfigCodecTest.java`
- Test fixture: `gigaxfer-core/src/test/resources/config/v3.json`（內容 = 上方設定檔格式範例）

**Interfaces:**
- Consumes: `com.gigaxfer.core.identity.FileIdentity.requireSegment(String value, String name)`（P01 已為 public static；注意參數順序是 value 在前）。
- Produces: `ConfigCodec.decode(byte[]) : NodeConfig`（驗證通過才回）、`ConfigCodec.encodePolicy(Policy) : byte[]`、`ConfigCodec.encode(NodeConfig) : byte[]`、`Policy.targetsFor(sourceNode, dataClass) : Optional<Set<String>>`、`Policy.namespaces() : List<String>`、`Policy.isNamespaceRegistered(String) : boolean`、`Policy.allowsWrite(namespace, sourceNode, dataClass) : boolean`、`FixedConstants.V1`、`InvalidConfigException(String reason)`。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigCodecTest {

    private static byte[] fixture() throws IOException {
        try (InputStream in = ConfigCodecTest.class.getResourceAsStream("/config/v3.json")) {
            return in.readAllBytes();
        }
    }

    private static byte[] mutate(String find, String replace) throws IOException {
        String s = new String(fixture(), StandardCharsets.UTF_8);
        assertThat(s).contains(find);
        return s.replace(find, replace).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void decodes_fixture_and_normalises_policy() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        assertThat(c.schemaVersion()).isEqualTo(1);
        assertThat(c.version()).isEqualTo(3L);
        assertThat(c.publishedBy()).isEqualTo("alice");
        assertThat(c.policy().deployment()).isEqualTo("example-deployment");
        assertThat(c.policy().nodes()).containsExactly("P1", "P2", "P3");
        assertThat(c.policy().targetsFor("P1", "lot-log")).contains(Set.of("P2", "P3"));
        assertThat(c.policy().targetsFor("P1", "local-only")).contains(Set.of());
        assertThat(c.policy().targetsFor("P3", "lot-log")).isEmpty();
        assertThat(c.operational().pendingLimit()).isEqualTo(200);
        assertThat(c.operational().peerTokenSha256()).containsKeys("P1", "P2", "P3");
        assertThat(c.fixed()).isEqualTo(FixedConstants.V1);
    }

    @Test
    void policy_equality_ignores_list_order() throws IOException {
        NodeConfig a = ConfigCodec.decode(fixture());
        NodeConfig b = ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", "[\"P3\", \"P1\", \"P2\"]"));
        assertThat(b.policy()).isEqualTo(a.policy());
    }

    @Test
    void fixed_segment_may_be_omitted_and_then_defaults_to_v1() throws IOException {
        String s = new String(fixture(), StandardCharsets.UTF_8);
        int i = s.indexOf("  \"fixed\"");
        String without = s.substring(0, i).replaceAll(",\\s*$", "\n") + "}\n";
        NodeConfig c = ConfigCodec.decode(without.getBytes(StandardCharsets.UTF_8));
        assertThat(c.fixed()).isEqualTo(FixedConstants.V1);
    }

    @Test
    void rejects_fixed_segment_that_differs_from_v1() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"declaration_max_age_days\": 7", "\"declaration_max_age_days\": 8")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("fixed");
    }

    @Test
    void rejects_unknown_field() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"version\": 3,", "\"version\": 3, \"extra\": 1,")))
            .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejects_wrong_schema_version() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"schema_version\": 1", "\"schema_version\": 2")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("schema_version");
    }

    @Test
    void rejects_target_not_in_nodes() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"targets\": [\"P1\"]", "\"targets\": [\"P9\"]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P9");
    }

    @Test
    void rejects_source_as_its_own_target() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"targets\": [\"P1\"]", "\"targets\": [\"P2\"]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P2");
    }

    @Test
    void rejects_duplicate_source_class_pair() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"data_class\": \"local-only\"", "\"data_class\": \"lot-log\"")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("lot-log");
    }

    @Test
    void rejects_empty_nodes() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", "[]")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("nodes");
    }

    @Test
    void rejects_more_than_ten_nodes() throws IOException {
        String eleven = "[\"P1\", \"P2\", \"P3\", \"P4\", \"P5\", \"P6\", \"P7\", \"P8\", \"P9\", \"P10\", \"P11\"]";
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("[\"P1\", \"P2\", \"P3\"]", eleven)))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("nodes");
    }

    @Test
    void rejects_bad_node_name_segment() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"P3\"", "\"a/b\"")))
            .isInstanceOf(InvalidConfigException.class);
    }

    @Test
    void rejects_peer_token_for_unknown_node_and_bad_hex() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"P3\": \"fd61a03a", "\"P9\": \"fd61a03a")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("P9");
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("a770a998b69adca5f88498fcd315c7d49d54b3ab2091c6f8afab58390bb1da1a", "zz")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("sha256");
    }

    @Test
    void rejects_capacity_reject_not_below_alert() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"capacity_alert_bytes\": 21990232555520", "\"capacity_alert_bytes\": 1")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("capacity");
    }

    @Test
    void rejects_non_positive_operational_value() throws IOException {
        assertThatThrownBy(() -> ConfigCodec.decode(mutate("\"pending_limit\": 200", "\"pending_limit\": 0")))
            .isInstanceOf(InvalidConfigException.class)
            .hasMessageContaining("pending_limit");
    }

    @Test
    void encode_policy_is_single_line_snake_case_json() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        String json = new String(ConfigCodec.encodePolicy(c.policy()), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{");
        assertThat(json).contains("\"namespaces\":[\"analytics\",\"transactions\"]");
        assertThat(json).endsWith("}\n");
        assertThat(json.strip()).doesNotContain("\n");
    }

    @Test
    void encode_then_decode_round_trips() throws IOException {
        NodeConfig c = ConfigCodec.decode(fixture());
        assertThat(ConfigCodec.decode(ConfigCodec.encode(c))).isEqualTo(c);
    }
    @Test
    void registry_rejects_unregistered_namespace_but_allows_local_only_class() throws IOException {
        Policy policy = ConfigCodec.decode(fixture()).policy();
        assertThat(policy.namespaces()).containsExactly("analytics", "transactions");
        assertThat(policy.allowsWrite("transactions", "P1", "lot-log")).isTrue();
        assertThat(policy.allowsWrite("transactions", "P1", "local-only")).isTrue();
        assertThat(policy.allowsWrite("unregistered", "P1", "lot-log")).isFalse();
        assertThat(policy.allowsWrite("transactions", "P1", "unregistered")).isFalse();
    }

    @Test
    void namespace_registry_is_validated_and_part_of_policy_identity() throws IOException {
        String entry = "\"namespaces\": [\"transactions\", \"analytics\"]";
        Policy original = ConfigCodec.decode(fixture()).policy();
        assertThat(ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"analytics\", \"transactions\"]")).policy()).isEqualTo(original);
        assertThat(ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"transactions\"]")).policy()).isNotEqualTo(original);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"transactions\", \"transactions\"]")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry,
            "\"namespaces\": [\"../escape\"]")))
            .isInstanceOf(InvalidConfigException.class);
        assertThatThrownBy(() -> ConfigCodec.decode(mutate(entry + ",", "")))
            .isInstanceOf(InvalidConfigException.class);
    }

}
```

- [ ] **Step 2: 放 fixture**

把「設定檔格式」一節的 JSON 原文存為 `gigaxfer-core/src/test/resources/config/v3.json`。

- [ ] **Step 3: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-core test -Dtest=ConfigCodecTest`
Expected: 編譯失敗（找不到 `com.gigaxfer.core.config`）。

- [ ] **Step 4: 寫資料模型**

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** (Source Node, Data class) → Required targets 的一列。targets 為空 = 只在本地、不同步。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RequiredTargets(String sourceNode, String dataClass, List<String> targets) {
    public RequiredTargets {
        targets = List.copyOf(targets);
    }
}
```

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Policy：第一版運行期間不可變的對照表。經 ConfigCodec 解碼後 nodes 與 required_targets 已排序，記錄相等即語意相等。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Policy(String deployment, List<String> nodes, List<String> namespaces, List<RequiredTargets> requiredTargets) {
    public Policy {
        nodes = List.copyOf(nodes);
        namespaces = List.copyOf(namespaces);
        requiredTargets = List.copyOf(requiredTargets);
    }

    public boolean isNamespaceRegistered(String namespace) {
        return namespaces.contains(namespace);
    }

    public boolean allowsWrite(String namespace, String sourceNode, String dataClass) {
        return isNamespaceRegistered(namespace) && targetsFor(sourceNode, dataClass).isPresent();
    }

    /** 未登錄的 (source, class) 回 empty；登錄但 targets 為空回 Optional.of(空集合)。 */
    public Optional<Set<String>> targetsFor(String sourceNode, String dataClass) {
        for (RequiredTargets rt : requiredTargets) {
            if (rt.sourceNode().equals(sourceNode) && rt.dataClass().equals(dataClass)) {
                return Optional.of(Set.copyOf(rt.targets()));
            }
        }
        return Optional.empty();
    }

    /** 排序後的等價 Policy，供比較與輸出。 */
    Policy normalized() {
        List<String> ns = new TreeSet<>(nodes).stream().toList();
        List<RequiredTargets> rts = requiredTargets.stream()
            .map(rt -> new RequiredTargets(rt.sourceNode(), rt.dataClass(), new TreeSet<>(rt.targets()).stream().toList()))
            .sorted(Comparator.comparing(RequiredTargets::sourceNode).thenComparing(RequiredTargets::dataClass))
            .toList();
        return new Policy(deployment, ns, new TreeSet<>(namespaces).stream().toList(), rts);
    }
}
```

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/** operational policy 可調項（D30、D30 修、D30 修 3、D52）；peer_token_sha256 = 各 Node token 的 sha256 hex（D14 修 2）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OperationalPolicy(
    int scanIntervalSeconds,
    int pollIntervalSeconds,
    int pendingLimit,
    int transferTimeoutBaseSeconds,
    long transferTimeoutBytesPerSecond,
    int targetConcurrency,
    long targetRateLimitBytesPerSecond,
    int fileWorkBudget,
    int unreachablePolls,
    int backoffMaxSeconds,
    long capacityRejectBytes,
    long capacityAlertBytes,
    Map<String, String> peerTokenSha256) {
    public OperationalPolicy {
        peerTokenSha256 = Map.copyOf(peerTokenSha256);
    }
}
```

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** v1 固定常數（D30 修 5、D30 修 6）。程式碼以 V1 為準；設定若帶 fixed 段必須逐欄相等。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FixedConstants(
    int declarationMaxAgeDays,
    int abandonedTtlHours,
    int cleanerIntervalHours,
    int fullReconcileHours,
    int searchWindowDays,
    int latePublishToleranceDays,
    int clockMarginDays) {
    public static final FixedConstants V1 = new FixedConstants(7, 24, 24, 6, 30, 19, 1);
}
```

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

/** 一個不可變的設定版本（git configs/v<N>.json = sync 主機 active.json）。fixed 省略時解碼為 V1。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NodeConfig(
    int schemaVersion,
    long version,
    String publishedBy,
    Instant publishedAt,
    Policy policy,
    OperationalPolicy operational,
    FixedConstants fixed) {
}
```

```java
package com.gigaxfer.core.config;

import java.io.IOException;

/** 設定驗證失敗；extends IOException 以沿用 gigaxfer-core 既有風格（見 MalformedManifestException），
 * 使呼叫端測試可用單一 {@code throws IOException} 涵蓋。 */
public final class InvalidConfigException extends IOException {
    public InvalidConfigException(String reason) {
        super(reason);
    }

    public InvalidConfigException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
```

- [ ] **Step 5: 寫 ConfigCodec**

```java
package com.gigaxfer.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gigaxfer.core.identity.FileIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 設定檔編解碼與驗證。decode 只回傳通過全部驗證的 NodeConfig；Policy 已正規化（排序）。 */
public final class ConfigCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODES = 10;
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private static final JsonMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private ConfigCodec() {}

    public static NodeConfig decode(byte[] json) throws InvalidConfigException {
        NodeConfig raw;
        try {
            raw = MAPPER.readValue(json, NodeConfig.class);
        } catch (IOException e) {
            throw new InvalidConfigException("malformed config: " + e.getMessage(), e);
        }
        return validate(raw);
    }

    public static byte[] encode(NodeConfig c) {
        return write(c);
    }

    public static byte[] encodePolicy(Policy p) {
        return write(p.normalized());
    }

    private static byte[] write(Object o) {
        try {
            return (MAPPER.writeValueAsString(o) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static NodeConfig validate(NodeConfig c) throws InvalidConfigException {
        if (c.schemaVersion() != SCHEMA_VERSION) {
            throw new InvalidConfigException("schema_version must be " + SCHEMA_VERSION + ", got " + c.schemaVersion());
        }
        if (c.version() < 1) {
            throw new InvalidConfigException("version must be >= 1, got " + c.version());
        }
        require(c.publishedBy() != null && !c.publishedBy().isBlank(), "published_by is required");
        require(c.publishedAt() != null, "published_at is required");
        require(c.policy() != null, "policy is required");
        require(c.operational() != null, "operational is required");

        Policy p = validatePolicy(c.policy());
        validateOperational(c.operational(), p);

        FixedConstants fixed = c.fixed();
        if (fixed == null) {
            fixed = FixedConstants.V1;
        } else if (!fixed.equals(FixedConstants.V1)) {
            throw new InvalidConfigException("fixed segment must equal v1 constants " + FixedConstants.V1 + ", got " + fixed);
        }
        return new NodeConfig(c.schemaVersion(), c.version(), c.publishedBy(), c.publishedAt(), p, c.operational(), fixed);
    }

    private static Policy validatePolicy(Policy p) throws InvalidConfigException {
        require(p.deployment() != null && !p.deployment().isBlank(), "policy.deployment is required");
        require(p.nodes() != null && !p.nodes().isEmpty(), "policy.nodes must not be empty");
        require(p.nodes().size() <= MAX_NODES, "policy.nodes must have at most " + MAX_NODES + " entries");
        Set<String> nodes = new HashSet<>();
        for (String n : p.nodes()) {
            segment("policy.nodes", n);
            require(nodes.add(n), "policy.nodes has duplicate " + n);
        }
        require(p.namespaces() != null, "policy.namespaces is required");
        Set<String> namespaces = new HashSet<>();
        for (String namespace : p.namespaces()) {
            segment("policy.namespaces", namespace);
            require(namespaces.add(namespace), "policy.namespaces has duplicate " + namespace);
        }
        require(p.requiredTargets() != null, "policy.required_targets is required");
        Set<String> pairs = new HashSet<>();
        for (RequiredTargets rt : p.requiredTargets()) {
            segment("required_targets.source_node", rt.sourceNode());
            segment("required_targets.data_class", rt.dataClass());
            require(nodes.contains(rt.sourceNode()), "required_targets.source_node " + rt.sourceNode() + " not in policy.nodes");
            require(pairs.add(rt.sourceNode() + "\u0000" + rt.dataClass()),
                "required_targets has duplicate (" + rt.sourceNode() + ", " + rt.dataClass() + ")");
            Set<String> seen = new HashSet<>();
            for (String t : rt.targets()) {
                require(nodes.contains(t), "required_targets.targets " + t + " not in policy.nodes");
                require(!t.equals(rt.sourceNode()), "required_targets.targets " + t + " equals its own source_node");
                require(seen.add(t), "required_targets.targets has duplicate " + t);
            }
        }
        return p.normalized();
    }

    private static void validateOperational(OperationalPolicy o, Policy p) throws InvalidConfigException {
        positive("scan_interval_seconds", o.scanIntervalSeconds());
        positive("poll_interval_seconds", o.pollIntervalSeconds());
        positive("pending_limit", o.pendingLimit());
        positive("transfer_timeout_base_seconds", o.transferTimeoutBaseSeconds());
        positive("transfer_timeout_bytes_per_second", o.transferTimeoutBytesPerSecond());
        positive("target_concurrency", o.targetConcurrency());
        positive("target_rate_limit_bytes_per_second", o.targetRateLimitBytesPerSecond());
        positive("file_work_budget", o.fileWorkBudget());
        positive("unreachable_polls", o.unreachablePolls());
        positive("backoff_max_seconds", o.backoffMaxSeconds());
        positive("capacity_reject_bytes", o.capacityRejectBytes());
        positive("capacity_alert_bytes", o.capacityAlertBytes());
        require(o.capacityRejectBytes() < o.capacityAlertBytes(),
            "capacity_reject_bytes must be below capacity_alert_bytes");
        Set<String> tokenHashes = new HashSet<>();
        for (Map.Entry<String, String> e : o.peerTokenSha256().entrySet()) {
            require(p.nodes().contains(e.getKey()), "peer_token_sha256 has unknown node " + e.getKey());
            require(e.getValue() != null && SHA256_HEX.matcher(e.getValue()).matches(),
                "peer_token_sha256[" + e.getKey() + "] must be 64 lowercase hex (sha256)");
            require(tokenHashes.add(e.getValue()), "peer_token_sha256 must uniquely identify each node");
        }
    }

    private static void segment(String field, String value) throws InvalidConfigException {
        try {
            FileIdentity.requireSegment(value, field);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException(e.getMessage(), e);
        }
    }

    private static void positive(String field, long v) throws InvalidConfigException {
        require(v > 0, field + " must be > 0, got " + v);
    }

    private static void require(boolean ok, String reason) throws InvalidConfigException {
        if (!ok) {
            throw new InvalidConfigException(reason);
        }
    }
}
```

`FileIdentity.requireSegment(String value, String name)` 已存在且為 public static，直接使用。

- [ ] **Step 6: 跑測試**

Run: `mvn -q -pl gigaxfer-core test`
Expected: 全綠（P01 既有測試 + ConfigCodecTest 16 個）。

- [ ] **Step 7: Commit**

```bash
git add gigaxfer-core/src/main/java/com/gigaxfer/core/config gigaxfer-core/src/test/java/com/gigaxfer/core/config gigaxfer-core/src/test/resources/config gigaxfer-core/src/main/java/com/gigaxfer/core/identity/FileIdentity.java
git commit -m "feat(core): config data model, codec and validation (Policy, operational, v1 fixed constants)"
```

---

### Task 2: sync-service 模組 + ConfigStore 啟用協定

**Files:**
- Modify: `pom.xml`（根，加 module 與 Spring Boot BOM）
- Create: `gigaxfer-sync-service/pom.xml`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigStore.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigActivation.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigUnavailableException.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/config/ConfigStoreTest.java`
- Test fixture: `gigaxfer-sync-service/src/test/resources/config/v3.json`（複製 Task 1 的 fixture）

**Interfaces:**
- Consumes: `ConfigCodec.decode/encode`、`NodeConfig`、`InvalidConfigException`（Task 1）。
- Produces: `ConfigStore(Path dir)`、`ConfigStore.load() : ConfigActivation`、`ConfigActivation(NodeConfig config, Source source, Optional<String> activationFailure)`、`enum ConfigActivation.Source { ACTIVE, LKG }`、`ConfigUnavailableException`。

- [ ] **Step 1: 根 pom 加模組與 BOM**

`pom.xml` 的 `<modules>` 加 `<module>gigaxfer-sync-service</module>`；`<properties>` 加 `<spring-boot.version>3.4.4</spring-boot.version>`；`<dependencyManagement>` 最前面加：

```xml
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
```

注意：Spring Boot BOM 也管理 Jackson / JUnit / AssertJ 版本；既有 `jackson-bom`、`junit-bom` import 保留且放在 Boot BOM **之前**（先 import 者優先），core 的版本不受影響。

- [ ] **Step 2: 寫 sync-service pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.gigaxfer</groupId>
    <artifactId>gigaxfer-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>gigaxfer-sync-service</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.gigaxfer</groupId>
      <artifactId>gigaxfer-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-oracle</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.oracle.database.jdbc</groupId>
      <artifactId>ojdbc11</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 寫失敗測試**

```java
package com.gigaxfer.sync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.NodeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir Path dir;

    private static String fixture() throws IOException {
        try (InputStream in = ConfigStoreTest.class.getResourceAsStream("/config/v3.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String withVersion(String json, long v) {
        return json.replace("\"version\": 3", "\"version\": " + v);
    }

    private void put(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void refuses_when_neither_active_nor_lkg_exists() {
        assertThatThrownBy(() -> new ConfigStore(dir).load())
            .isInstanceOf(ConfigUnavailableException.class);
    }

    @Test
    void loads_active_when_no_candidate() throws Exception {
        put("active.json", fixture());
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isEmpty();
        assertThat(dir.resolve("lkg.json")).doesNotExist();
    }

    @Test
    void falls_back_to_lkg_when_active_missing() throws Exception {
        put("lkg.json", withVersion(fixture(), 2));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.config().version()).isEqualTo(2L);
    }

    @Test
    void falls_back_to_lkg_when_active_is_corrupt() throws Exception {
        put("active.json", "{ not json");
        put("lkg.json", withVersion(fixture(), 2));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.activationFailure()).isPresent();
    }

    @Test
    void activates_valid_candidate_and_rotates_active_to_lkg() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(a.activationFailure()).isEmpty();
        assertThat(dir.resolve("candidate.json")).doesNotExist();
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("active.json"))).version()).isEqualTo(4L);
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("lkg.json"))).version()).isEqualTo(3L);
    }

    @Test
    void corrupt_active_does_not_overwrite_good_lkg_when_candidate_activates() throws Exception {
        put("active.json", "{ not json");
        put("lkg.json", withVersion(fixture(), 2));
        put("candidate.json", withVersion(fixture(), 4));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(dir.resolve("candidate.json")).doesNotExist();
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("lkg.json"))).version()).isEqualTo(2L);
    }

    @Test
    void rejects_candidate_with_non_increasing_version_and_keeps_it() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 3));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("version");
        assertThat(dir.resolve("candidate.json")).exists();
        assertThat(dir.resolve("lkg.json")).doesNotExist();
    }

    @Test
    void rejects_candidate_whose_policy_differs() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4).replace("\"targets\": [\"P1\"]", "\"targets\": [\"P1\", \"P3\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("policy");
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void accepts_candidate_that_only_changes_operational_and_node_order() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4)
            .replace("\"pending_limit\": 200", "\"pending_limit\": 100")
            .replace("[\"P1\", \"P2\", \"P3\"]", "[\"P3\", \"P2\", \"P1\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(a.config().operational().pendingLimit()).isEqualTo(100);
    }

    @Test
    void rejects_malformed_candidate_and_keeps_active() throws Exception {
        put("active.json", withVersion(fixture(), 3));
        put("candidate.json", "{ \"schema_version\": 1 ");
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent();
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void crash_between_renames_recovers_on_next_start() throws Exception {
        // 模擬：active→lkg 已完成、candidate→active 尚未完成時 crash。
        put("lkg.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.ACTIVE);
        assertThat(a.config().version()).isEqualTo(4L);
        assertThat(dir.resolve("candidate.json")).doesNotExist();
        assertThat(ConfigCodec.decode(Files.readAllBytes(dir.resolve("lkg.json"))).version()).isEqualTo(3L);
    }

    @Test
    void candidate_is_validated_against_lkg_when_active_missing() throws Exception {
        put("lkg.json", withVersion(fixture(), 3));
        put("candidate.json", withVersion(fixture(), 4).replace("\"targets\": [\"P1\"]", "\"targets\": [\"P1\", \"P3\"]"));
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.source()).isEqualTo(ConfigActivation.Source.LKG);
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isPresent().get().asString().contains("policy");
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void candidate_alone_does_not_bypass_manual_initial_active_setup() throws Exception {
        put("candidate.json", withVersion(fixture(), 1));
        assertThatThrownBy(() -> new ConfigStore(dir).load())
            .isInstanceOf(ConfigUnavailableException.class);
        assertThat(dir.resolve("active.json")).doesNotExist();
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void rejects_candidate_that_changes_registered_namespaces() throws Exception {
        put("active.json", fixture());
        put("candidate.json", withVersion(fixture(), 4).replace(
            "\"namespaces\": [\"transactions\", \"analytics\"]",
            "\"namespaces\": [\"transactions\"]"));
        ConfigActivation activation = new ConfigStore(dir).load();
        assertThat(activation.config().version()).isEqualTo(3L);
        assertThat(activation.activationFailure()).isPresent().get().asString().contains("policy");
        assertThat(dir.resolve("candidate.json")).exists();
    }

    @Test
    void ignores_candidate_tmp_still_being_written_by_cd() throws Exception {
        put("active.json", fixture());
        put("candidate.json.tmp", "partial");
        ConfigActivation a = new ConfigStore(dir).load();
        assertThat(a.config().version()).isEqualTo(3L);
        assertThat(a.activationFailure()).isEmpty();
    }
}
```

- [ ] **Step 4: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-sync-service -am test -Dtest=ConfigStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 編譯失敗（找不到 ConfigStore）。

- [ ] **Step 5: 寫 ConfigStore**

```java
package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.NodeConfig;
import java.util.Objects;
import java.util.Optional;

/** 啟動時一次載入的結果：生效設定、來源、以及本次 candidate 啟用失敗原因（有則 activation_failure_count +1）。 */
public record ConfigActivation(NodeConfig config, Source source, Optional<String> activationFailure) {
    public enum Source { ACTIVE, LKG }

    public ConfigActivation {
        Objects.requireNonNull(config);
        Objects.requireNonNull(source);
        Objects.requireNonNull(activationFailure);
    }
}
```

```java
package com.gigaxfer.sync.config;

/** active.json 與 lkg.json 皆缺或皆無法解碼：拒絕啟動（D17）。 */
public final class ConfigUnavailableException extends Exception {
    public ConfigUnavailableException(String message) {
        super(message);
    }
}
```

```java
package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.InvalidConfigException;
import com.gigaxfer.core.config.NodeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * candidate.json → active.json → lkg.json 啟用協定（D17、D45、spec §13）。
 * 只在啟動時呼叫一次；設定 process 內不可變。
 * 順序：讀 active（壞則視為缺）→ 有 candidate 則驗證（schema、版本遞增、policy 相同、fixed 相同）
 * → 通過：active→lkg、candidate→active（皆 ATOMIC_MOVE）→ 失敗：留 candidate、記原因 → active 缺則用 lkg。
 * crash 於兩次 rename 之間：active 缺、lkg = 舊 active、candidate 仍在；下次啟動以 lkg 為基準重驗 candidate 即可收斂。
 */
public final class ConfigStore {
    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);
    public static final String CANDIDATE = "candidate.json";
    public static final String ACTIVE = "active.json";
    public static final String LKG = "lkg.json";

    private final Path dir;

    public ConfigStore(Path dir) {
        this.dir = Objects.requireNonNull(dir);
    }

    public ConfigActivation load() throws ConfigUnavailableException, IOException {
        Path candidate = dir.resolve(CANDIDATE);
        Path active = dir.resolve(ACTIVE);
        Path lkg = dir.resolve(LKG);

        Optional<String> failure = Optional.empty();
        Optional<NodeConfig> current = read(active);
        if (current.isEmpty() && Files.exists(active)) {
            failure = Optional.of("active.json unreadable, using lkg");
        }
        ConfigActivation.Source source = current.isPresent() ? ConfigActivation.Source.ACTIVE : ConfigActivation.Source.LKG;
        Optional<NodeConfig> baseline = current.isPresent() ? current : read(lkg);

        if (baseline.isEmpty()) {
            throw new ConfigUnavailableException("no valid active or lkg config; initialise active.json first");
        }

        if (Files.exists(candidate)) {
            String reason = validateCandidate(candidate, baseline);
            if (reason == null) {
                NodeConfig accepted;
                try {
                    accepted = ConfigCodec.decode(Files.readAllBytes(candidate));
                } catch (InvalidConfigException e) {
                    throw new IllegalStateException("candidate validated a moment ago", e);
                }
                if (current.isPresent()) {
                    Files.move(active, lkg, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(candidate, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("activated config version {} (previous {})", accepted.version(),
                    baseline.map(b -> Long.toString(b.version())).orElse("none"));
                return new ConfigActivation(accepted, ConfigActivation.Source.ACTIVE, Optional.empty());
            }
            log.warn("candidate.json rejected, left in place: {}", reason);
            failure = Optional.of(reason);
        }

        if (current.isPresent()) {
            return new ConfigActivation(current.get(), source, failure);
        }
        Optional<NodeConfig> fromLkg = read(lkg);
        if (fromLkg.isPresent()) {
            log.warn("active.json missing or unreadable, running on lkg.json version {}", fromLkg.get().version());
            return new ConfigActivation(fromLkg.get(), ConfigActivation.Source.LKG, failure);
        }
        throw new ConfigUnavailableException("neither " + active + " nor " + lkg + " is a valid config; refusing to start");
    }

    /** 回 null = 通過；否則為拒絕原因。 */
    private static String validateCandidate(Path candidate, Optional<NodeConfig> baseline) throws IOException {
        NodeConfig c;
        try {
            c = ConfigCodec.decode(Files.readAllBytes(candidate));
        } catch (InvalidConfigException e) {
            return "candidate invalid: " + e.getMessage();
        }
        if (baseline.isPresent()) {
            NodeConfig b = baseline.get();
            if (c.version() <= b.version()) {
                return "candidate version " + c.version() + " is not greater than current version " + b.version();
            }
            if (!c.policy().equals(b.policy())) {
                return "candidate policy segment differs from current version " + b.version() + " (policy is immutable in v1)";
            }
        }
        return null;
    }

    private static Optional<NodeConfig> read(Path p) throws IOException {
        if (!Files.exists(p)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ConfigCodec.decode(Files.readAllBytes(p)));
        } catch (InvalidConfigException e) {
            log.error("{} is not a valid config: {}", p, e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 6: 跑測試**

Run: `mvn -q test`
Expected: 兩模組全綠；ConfigStoreTest 包含 candidate-only 拒絕、Namespace 變更拒絕與壞 active 不覆蓋有效 LKG 的回歸測試。

- [ ] **Step 7: Commit**

```bash
git add pom.xml gigaxfer-sync-service
git commit -m "feat(sync): sync-service module and candidate/active/lkg config activation protocol"
```

---

### Task 3: Spring Boot 啟動、`/policy`、設定指標

**Files:**
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/GigaxferSyncApplication.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/SyncProperties.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigBootstrap.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/PolicyController.java`
- Create: `gigaxfer-sync-service/src/main/resources/application.yml`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/SyncTestSupport.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/config/PolicyEndpointTest.java`
- Test: `gigaxfer-sync-service/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: `ConfigStore`、`ConfigActivation`（Task 2）、`ConfigCodec.encodePolicy`（Task 1）。
- Produces: `@Bean NodeConfig`（全 app 唯一設定物件）、`@Bean ConfigActivation`、`SyncProperties(node, configDir, tokenFile, nfsRoot, nfsTimeout, nfsSlots)`、`GET /policy` → `{"version": N, "policy": {...}}`、metrics `active_config_version{node}`、`activation_failure_count{node}`。

- [ ] **Step 1: 寫 application.yml 與測試 profile**

`src/main/resources/application.yml`：

```yaml
server:
  port: 8080
spring:
  application:
    name: gigaxfer-sync
  flyway:
    enabled: false          # migration 由 DbBootstrap 在背景重試執行（D34 修）
  datasource:
    hikari:
      initialization-fail-timeout: -1   # DB 不可用時不阻擋啟動
      maximum-pool-size: 8
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      show-details: always
  metrics:
    tags:
      node: ${gigaxfer.node}
gigaxfer:
  nfs-timeout: 30s
  nfs-slots: 16
```

`src/test/resources/application-test.yml`：

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:gigaxfer;MODE=Oracle;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
gigaxfer:
  node: P1
  nfs-timeout: 2s
  nfs-slots: 2
```

`config-dir`、`token-file`、`nfs-root` 由測試以 `@DynamicPropertySource` 指向 `@TempDir`。

- [ ] **Step 2: 寫 SyncProperties 與 Application**

```java
package com.gigaxfer.sync;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本機部署參數（非 config 版本的一部分）：Node 名、設定目錄、自身 token 檔、NFS root 與執行器參數。 */
@ConfigurationProperties(prefix = "gigaxfer")
public record SyncProperties(
    String node,
    Path configDir,
    Path tokenFile,
    Path nfsRoot,
    Duration nfsTimeout,
    int nfsSlots) {
}
```

```java
package com.gigaxfer.sync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SyncProperties.class)
public class GigaxferSyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(GigaxferSyncApplication.class, args);
    }
}
```

- [ ] **Step 3: 寫失敗測試**

`SyncTestSupport`（測試共用基底，後續 task 沿用）：

```java
package com.gigaxfer.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 每個測試類別一個 TempDir：config/active.json = fixture v3、token 檔 = "p1-secret"、nfs root = 空目錄。 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class SyncTestSupport {
    public static final String P1_TOKEN = "p1-secret";
    public static final String P2_TOKEN = "p2-secret";

    @TempDir static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path cfg = Files.createDirectories(root.resolve("config"));
        Path nfs = Files.createDirectories(root.resolve("nfs"));
        Files.writeString(cfg.resolve("active.json"), fixture());
        Path token = root.resolve("token");
        Files.writeString(token, P1_TOKEN + "\n");
        r.add("gigaxfer.config-dir", cfg::toString);
        r.add("gigaxfer.nfs-root", nfs::toString);
        r.add("gigaxfer.token-file", token::toString);
    }

    public static String fixture() throws IOException {
        try (InputStream in = SyncTestSupport.class.getResourceAsStream("/config/v3.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static Path configDir() {
        return root.resolve("config");
    }

    public static Path nfsRoot() {
        return root.resolve("nfs");
    }
}
```

fixture 的 `peer_token_sha256` 必須對應真實 token：P1 = sha256("p1-secret")、P2 = sha256("p2-secret")。把 `gigaxfer-core/src/test/resources/config/v3.json` 與 `gigaxfer-sync-service/src/test/resources/config/v3.json` 中 P1、P2 的值改為（用 `printf 'p1-secret' | shasum -a 256` 驗算）：

- P1: `printf 'p1-secret' | shasum -a 256` 的輸出
- P2: `printf 'p2-secret' | shasum -a 256` 的輸出
- P3 維持原值（無對應 token，模擬尚未部署的 Node）

Task 1 的 `rejects_peer_token_for_unknown_node_and_bad_hex` 以 `"P3": "fd61a03a` 為錨點，P3 不變即不受影響；`mutate("9f86d081…", "zz")` 的錨點改為 P1 新值。

`PolicyEndpointTest`：

```java
package com.gigaxfer.sync.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.sync.SyncTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PolicyEndpointTest extends SyncTestSupport {
    @Autowired MockMvc mvc;
    @Autowired NodeConfig config;
    @Autowired MeterRegistry meters;

    @Test
    void active_config_is_the_fixture() {
        assertThat(config.version()).isEqualTo(3L);
        assertThat(config.policy().nodes()).contains("P1");
    }

    @Test
    void policy_endpoint_returns_version_and_normalised_policy_without_auth() throws Exception {
        mvc.perform(get("/policy"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.policy.deployment").value("example-deployment"))
            .andExpect(jsonPath("$.policy.namespaces[0]").value("analytics"))
            .andExpect(jsonPath("$.policy.namespaces[1]").value("transactions"))
            .andExpect(jsonPath("$.policy.nodes[0]").value("P1"))
            .andExpect(jsonPath("$.policy.required_targets[0].source_node").value("P1"))
            .andExpect(jsonPath("$.policy.required_targets[0].data_class").value("local-only"))
            .andExpect(jsonPath("$.policy.required_targets[1].targets[1]").value("P3"));
    }

    @Test
    void config_metrics_are_registered_with_node_tag() {
        assertThat(meters.get("active_config_version").tag("node", "P1").gauge().value()).isEqualTo(3.0);
        assertThat(meters.get("activation_failure_count").tag("node", "P1").gauge().value()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 4: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-sync-service test -Dtest=PolicyEndpointTest`
Expected: context 啟動失敗（無 NodeConfig bean）。

- [ ] **Step 5: 寫 ConfigBootstrap 與 PolicyController**

```java
package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.sync.SyncProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 啟動序列第一步（D34）：讀一次設定，之後 process 內不可變（D45）。
 * 失敗即讓 context 啟動失敗（D17：active 與 lkg 皆缺拒絕啟動）；systemd 會重啟，但沒有設定就沒有可服務的東西。
 */
@Configuration
public class ConfigBootstrap {

    @Bean
    ConfigActivation configActivation(SyncProperties props) throws ConfigUnavailableException, IOException {
        if (props.configDir() == null) {
            throw new ConfigUnavailableException("gigaxfer.config-dir is not set");
        }
        return new ConfigStore(props.configDir()).load();
    }

    @Bean
    NodeConfig nodeConfig(ConfigActivation activation, SyncProperties props) throws ConfigUnavailableException {
        NodeConfig c = activation.config();
        if (props.node() == null || !c.policy().nodes().contains(props.node())) {
            throw new ConfigUnavailableException("gigaxfer.node '" + props.node() + "' is not in policy.nodes " + c.policy().nodes());
        }
        return c;
    }

    @Bean
    ConfigMetrics configMetrics(MeterRegistry registry, ConfigActivation activation, NodeConfig config) {
        Gauge.builder("active_config_version", config, c -> (double) c.version())
            .description("version of the config this process is running on (D17)")
            .register(registry);
        Gauge.builder("activation_failure_count", activation, a -> a.activationFailure().isPresent() ? 1.0 : 0.0)
            .description("1 if a candidate.json was rejected at this start (D45)")
            .register(registry);
        return new ConfigMetrics();
    }

    /** 標記 bean，讓指標註冊有明確的生命週期。 */
    public static final class ConfigMetrics {}
}
```

`node` 標籤由 `management.metrics.tags.node` 自動加到所有指標。設定不熱載入，`activation_failure_count` 在單次 process 內只會是 0 或 1，重啟即重算——Prometheus 端以 `max_over_time` 看歷史（D45 的「計數」在無狀態 process 下就是這個意思，記入 P02 偏差）。

```java
package com.gigaxfer.sync.config;

import com.gigaxfer.core.config.ConfigCodec;
import com.gigaxfer.core.config.NodeConfig;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Node 內端點：library 取 Policy 段（D18）。不認證（同主機群、Node 內）。 */
@RestController
public class PolicyController {
    private final byte[] body;

    public PolicyController(NodeConfig config) {
        String policy = new String(ConfigCodec.encodePolicy(config.policy()), StandardCharsets.UTF_8).strip();
        this.body = ("{\"version\":" + config.version() + ",\"policy\":" + policy + "}\n").getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/policy", produces = MediaType.APPLICATION_JSON_VALUE)
    public byte[] policy() {
        return body;
    }
}
```

- [ ] **Step 6: 跑測試**

Run: `mvn -q test`
Expected: 全綠。若 `PolicyEndpointTest` 因 DataSource 自動設定失敗，確認 `application-test.yml` 的 H2 URL 已載入（`@ActiveProfiles("test")`）。

- [ ] **Step 7: Commit**

```bash
git add gigaxfer-sync-service gigaxfer-core/src/test/resources/config/v3.json gigaxfer-core/src/test/java/com/gigaxfer/core/config/ConfigCodecTest.java
git commit -m "feat(sync): Spring Boot bootstrap, immutable NodeConfig bean, GET /policy, config metrics"
```

---

### Task 4: Flyway schema（D24 修 全表）與背景 DB bootstrap

**Files:**
- Create: `gigaxfer-sync-service/src/main/resources/db/migration/V1__schema.sql`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/db/DbBootstrap.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/db/DbState.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/db/SchemaTest.java`

**Interfaces:**
- Consumes: Spring `DataSource`（Hikari，`initialization-fail-timeout=-1`）。
- Produces: `DbState.ready() : boolean`、`DbState.lastError() : Optional<String>`、`DbState.awaitReady(Duration) : boolean`（測試用）；表 `file_identity, obligation, received, rebuild_progress, node_meta, seq_counter, inspection, ops_audit, target_control, obligation_history`；`node_meta` 一列（incarnation UUID、rebuild_in_progress=0）、`seq_counter` 兩列（completed、change，last=0）。

- [ ] **Step 1: 寫失敗測試**

```java
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
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'", String.class)
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
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-sync-service test -Dtest=SchemaTest`
Expected: 編譯失敗（無 DbState）。

- [ ] **Step 3: 寫 V1__schema.sql**

identity 外鍵以三段自然鍵引用（`identity_ref` 在文件中為邏輯名；實體欄位為 `source_node, namespace, logical_key`）。

```sql
-- gigaxfer sync-service schema V1 (system-design §3, D24 修, D29 修 11, D33 修 3, D54, D55, D56)
-- 可攜 SQL：Oracle 與 H2 MODE=Oracle 皆可執行。布林用 NUMERIC(1)。

CREATE TABLE file_identity (
  source_node     VARCHAR(64)   NOT NULL,
  namespace       VARCHAR(128)  NOT NULL,
  logical_key     VARCHAR(512)  NOT NULL,
  data_class      VARCHAR(128)  NOT NULL,
  size            NUMERIC(19)   NOT NULL,
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
  size             NUMERIC(19)   NOT NULL,
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
```

Target 的 `received.content_path` 保存交付提供的相對路徑，`data_class` 保存其分類；與 Source 的 `file_identity` 不建外鍵。P05 初次發布與 P08 補建皆須寫入，P10 `/locate` 直接讀此欄位，不用 Target 時鐘猜路徑。

註：`rebuild_progress.cursor` 改名 `cursor_seq`（`CURSOR` 在 Oracle 是保留字）；`inspection.unknown` 改名 `unknown_count` 同理。`obligation_history` 事故類「同 (identity, target, kind) 只寫一次」（D33 修 2）由 P07 以條件插入保證，不在此加唯一鍵（LOST / CORRUPT 可多次）。

- [ ] **Step 4: 寫 DbState 與 DbBootstrap**

```java
package com.gigaxfer.sync.db;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** DB 就緒狀態：migration 與 bootstrap 列完成後才 ready；health 與後續排程都看這個旗標（D34 修）。 */
public final class DbState {
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile String lastError;
    private final Runnable bootstrap;

    DbState(Runnable bootstrap) {
        this.bootstrap = bootstrap;
    }

    public boolean ready() {
        return ready.getCount() == 0;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    public boolean awaitReady(Duration timeout) {
        try {
            return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 執行一次 migration + bootstrap；成功即 ready。冪等：可重複呼叫（測試與重試共用）。 */
    public void runOnce() {
        try {
            bootstrap.run();
            lastError = null;
            ready.countDown();
        } catch (RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        }
    }
}
```

```java
package com.gigaxfer.sync.db;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 啟動序列第二步（D34）：開 DB。DB 連不上不退出：背景每 5 s 重試 migration，期間 health 回 DOWN（D34 修）。
 * bootstrap 列：node_meta 一列（incarnation 只在首次建立時產生，D55 (5)）、seq_counter 兩列（D29 修 11）。
 */
@Configuration
public class DbBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DbBootstrap.class);
    static final long RETRY_MILLIS = 5_000;

    @Bean
    DbState dbState(DataSource ds, TransactionTemplate tx) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        return new DbState(() -> {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            tx.executeWithoutResult(s -> {
                Integer meta = jdbc.queryForObject("SELECT COUNT(*) FROM node_meta", Integer.class);
                if (meta == null || meta == 0) {
                    jdbc.update("INSERT INTO node_meta (singleton, incarnation, rebuild_in_progress) VALUES (1, ?, 0)",
                        UUID.randomUUID().toString());
                }
                for (String name : new String[] {"completed", "change"}) {
                    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM seq_counter WHERE name = ?", Integer.class, name);
                    if (n == null || n == 0) {
                        jdbc.update("INSERT INTO seq_counter (name, last) VALUES (?, 0)", name);
                    }
                }
            });
        });
    }

    @Bean
    SmartLifecycle dbBootstrapLifecycle(DbState state) {
        return new SmartLifecycle() {
            private volatile Thread worker;

            @Override
            public void start() {
                Thread t = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            state.runOnce();
                            log.info("database migrated and bootstrapped");
                            return;
                        } catch (RuntimeException e) {
                            log.warn("database not ready ({}); retrying in {} ms", state.lastError().orElse("?"), RETRY_MILLIS);
                        }
                        try {
                            Thread.sleep(RETRY_MILLIS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "db-bootstrap");
                t.setDaemon(true);
                worker = t;
                t.start();
            }

            @Override
            public void stop() {
                Thread t = worker;
                if (t != null) {
                    t.interrupt();
                }
            }

            @Override
            public boolean isRunning() {
                Thread t = worker;
                return t != null && t.isAlive();
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE; // 在 web server 之前啟動，但不阻擋它
            }
        };
    }
}
```

- [ ] **Step 5: 跑測試**

Run: `mvn -q test`
Expected: 全綠，包含 SchemaTest 的完整 schema、冪等 bootstrap 與遠端副本路徑測試。若 H2 對 `information_schema.tables` 的 `table_schema` 欄位名不同（H2 2.x 為 `TABLE_SCHEMA`，值 `PUBLIC`），依錯誤訊息調整查詢，不改斷言。

- [ ] **Step 6: Commit**

```bash
git add gigaxfer-sync-service/src/main/resources/db gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/db gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/db
git commit -m "feat(sync): Flyway V1 schema (all D24-rev tables) and background DB bootstrap with retry"
```

---

### Task 5: `/actuator/health` readiness：DB 與 NFS 指標

**Files:**
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/nfs/NfsConfig.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/health/DbHealthIndicator.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/health/NfsHealthIndicator.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/health/HealthMetrics.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/health/HealthEndpointTest.java`

**Interfaces:**
- Consumes: `DbState`（Task 4）、`com.gigaxfer.core.nfs.BoundedNfsExecutor(name, slots, timeout)` / `NfsExecutor.call(op, body)`、`NfsException`（P01）。
- Produces: `@Bean NfsExecutor`（全 app 共用一個有界執行器，D51）、health components `db`、`nfs`、readiness group；metrics `db_health{node}`、`storage_health{node}`（1 = UP、0 = DOWN）。

- [ ] **Step 1: 寫失敗測試**

```java
package com.gigaxfer.sync.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gigaxfer.sync.SyncTestSupport;
import com.gigaxfer.sync.db.DbState;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HealthEndpointTest extends SyncTestSupport {
    @Autowired MockMvc mvc;
    @Autowired DbState db;
    @Autowired MeterRegistry meters;

    @Test
    void readiness_is_up_when_db_migrated_and_nfs_root_reachable() throws Exception {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"))
            .andExpect(jsonPath("$.components.nfs.status").value("UP"));
        assertThat(meters.get("db_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void nfs_component_is_down_when_root_disappears_and_recovers() throws Exception {
        assertThat(db.awaitReady(Duration.ofSeconds(30))).isTrue();
        Path root = nfsRoot();
        Path moved = root.resolveSibling("nfs-gone");
        Files.move(root, moved);
        try {
            mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.nfs.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
            assertThat(meters.get("storage_health").tag("node", "P1").gauge().value()).isEqualTo(0.0);
        } finally {
            Files.move(moved, root);
        }
        mvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.nfs.status").value("UP"));
    }

    @Test
    void liveness_does_not_depend_on_db_or_nfs() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
```

`liveness` 群組需在 `application.yml` 加：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: db,nfs
```

（在 Task 5 與兩個 health indicators 一起加入；`probes.enabled` 提供 `livenessState` / `readinessState`，readiness 群組被我們覆寫為 `db,nfs`。）

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-sync-service test -Dtest=HealthEndpointTest`
Expected: `components.nfs` 不存在 → 失敗。

- [ ] **Step 3: 寫 NfsConfig 與 health indicators**

```java
package com.gigaxfer.sync.nfs;

import com.gigaxfer.core.nfs.BoundedNfsExecutor;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.sync.SyncProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 全 app 唯一的有界 NFS 執行器（D51）：固定槽、無佇列、timeout 不釋放槽。後續掃描、傳輸、自查都用它。 */
@Configuration
public class NfsConfig {
    @Bean(destroyMethod = "close")
    NfsExecutor nfsExecutor(SyncProperties props) {
        if (props.nfsRoot() == null) {
            throw new IllegalStateException("gigaxfer.nfs-root is not set");
        }
        return new BoundedNfsExecutor("nfs", props.nfsSlots(), props.nfsTimeout());
    }
}
```

```java
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
```

```java
package com.gigaxfer.sync.health;

import com.gigaxfer.core.nfs.NfsException;
import com.gigaxfer.core.nfs.NfsExecutor;
import com.gigaxfer.sync.SyncProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** nfs：經有界執行器 stat mount root；Busy / Timeout / IOException 皆 DOWN，原因帶 op（D34 修、D51）。 */
@Component("nfs")
public class NfsHealthIndicator implements HealthIndicator {
    private final NfsExecutor nfs;
    private final SyncProperties props;
    private final HealthMetrics metrics;

    public NfsHealthIndicator(NfsExecutor nfs, SyncProperties props, HealthMetrics metrics) {
        this.nfs = nfs;
        this.props = props;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        try {
            BasicFileAttributes attrs = nfs.call("stat-root",
                () -> Files.readAttributes(props.nfsRoot(), BasicFileAttributes.class));
            if (!attrs.isDirectory()) {
                metrics.storage(false);
                return Health.down().withDetail("reason", "nfs root is not a directory").build();
            }
            metrics.storage(true);
            return Health.up().withDetail("root", props.nfsRoot().toString()).build();
        } catch (NfsException e) {
            metrics.storage(false);
            return Health.down().withDetail("op", e.op()).withDetail("reason", e.getClass().getSimpleName()).build();
        } catch (IOException e) {
            metrics.storage(false);
            return Health.down(e).build();
        }
    }
}
```

```java
package com.gigaxfer.sync.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** db_health / storage_health gauge（monitoring.md Availability 軸第二層）；由 health indicator 每次探測更新，未探測前為 0。 */
@Component
public class HealthMetrics {
    private final AtomicInteger db = new AtomicInteger();
    private final AtomicInteger storage = new AtomicInteger();

    public HealthMetrics(MeterRegistry registry) {
        Gauge.builder("db_health", db, AtomicInteger::get).description("1 = DB migrated and reachable").register(registry);
        Gauge.builder("storage_health", storage, AtomicInteger::get).description("1 = NFS root stat succeeded").register(registry);
    }

    void db(boolean up) {
        db.set(up ? 1 : 0);
    }

    void storage(boolean up) {
        storage.set(up ? 1 : 0);
    }
}
```

`application.yml` 加 `management.health.db.enabled: false`（關掉 Boot 內建的 DataSource indicator，避免與自訂 `db` 同名衝突；Boot 內建者在 migration 未完成時也會回 UP，語意不對）。

- [ ] **Step 4: 跑測試**

Run: `mvn -q test`
Expected: 全綠；HealthEndpointTest 3 個。若 `Files.move` 在 macOS 上對 `@TempDir` 失敗，改為 `chmod 000` 不可行（root 權限差異），保留 move 方案並檢查 `nfsRoot()` 回傳的是移動前的路徑。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-sync-service
git commit -m "feat(sync): shared bounded NFS executor, db/nfs health indicators, readiness group and health gauges"
```

---

### Task 6: 每 Node token 認證 filter（D14 修 2）

**Files:**
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/auth/NodeAuthFilter.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/auth/CallerIdentity.java`
- Create: `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/auth/OwnToken.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/auth/NodeAuthFilterTest.java`
- Test: `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/auth/EchoCallerController.java`（測試用端點，只在 test source）

**Interfaces:**
- Consumes: `NodeConfig.operational().peerTokenSha256()`（Task 1）、`SyncProperties.tokenFile()`（Task 3）、`com.gigaxfer.core.digest.Sha256`（P01 已有 `ofBytes(byte[])` 回 `sha256:<hex>`；在 core 加 `public static String hex(byte[])` 回純 hex，不改既有方法）。
- Produces: 受保護路徑 `/pending`、`/file/**`、`/report`、`/received` 需 `Authorization: Bearer <token>`；`CallerIdentity.of(HttpServletRequest) : String`（呼叫者 Node 名）、`CallerIdentity.requireTarget(HttpServletRequest, String targetParam)`（另帶且不同 → 403）；`OwnToken.value() : String`（本 Node 明文 token，供後續 client 使用）。

- [ ] **Step 1: 寫測試用端點與失敗測試**

```java
package com.gigaxfer.sync.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 測試專用：站在 /pending 位置回報 filter 辨識出的呼叫者。正式 /pending 由 P04 提供。 */
@TestConfiguration
public class EchoCallerController {
    @Bean
    Echo echo() {
        return new Echo();
    }

    @RestController
    public static class Echo {
        @GetMapping("/pending")
        public Map<String, String> pending(HttpServletRequest req, @RequestParam(required = false) String target) {
            CallerIdentity.requireTarget(req, target);
            return Map.of("caller", CallerIdentity.of(req));
        }
    }
}
```

```java
package com.gigaxfer.sync.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
```

- [ ] **Step 2: 跑測試確認失敗**

Run: `mvn -q -pl gigaxfer-sync-service test -Dtest=NodeAuthFilterTest`
Expected: 編譯失敗（無 CallerIdentity / OwnToken）。

- [ ] **Step 3: 寫 OwnToken、CallerIdentity、NodeAuthFilter**

```java
package com.gigaxfer.sync.auth;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.core.digest.Sha256;
import com.gigaxfer.sync.SyncProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 本 Node 的明文 token：啟動時自本機秘密檔讀一次（file-inventory）。供後續對他 Node 的呼叫使用。 */
@Component
public class OwnToken {
    private static final Logger log = LoggerFactory.getLogger(OwnToken.class);
    private final String value;

    public OwnToken(SyncProperties props, NodeConfig config) throws IOException {
        if (props.tokenFile() == null) {
            throw new IllegalStateException("gigaxfer.token-file is not set");
        }
        this.value = Files.readString(props.tokenFile(), StandardCharsets.UTF_8).strip();
        if (value.isEmpty()) {
            throw new IllegalStateException("token file " + props.tokenFile() + " is empty");
        }
        String expected = config.operational().peerTokenSha256().get(props.node());
        if (expected == null || !expected.equals(Sha256.hex(value.getBytes(StandardCharsets.UTF_8)))) {
            // 輪替窗口內允許不一致：其他 Node 會拒絕本 Node，直到 config 更新。只警告不阻擋（D14 修 2、D30）。
            log.warn("sha256 of own token does not match peer_token_sha256[{}] in active config; peers will reject calls from this node", props.node());
        }
    }

    public String value() {
        return value;
    }
}
```

在 core 的 `Sha256` 加（沿用既有 `newDigest()`）：

```java
    /** sha256 hex（不帶 "sha256:" 前綴），供 token 雜湊等非檔案用途。 */
    public static String hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
```

```java
package com.gigaxfer.sync.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 由 NodeAuthFilter 放入 request attribute 的呼叫者 Node 名。 */
public final class CallerIdentity {
    static final String ATTR = "gigaxfer.caller";

    private CallerIdentity() {}

    public static String of(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no caller identity");
        }
        return (String) v;
    }

    /** `target` 由身分取得；請求另帶且不同 → 403（D14 修 2）。 */
    public static void requireTarget(HttpServletRequest req, String targetParam) {
        String caller = of(req);
        if (targetParam != null && !targetParam.equals(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "target " + targetParam + " is not the caller " + caller);
        }
    }
}
```

```java
package com.gigaxfer.sync.auth;

import com.gigaxfer.core.config.NodeConfig;
import com.gigaxfer.core.digest.Sha256;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Node 間端點認證（D14 修 2）：Authorization: Bearer <token> → sha256 → 查 peer_token_sha256 反查 Node 名。
 * 驗證端只持有雜湊。不用 Spring Security：一個 header、一張表。
 */
@Component
public class NodeAuthFilter extends OncePerRequestFilter {
    static final List<String> PROTECTED_PREFIXES = List.of("/pending", "/file", "/report", "/received");

    private final Map<String, String> nodeByTokenHash;

    public NodeAuthFilter(NodeConfig config) {
        Map<String, String> m = new HashMap<>();
        config.operational().peerTokenSha256().forEach((node, hash) -> m.put(hash, node));
        this.nodeByTokenHash = Map.copyOf(m);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String p : PROTECTED_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String token = header.substring("Bearer ".length()).strip();
        String node = nodeByTokenHash.get(Sha256.hex(token.getBytes(StandardCharsets.UTF_8)));
        if (node == null) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        req.setAttribute(CallerIdentity.ATTR, node);
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 4: 跑測試**

Run: `mvn -q test`
Expected: 全綠；NodeAuthFilterTest 9 個。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-sync-service gigaxfer-core/src/main/java/com/gigaxfer/core/digest/Sha256.java
git commit -m "feat(sync): per-node bearer token auth filter with hashed peer tokens (D14 rev 2)"
```

---

### Task 7: README 與設計偏差紀錄

**Files:**
- Create: `gigaxfer-sync-service/README.md`
- Modify: `docs/design/design-decisions.md`（在 `P01 偏差 2` 列之後加 `P02 偏差` 列）
- Modify: `docs/design/file-inventory.md`（「sync 主機本機磁碟」表加 `candidate.json.tmp` 被忽略的說明）

- [ ] **Step 1: 寫 README**

````markdown
# gigaxfer-sync-service

每 Node 一個 process 的同步服務。本模組目前為骨架（P02）：設定啟用、schema、認證、health、`/policy`。掃描、傳輸、對帳由 P03 起加入。

## 執行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
mvn -q -pl gigaxfer-sync-service -am package -DskipTests
java -jar gigaxfer-sync-service/target/gigaxfer-sync-service-0.1.0-SNAPSHOT.jar \
  --gigaxfer.node=P1 \
  --gigaxfer.config-dir=/var/lib/gigaxfer/config \
  --gigaxfer.token-file=/etc/gigaxfer/node.token \
  --gigaxfer.nfs-root=/mnt/files \
  --spring.datasource.url=jdbc:oracle:thin:@//db:1521/FREEPDB1 \
  --spring.datasource.username="$P02_DB_USER" --spring.datasource.password="$P02_DB_PASSWORD"
```

本機參數（不是 config 版本的一部分）：

| 屬性 | 意義 | 預設 |
| --- | --- | --- |
| `gigaxfer.node` | 本 Node 名，必須在 `policy.nodes` 內 | 無，必填 |
| `gigaxfer.config-dir` | `candidate.json` / `active.json` / `lkg.json` 所在目錄 | 無，必填 |
| `gigaxfer.token-file` | 本 Node 明文 token 檔（不進 git、不進 config） | 無，必填 |
| `gigaxfer.nfs-root` | NAS mount root（`hard` mount） | 無，必填 |
| `gigaxfer.nfs-timeout` | 每個 NFS 操作的 timeout | 30s |
| `gigaxfer.nfs-slots` | 有界執行器槽數（D51） | 16 |

## 啟動序列（D34、D34 修、D45）

1. 讀 `config-dir`：有 `candidate.json` 先驗證（schema、`version` 遞增、`policy` 段與現行完全相同、`fixed` 段等於 v1 常數）；通過 → `active.json`→`lkg.json`、`candidate.json`→`active.json`；失敗 → 留原地、`activation_failure_count`=1、用現行。`active.json` 缺或壞 → 用 `lkg.json`；皆缺 → 拒絕啟動。
2. 起 HTTP。`/policy`、`/actuator/**` 立即可用。
3. 背景執行 Flyway migration 與 bootstrap 列（`node_meta`、`seq_counter`），失敗每 5 s 重試；期間 `/actuator/health/readiness` 的 `db` 為 DOWN，process 不退出。

設定 process 內不可變。改 operational policy = CD 放新 candidate 後 `systemctl restart`。

## 設定檔格式

見 `docs/superpowers/plans/P02-sync-service-skeleton.md`「設定檔格式」。`peer_token_sha256` 放 operational 段：`printf '%s' "$TOKEN" | shasum -a 256`。

## 端點

| 路徑 | 認證 | 說明 |
| --- | --- | --- |
| `GET /policy` | 無（Node 內） | `{"version": N, "policy": {...}}`，library 用 |
| `GET /actuator/health/readiness` | 無 | `db`、`nfs` 兩個 component |
| `GET /actuator/health/liveness` | 無 | process 存活 |
| `GET /actuator/prometheus` | 無 | 指標；`node` 標籤自動附加 |
| `/pending`、`/file/**`、`/report`、`/received` | `Authorization: Bearer <token>` | Node 間端點；本模組只提供 filter，端點由 P04 起實作 |

401 = 無 / 未知 token；403 = `target` 參數與呼叫者身分不同（D14 修 2）。TLS 由 `server.ssl.*` 部署設定提供（D14），本模組測試走明文。

## 指標（P02）

`active_config_version{node}`、`activation_failure_count{node}`（單次 process 0/1；歷史用 `max_over_time`）、`db_health{node}`、`storage_health{node}`。
````

- [ ] **Step 2: 加 design-decisions 偏差列**

在 `P01 偏差 2` 列後加一列（表格三欄）：

```
| P02 偏差 | 實作計畫 `docs/superpowers/plans/P02-sync-service-skeleton.md` 定的六項：① 設定檔 JSON 格式（`schema_version`、`version`、`published_by`、`published_at`、`policy{deployment,nodes,namespaces[],required_targets[]}`、`operational{…}`、`fixed{…}`）由本計畫定義，未知欄位拒絕；`fixed` 可省略、存在必須等於 v1 常數；② 各 Node token 的 sha256 放 config 的 operational 段 `peer_token_sha256`（D30「每 Node 憑證輪替」為可調項），本 Node 明文 token 在 `gigaxfer.token-file` 秘密檔；本 Node 雜湊與 config 不符只警告不阻擋（輪替窗口）；③ Node 內端點（`/policy`、`/locate`、`/actuator/**`）不認證，Node 間四個端點以 Bearer token 認證，不用 Spring Security；④ `activation_failure_count` 為單次 process 的 0/1 gauge（process 無狀態，D34），歷史以 Prometheus `max_over_time` 看；⑤ DB migration 在背景執行緒重試（每 5 s），Spring 的 Flyway 自動執行關閉，Hikari `initialization-fail-timeout=-1`，實現 D34 修「DB 連不上不退出」；⑥ 欄位改名避開保留字：`rebuild_progress.cursor`→`cursor_seq`、`inspection.unknown`→`unknown_count`；`node_meta` 以 `singleton=1` 單列約束；identity 外鍵以 (source_node, namespace, logical_key) 三段自然鍵，文件中的 `identity_ref` 為其邏輯名 | D14 修 2, D17, D24 修, D30, D34, D34 修, D45, file-inventory |
```

- [ ] **Step 3: file-inventory 補一句**

在「sync 主機本機磁碟」表 `candidate.json.tmp → candidate.json` 列的「生命週期」欄尾加：「sync service 只認 `candidate.json`，`.tmp` 一律忽略」。

- [ ] **Step 4: 全量測試**

Run: `mvn -q test`
Expected: 全綠。

- [ ] **Step 5: Commit**

```bash
git add gigaxfer-sync-service/README.md docs/design/design-decisions.md docs/design/file-inventory.md
git commit -m "docs(sync): sync-service README, P02 design deviations, file inventory note"
```

---

## Self-review

**Spec coverage**
- §13 / AC-CFG-01～05、D17、D45：Task 2（驗證、rename 順序、crash 中間狀態、LKG 回退、candidate 留原地）+ Task 3（啟動失敗即拒絕、`active_config_version`）。AC-CFG-04 的「發布者、時間」= `published_by` / `published_at` 欄位（Task 1）+ git commit。
- D30 修 5 / 修 6：`FixedConstants.V1` 與 fixed 段相等檢查（Task 1）。
- D24 修 全表（含 epoch、incarnation、seq_counter、rebuild_progress、node_meta）：Task 4。
- D14 修 2：Task 6（雜湊、身分、target 403）。
- D34 / D34 修：Task 3（啟動序列）+ Task 4（背景重試、不退出）+ Task 5（health DB / NFS）。
- SR-01 Namespace／Data class 登錄：Task 1 的 `Policy.allowsWrite`、輸入驗證与變更比對；Task 3 的 `/policy` 回傳；實際 write gate 由 P10 整合。
- D18 `/policy`：Task 3。
- monitoring.md 指標名：Task 3、Task 5。
- 未涵蓋（刻意）：`/locate`（P10 負責 server／client，資料依賴 P03／P05，恢復進度依賴 P07／P08）、ops API（P11）、TLS 憑證（部署）。

**Placeholder scan**：實作區塊提供程式碼與測試；既有工作按本輪接續基準增量修改。配置中的 token 為測試 fixture，不作部署憑證。

**Type consistency**：`Policy(deployment, nodes, namespaces, requiredTargets)` 與 JSON fixture、codec、endpoint 測試一致；`ConfigActivation(NodeConfig, Source, Optional<String>)` 在 Task 2、3 一致；`DbState.awaitReady/ready/lastError/runOnce` 在 Task 4、5 一致；`Sha256.hex(byte[])` 在 Task 6 兩處一致；`SyncProperties` 六欄在 Task 3、5、6 一致；fixture 的 P1/P2 雜湊在 Task 3 Step 3 改後，Task 1 測試錨點同步更新。

## 交付前覆蓋核對

- [ ] Ticket 每條驗收条件對到上述 task 與實際測試名稱；已完成項有目前 commit 的證據。
- [ ] Config 模型位於 core；Namespace 名單與 `deployment` 在 v1 初始化 fixture、codec、Policy endpoint 一致，Policy 名單變動仍被 candidate 驗證拒絕。
- [ ] `received` 可保存遠端 identity 的 data class、content path 與基準，不依賴本 Node Source 的 identity 列。
- [ ] 測試區分 H2/local filesystem 與 Oracle／NAS；未執行 Oracle／NAS 時不能宣告其相容與持久化保證已驗收。
- [ ] 本檔修訂套入工作分支前先核對最新 ledger；所有舊工作的新修正均保留，未完成項才續行。
- [ ] `docs/validation/P02-validation.md` 記錄 base／HEAD、環境、命令、結果、仍待驗證項目；PR 引用 ticket 與本 plan。

本輪只交付 ticket 與計畫修訂，沒有執行上述實作步驟，也沒有以原本的測試通過數替新增 Namespace 契約背書。

---

## 修正 tasks（2026-09-23 接續審查後新增；依 ledger 續行）

以下是把本修訂套到 `0f3f916` 之後**必須額外執行**的工作。Task 1–3 在舊 ledger 已標 complete，但受本修訂影響；不得因舊標記略過。每個修正 task 走完整 implementer → review 流程。Task 4–7 未開始，直接依上文（含本節補充）執行。

### Task 1R：Namespace 登錄與 `deployment`（gigaxfer-core）

**Files:** Modify `gigaxfer-core/src/main/java/com/gigaxfer/core/config/{Policy,ConfigCodec}.java`、`gigaxfer-core/src/test/java/com/gigaxfer/core/config/ConfigCodecTest.java`、`gigaxfer-core/src/test/resources/config/v3.json`、`gigaxfer-sync-service/src/test/resources/config/v3.json`。

- [ ] Step 1：兩份 fixture `v3.json` 的 `policy` 段改為上文「設定檔格式」：`"fab"` → `"deployment": "example-deployment"`，加 `"namespaces": ["transactions", "analytics"]`。
- [ ] Step 2：`ConfigCodecTest` 依上文 Task 1 Step 1 更新：`fab` 斷言改 `deployment`；`encode_policy_is_single_line_snake_case_json` 改為 `startsWith("{")` + `contains("\"namespaces\":[\"analytics\",\"transactions\"]")`；新增 `registry_rejects_unregistered_namespace_but_allows_local_only_class`、`namespace_registry_is_validated_and_part_of_policy_identity`；新增 `rejects_duplicate_peer_token_hash`（P2 的雜湊改成與 P1 相同 → InvalidConfigException，訊息含 "uniquely"）。跑到紅。
- [ ] Step 3：`Policy` 改為 `Policy(deployment, nodes, namespaces, requiredTargets)`，加 `isNamespaceRegistered`、`allowsWrite`，`normalized()` 排序 namespaces（上文 Task 1 Step 4 的版本）。
- [ ] Step 4：`ConfigCodec.validatePolicy` 加 namespaces 驗證（非 null、每項合法 segment、不重複）；`validateOperational` 加 token 雜湊唯一性（上文 Task 1 Step 5 的版本）；`policy.fab is required` 改 `policy.deployment is required`。
- [ ] Step 5：`mvn -q test` 全綠（sync-service 的 ConfigStoreTest / PolicyEndpointTest 因 fixture 改動仍須通過；`PolicyEndpointTest` 對 `$.policy.fab` 的斷言在 Task 3R 改，本 task 先讓它以 `deployment` 通過或暫時移除該行——**選擇改為 `deployment`**）。
- [ ] Step 6：commit `feat(core): namespace registry, deployment id and unique peer token hashes in Policy`。

### Task 2R：candidate-only 初始化拒絕、Namespace 變更拒絕（gigaxfer-sync-service）

**Files:** Modify `gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigStore.java`、`.../test/.../ConfigStoreTest.java`。

- [ ] Step 1：`ConfigStoreTest`：把 `first_initialisation_accepts_candidate_when_nothing_else_exists` 改成 `candidate_alone_does_not_bypass_manual_initial_active_setup`（上文 Task 2 Step 3 版本：拋 `ConfigUnavailableException`、active 不存在、candidate 仍在）；新增 `rejects_candidate_that_changes_registered_namespaces`。跑到紅（前者）。
- [ ] Step 2：`ConfigStore.load()`：在檢查 candidate 之前加 `if (baseline.isEmpty()) throw new ConfigUnavailableException("no valid active or lkg config; initialise active.json first");`（上文 Task 2 Step 5 版本）。ADR-0003 / D17：首次初始化由人工放 active.json。
- [ ] Step 3：`mvn -q test` 全綠；commit `fix(sync): refuse candidate-only initialisation; namespace change is a policy change`。

### Task 3R：`/policy` 輸出 namespaces、設定不可變契約測試、補做 Task 3 審查

**Files:** Modify `gigaxfer-sync-service/src/test/java/com/gigaxfer/sync/config/PolicyEndpointTest.java`（production 的 `PolicyController` 直接輸出 `ConfigCodec.encodePolicy`，namespaces 隨 Task 1R 自動出現，預期不需改 production）。

- [ ] Step 1：`PolicyEndpointTest.policy_endpoint_returns_version_and_normalised_policy_without_auth` 加 `$.policy.deployment == "example-deployment"`、`$.policy.namespaces[0] == "analytics"`、`$.policy.namespaces[1] == "transactions"`。
- [ ] Step 2：新增測試 `config_is_immutable_while_process_runs`（P02-04）：

```java
    @Test
    void config_is_immutable_while_process_runs() throws Exception {
        java.nio.file.Path active = configDir().resolve("active.json");
        String original = java.nio.file.Files.readString(active);
        java.nio.file.Files.writeString(active, original.replace("\"version\": 3", "\"version\": 9"));
        try {
            mvc.perform(get("/policy")).andExpect(jsonPath("$.version").value(3));
            assertThat(meters.get("active_config_version").tag("node", "P1").gauge().value()).isEqualTo(3.0);
        } finally {
            java.nio.file.Files.writeString(active, original);
        }
    }
```

- [ ] Step 3：`mvn -q test` 全綠；commit `test(sync): /policy exposes namespaces; config immutable while running`。
- [ ] Step 4：控制器對 `1617096..HEAD`（Task 3 + 3R）做一次 task review（Task 3 原審查未完成）。

### Task 4 補充（併入 Task 4 執行）

- `V1__schema.sql` 的 `received` 含 `content_path VARCHAR(1024) NOT NULL`（上文已改）。
- `SchemaTest` 用上文的 `bootstrap_is_idempotent_across_restarts`（非零計數器與 rebuild 旗標保留）與 `remote_received_keeps_source_selected_path_without_local_source_row`。
- 新增 P02-08 測試 `db_becomes_ready_without_restart_after_outage`：以 `@TestConfiguration` 提供包裝 `DataSource`（`getConnection()` 在旗標 `down=true` 時丟 `SQLException`），測試開始時 `down=true` → 等 2 個重試週期（`DbBootstrap.RETRY_MILLIS`，測試 profile 以 `gigaxfer.db-retry-millis=200` 縮短）確認 `db.ready()==false`、`/actuator/health` 的 `db` 為 DOWN 且 `/policy` 200 → `down=false` → `awaitReady(10 s)` 為 true。為此 `DbBootstrap.RETRY_MILLIS` 改為讀 `SyncProperties.dbRetryMillis()`（預設 5000；`SyncProperties` 加第七欄 `Long dbRetryMillis`，null 視為 5000）。此測試放獨立類別 `DbOutageRecoveryTest`（自己的 context，不繼承 `SyncTestSupport` 的共用 context 以免污染）。

### Task 5 補充（併入 Task 5 執行）

- `HealthEndpointTest` 加 `nfs_component_is_down_when_probe_times_out`：以 `@TestConfiguration` + `@Primary` 提供 `NfsExecutor`，其 `call` 直接丟 `new NfsTimeoutException("stat-root")`（建構子簽章以 P01 實際為準）→ `components.nfs.status == DOWN`、detail `op == stat-root`。放獨立類別 `NfsTimeoutHealthTest`。
- README「啟動序列」註明：systemd 只看 process 存活（`Restart=always`），不打 readiness；readiness DOWN 不觸發重啟（D34 修）。

### Task 6 補充（併入 Task 6 執行）

- `EchoCallerController` 加 `GET /received`（只回 `{"caller": …}`，**不呼叫** `requireTarget`）；`NodeAuthFilterTest` 加 `received_does_not_apply_target_equals_caller_rule`：`/received?target=P3` + P2 token → 200、caller=P2。README 明寫「只列出 caller 為 Source 的資料」交 P04。

### Task 7 補充（併入 Task 7 執行）

- 新增 `docs/validation/P02-validation.md`：base/HEAD、環境（arm64 macOS、JDK 27 `--release 21`、H2 2.x Oracle mode、本機檔案系統）、命令、逐 AC（P02-01～12）對應測試名稱與結果、尚未驗證（Oracle 實機、真實 NAS、HTTPS、跨 Node）。
- README 補：首次初始化步驟（人工放 active.json）、設定更新 / 失敗回退操作、DB 斷線復原觀察方式、認證檢查 `curl` 範例、health / metrics 回應範例、HTTPS 部署要求。
- commit 署名：記錄實際執行者（subagent 用其實際模型名），不預填。

## Ticket AC 對照（接續審查時的狀態，基準 `0f3f916`）

| AC | 對應 task | 既有實作 / 測試證據 | 缺口 → 修正 |
| --- | --- | --- | --- |
| P02-01 獨立啟動 | Task 3、5 | `ConfigBootstrap` 檢查 node ∈ nodes；`PolicyEndpointTest`（3）；啟動不掃描（P03 才有掃描器） | health 待 Task 5 |
| P02-02 Policy 登錄契約 | Task 1R、3R | `Policy.targetsFor` 已分辨未登錄 / 空 targets | Namespace 名單、`isNamespaceRegistered`、`allowsWrite`、`/policy` 輸出 → 1R、3R |
| P02-03 設定驗證 | Task 1、1R、2 | `ConfigCodecTest`（17）、`ConfigStoreTest` 版本 / policy 拒絕 | namespaces 驗證、token 雜湊唯一 → 1R |
| P02-04 安全啟用 | Task 2、3、3R | `accepts_candidate_that_only_changes_operational_and_node_order`；gauge = version | 運行期改檔不影響記憶體 → 3R 測試 |
| P02-05 失敗與回退 | Task 2、2R、3 | 拒絕留 candidate、lkg 回退、壞 active 不覆蓋 lkg（`1617096`）、`activation_failure_count` | candidate-only 初始化拒絕 → 2R |
| P02-06 中斷恢復 | Task 2 | `crash_between_renames_recovers_on_next_start` | 設定啟用不觸碰 DB，義務與控制狀態由 schema 持有（Task 4） |
| P02-07 schema 與冪等 bootstrap | Task 4 | 無 | 全部 → Task 4（含 `received.content_path`、非零計數器保留） |
| P02-08 DB 故障恢復 | Task 4、5 | 無 | `DbOutageRecoveryTest` → Task 4 補充 |
| P02-09 health 語意 | Task 5 | 無 | 含 NFS timeout → Task 5 補充 |
| P02-10 Node 認證 | Task 6 | 無 | 全部 → Task 6 |
| P02-11 角色身分 | Task 6 | 無 | `/received` 不套 target 規則 → Task 6 補充 |
| P02-12 可交接可重現 | Task 7 | `gigaxfer-core/README.md` 只涵蓋 P01 | README + `docs/validation/P02-validation.md` → Task 7 |

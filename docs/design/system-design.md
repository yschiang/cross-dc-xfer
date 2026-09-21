# Cross-Node File Synchronization — System Design

Version: 1.0-draft  
Date: 2026-09-22  
依據：`docs/spec.md`（v0.3）、`CONTEXT.md`、`docs/design/design-decisions.md`（D1–D26）、`docs/adr/`  
對照表：`docs/design/traceability.md`

## 0. 一頁摘要

- 每個 Node（Fab 內一個 Phase）= 自有 NAS + 自有 Oracle + 一個 sync service process + 若干 Application 主機（嵌入 Framework library）。跨 Node 只共用 git（設定）、CD、AD/DNS。
- **NFS 是事實，DB 是索引**：Source Ready = `<key>.manifest` 存在且 `<key>` 已 link 到位；Target Ready = `<key>` 已 link 到位。DB 遺失可由 NFS 重建。
- **Target pull**：Target 週期向每個 Source 拉待辦、streaming 下載、本地驗 digest、link 發布、回報。義務與退避狀態只在 Source。
- **沒有 Control Plane process**：設定在 git，CD 下發，Node 端驗證啟用並保留 LKG；全域視圖由 CLI 扇出。
- 對外 endpoint 每 Node 五個：`/pending`、`/file`、`/report`、`/received`、`/policy`，加本地 ops API。

## 1. 元件與部署（§21.2 Component / Deployment）

### 1.1 一個 Node 的形狀

```
┌─ Node (Phase) ───────────────────────────────────────────────┐
│  App host ×N                sync host ×1                     │
│  ┌──────────────┐           ┌──────────────────────────┐     │
│  │ Application  │           │ sync service (Spring Boot)│     │
│  │ + xfer lib   │           │  scanner / puller /       │     │
│  └──────┬───────┘           │  reconciler / ops API     │     │
│         │ NFS mount         └──┬──────────┬────────────┘     │
│         ▼                      │ NFS      │ JDBC             │
│  ┌──────────────┐              ▼          ▼                  │
│  │   NAS (NFSv3)│◄────────────┘   ┌──────────────┐          │
│  └──────────────┘                 │ Oracle (xfer │          │
│                                   │  schema)     │          │
│                                   └──────────────┘          │
└──────────────────────────────────────────────────────────────┘
        ▲ HTTPS (pull)                    ▲ CD 下發 config
        └── 其他 Node 的 sync service ─────┘
```

| 元件 | 部署 | 責任 |
| --- | --- | --- |
| **xfer library**（Spring Boot starter） | 嵌入每個 Application | write / Finalize / Discard / exists / read；Policy 快取；容量檢查；每個 NFS 操作獨立 thread + timeout |
| **sync service** | 每 Node 一台主機、一個 process，監控快速重啟（D5） | 掃描 ingest、對外 endpoint、pull 其他 Source、reconciliation、Deep check、ops API、metrics |
| **NAS** | 每 Phase 獨立，App 主機與 sync 主機共 mount（D7、Q12） | 資料與 manifest 的真相 |
| **Oracle** | 每 Phase 自有，Application 既有；sync service 用獨立 schema，唯一寫入者（D7、D9） | 索引、義務狀態、received、inspection、audit |
| **git repo + CD** | 既有 | 設定真相與下發（D17） |
| **CLI** | ops 工作站 | 包本地 ops API；扇出查全 Fab（D20） |

### 1.2 共用依賴與故障影響

| 依賴 | 故障影響 |
| --- | --- |
| 本 Node NAS | 本 Node 全停：write 拒絕、本 Node 為 Source 的 pull 無法服務、本 Node 為 Target 停止拉取 |
| 本 Node Oracle | Application 自身交易已停；sync service 暫停（重啟後由 NFS 重建索引） |
| 本 Node sync service | Local Transaction 不受影響（library 只碰 NAS + 快取 Policy）；replication / reconciliation 暫停，lag 上升 |
| 其他 Node 任何元件 | 只影響對該 Node 的義務；其餘 Target 與本地交易不受影響 |
| git / CD | 不能發新設定 |
| AD / DNS | 主機名解析：sync service 間 HTTPS 受影響；NAS mount 已建立者不受影響 |

## 2. 資料佈局與 Finalize 協議（§21.2 state persistence）

### 2.1 NFS 佈局（D2）

```
<mount root>/<namespace>/<data class>/<yyyy-mm-dd>/<HH>/<logical key>            ← 內容，存在 = Ready
<mount root>/<namespace>/<data class>/<yyyy-mm-dd>/<HH>/<logical key>.manifest   ← Source 端 Integrity baseline
<mount root>/<namespace>/<data class>/<yyyy-mm-dd>/<HH>/<logical key>.<uuid>.writing
```

- 日期時間 = Finalize 時 App 主機時鐘（Node 內 NTP 同步，D26）。
- Target 落地用**同一路徑**；consumer 在任一 Node 以 identity 推同一路徑。
- 10⁵ 檔/日 ÷ 24 ≈ 4k 項/小時目錄。

### 2.2 Manifest（單行 JSON）

```json
{"schema_version":1,"source_node":"P3","namespace":"mes","data_class":"metrology",
 "logical_key":"L123-R2","size":1048576,"digest":"sha256:…","uuid":"…","source_ready_at":"2026-09-22T08:15:03.123Z"}
```

### 2.3 Finalize 協議（D3、D3a、D4）

| 步 | 操作 | 失敗語意 |
| --- | --- | --- |
| 0 | `beginWrite`：驗 Namespace / Data class 已登錄、容量未達 reject 門檻 | 拒絕，不產生任何檔 |
| 1 | 內容 streaming 寫 `<key>.<uuid>.writing`，同時算 digest；fsync | FAILURE，library 清 `.writing` |
| 2 | `<key>.manifest` **O_EXCL create** + write + fsync | EEXIST → 讀既有：digest 同 → 續行（冪等）；不同 → FAILURE(CONFLICT) |
| 3 | `link(<key>.<uuid>.writing, <key>)` — **commit point** | EEXIST → 既有 `<key>` digest = manifest → SUCCESS（冪等）；否則 CONFLICT |
| 4 | unlink `.writing` | 失敗只記 log，掃描會清 |

任一步 timeout / I/O 結果不明 → **PENDING_CONFIRMATION**；Application 重呼叫 `finalize()` 走同一序列，每步冪等。

Framework 額外 NFS 成本：每筆 1 create + 1 fsync + 1 link + 1 unlink。

### 2.4 Rediscovery 規則（D3、D11）

| NFS 狀態 | 判定 | 動作 |
| --- | --- | --- |
| manifest ✓、`<key>` ✓ | Source Ready | ingest |
| manifest ✓、`<key>` ✗、`.writing` ✓ | 步 3 中斷 | 補 link（digest 驗過） |
| manifest ✓、`<key>` ✗、`.writing` ✗ | Application 從未拿到 SUCCESS | manifest 標 Abandoned |
| 只有 `.writing`，mtime > TTL（預設 24h） | Abandoned | DB 記錄，檔案留原地 |

### 2.5 DB schema（D24）

| 表 | 用途 |
| --- | --- |
| `file_identity` | manifest 的索引（source_node, namespace, logical_key, data_class, size, digest, source_ready_at, path） |
| `obligation` | Source 端義務：identity_ref, target_node, state, attempts, next_attempt_at, last_error, completed_at；索引 (target_node, state, next_attempt_at) |
| `received` | Target 端已發布：identity_ref, size, digest, published_at |
| `inspection` | 每次 reconciliation：scope, cutoff, started_at, finished_at, checked, unknown, diffs, repairs |
| `ops_audit` | who, at, action, scope, reason, result |
| `target_liveness` | target_node, last_pending_at |

SQL 保持可攜；本機 / 單元測試用 H2 Oracle mode，CI 用 Oracle Free container（D9）。單一 process 為唯一寫入者，不需 row lock。

### 2.6 DB 重建

Source 側：重建掃（§4.1 第三層）→ 全部 manifest ingest → 每個 identity × Policy targets 建 obligation → 對每個 Target `GET /received` 補 COMPLETED。  
Target 側：掃正式檔 → 重算 digest → 對 Source `file_identity`（經 `/pending` 附帶欄位或 `/file` HEAD）比對 → 重建 received。慢但正確，僅在 Oracle 備份間隙需要。

## 3. 傳輸協議（§21.2 transfer protocol）— Target pull（D12、D12a、ADR-0002）

### 3.1 端點（sync service 對外，HTTPS + shared token，D14）

| 端點 | 方向 | 語意 |
| --- | --- | --- |
| `GET /pending?target=<self>&limit=N` | Target → Source | 回該 Target 最舊 N 筆「未 COMPLETED、非 BLOCKED/QUARANTINED、next_attempt_at ≤ now」的義務，附 manifest 欄位；FIFO by source_ready_at（D13）。同時更新 `target_liveness` |
| `GET /file/<identity>` | Target → Source | streaming 內容（`StreamingResponseBody`）；`Content-Length` = size |
| `POST /report` | Target → Source | `{identity, target, kind: DONE\|FAILED\|VERIFIED, digest?, reason?}` |
| `GET /received?after=<seq>&limit=N` | Source → Target | Target 的 received 表增量（reconciliation 用） |
| `GET /policy` | library → 本 Node sync | active config 的 Policy 段（D18） |

### 3.2 Target 拉取迴圈

```
for each source in Policy.sources_for(self):
    batch = GET source/pending?target=self&limit=N
    for item in batch (skip if in in-flight set or in received):
        stream GET /file → <path>.<uuid>.writing, DigestInputStream
        if size/digest mismatch: unlink; POST /report FAILED(INTEGRITY); continue
        link(.writing, <key>)   # EEXIST → 既有檔 digest 對 → 視為已發布
        unlink .writing
        INSERT received
        POST /report DONE
```

- 兩端 streaming，記憶體只有 buffer；100 MB 在 LAN 上秒級，整檔重傳，timeout = base + size / 最低可接受頻寬。
- Target 唯一狀態 = in-memory in-flight set。crash 後重問 pending。
- 拉取前 statfs 低於 reject 門檻 → 不拉，`POST /report FAILED(CAPACITY)`（D21）。
- 並發、bytes/s 為 Target 端 operational policy。

### 3.3 Source 端狀態機（§14）

| 事件 | 轉移 |
| --- | --- |
| ingest | → PENDING（每個 Required target 一筆） |
| 出現在 `/pending` 回應 | → IN_PROGRESS（純顯示，逾時未回報自動回 PENDING） |
| DONE | → COMPLETED |
| FAILED(transient) | → RETRY_WAIT，attempts++，next_attempt_at = now + backoff(10 s → 15 min 上限) |
| FAILED(INTEGRITY / 4xx) 達門檻 | → QUARANTINED，保留 expected / observed |
| FAILED(CAPACITY) | → BLOCKED(reason=capacity) |
| ops pause(target) | → BLOCKED(reason=paused)；resume → PENDING |
| VERIFIED 不符 / shallow check 發現 Target 遺失 | COMPLETED → PENDING，保留原 completed_at 於歷史 |
| `target_liveness` 過期 | Target 顯示 UNREACHABLE（不改義務狀態） |

### 3.4 `/report` 遺失（T19）

下輪 `/pending` 重拿 → Target 先查 received（有 → 直接重送 DONE）→ 無則 stat `<key>`（有 → 重算該檔 digest 對基準 → 補 received、送 DONE）→ 皆無才下載。不重複發布、不破壞內容。

## 4. 掃描與 Reconciliation（§21.2 reconciliation algorithm）

### 4.1 三層掃描，同一段冪等 ingest（D6、D10、D26）

| 層 | 範圍 | 週期 | 目的 |
| --- | --- | --- | --- |
| 增量掃 | 當前 + 前兩小時目錄 | 每 N 秒（預設 5） | 新檔發現；正常 lag ≈ N + 目錄 cache |
| 全量對帳掃 | 最近 D 天（預設 7） | 每 M 小時 | lost trigger、從未建立的 task；附截止點與覆蓋範圍 |
| 重建掃 | 全部 | 人工觸發 | DB 重建 |

不做 library → sync service 通知；lag SLO 若要秒級再加（純加法）。

### 4.2 Shallow check（D19，每輪對帳掃後）

1. NFS manifest 集合 vs `file_identity` → 差集 ingest。
2. 對每個 Target `GET /received?after=` 增量 vs `obligation`：Source COMPLETED 但 Target 無 → 重開；Target 有但 Source 未完成 → 補 COMPLETED；digest 紀錄不符 → QUARANTINED。
3. 寫 `inspection`；Target 不可達 → unknown，保留上次結果與時間。

### 4.3 Deep check（D16）

Target 自己滾動讀本地正式檔重算 digest（每輪 1/K，K 天輪完），`POST /report VERIFIED`；Source 不符 → 重開義務，保留原完成紀錄與新發現。K = T30 偵測時限，§18 TBD。

## 5. NFSv3 具體處理（§21.2 NFS client / mount / HA）

| 項目 | 設計 |
| --- | --- |
| mount | App 主機與 sync 主機皆 `hard`（不假失敗）；sync 主機加 `lookupcache=positive`、`acdirmax` 調低（D3b） |
| 卡住 I/O 隔離（SR-05） | library 與 sync service 每個 NFS 操作在獨立 thread + timeout；逾時回 PENDING_CONFIRMATION / 該義務 FAILED，thread 留著等 kernel 收回，不阻塞其他工作；thread pool 有上限，滿了直接拒絕新操作而非排隊 |
| 原子操作依賴 | O_EXCL create（NFSv3 exclusive create）、`link`（目標存在則 EEXIST）、同目錄 unlink |
| Storage failover / stale handle | 操作回 ESTALE → 視為結果不明 → PENDING_CONFIRMATION；重試以路徑重新 lookup |
| 跨 client 可見性 | 內容以 link 原子出現；manifest 半截（parse 失敗）視同不存在 |
| SR-03 驗證清單 | ① O_EXCL create 在目標 NAS 上真的 exclusive；② link 在目標存在時回 EEXIST；③ failover 期間進行中的 write / link 結果；④ ESTALE 後重 lookup 行為；⑤ `acdirmax` 設定下新目錄項可見延遲；⑥ hang 情境下 thread 隔離不擴散 |

## 6. 設定（§13、D17、D18、ADR-0003）

- git：`configs/v<N>.json` + `latest`；PR review = 授權；commit = 發布者 / 時間。
- CD：送到各 sync 主機 `/var/lib/xfer/config/candidate.json.tmp` → rename `candidate.json`。
- Node 端：schema 驗證 → 版本嚴格遞增 → **Policy 段與 active 完全相同**（否則拒絕、上報、active 不動）→ rename active→lkg、candidate→active → 熱載入 operational policy → 曝 `active_config_version`。
- 啟動：讀 active，缺則 lkg，皆缺拒絕啟動。
- Application：library 啟動 `GET /policy` 快取記憶體 + App 主機本機 fallback 檔；拿不到則 write 明確回錯，不擋 App 啟動。
- Operational policy 項目：掃描間隔 N、對帳範圍 D、對帳週期 M、Deep check 分母 K、Writing TTL、退避參數、Target 拉取並發 / bytes/s、容量 warn / reject 門檻、shared token、ops token。

## 7. Ops、Metrics、容量（§15–§17）

- 本地 ops API：`GET /status`（§15 十題）、`GET /obligations?state=…`、`POST /retry`、`POST /pause|resume?target=`、`POST /reconcile`、`POST /release <identity>`、`POST /evict --target <node> <identity>`、`GET /config`。全部寫 `ops_audit`。
- CLI 扇出：依 Policy 的 Node 清單並行查 `/status`，不可達標 unknown + 上次成功時間。
- Metrics：Spring Boot Actuator，§16 表逐項對應 DB group by；lag / age 時戳依 ADR-0001。
- 容量：library statfs（10 s 快取）write 前檢查；sync service statfs 供 metric 與告警；Target 拉取前檢查（D21）。`protected_source_bytes` = 有未完成義務的 identity size 總和；`backlog_bytes` = 未完成義務 size 總和（同一檔多 Target 重複計）。

## 8. Thread model（sync service 內）

| Loop | 週期 | 備註 |
| --- | --- | --- |
| scanner | N 秒 | 單 thread；ingest 冪等 |
| puller ×(每個 Source 一組) | 連續 | 每 Source 一個 thread pool（並發為 operational policy），互不阻塞 |
| reconciler | M 小時 | 單 thread |
| deep-checker | 連續低速 | 單 thread，I/O 節流 |
| config-watcher | 60 s | 監看 candidate.json |
| HTTP | Tomcat pool | `/file` streaming 不佔記憶體 |
| NFS op executor | 有界 pool | 所有 NFS 操作經此，timeout 隔離 |

## 9. 已明確不做（v1）

續傳、Data class 優先級、快慢通道、HTTP 通知、`.evidence` 檔、Abandoned 搬目錄、Target 端 pause、force-complete、Web UI、CP process、mTLS、跨 Fab、Retire、Policy 運行期變更。

## 10. 待填數字（§18 TBD 對應）

N（掃描間隔）、D、M、K、Writing TTL、退避上限、拉取並發 / bytes/s、容量門檻、`/pending` limit、in-flight 逾時、`target_liveness` 過期門檻、size-based timeout 的最低頻寬。全部為 operational policy，驗收前定值。

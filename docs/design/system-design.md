# Cross-Node File Synchronization Framework — System Design

Version: 0.1 draft
Date: 2026-09-22
依據：spec v0.3、CONTEXT.md、ADR-0001～0003、docs/design/design-decisions.md（D1～D56 及修訂）。本文只展開決策，不新增決策；每段標註來源 D 編號。

## 0. 設計目標與量測

全文的尺。每條目標對應一個可量的指標與一組驗收測試；後面每一章都要能回答「這對哪條目標、用哪個數字證明」。

| 目標 | 一句話 | 怎麼量 | 通過條件 | 驗收 |
| --- | --- | --- | --- | --- |
| DG-01 Node independence | 任一 Node 停機，其他 Node 的本地交易不受影響 | `local_write_availability{node}`、`local_write_latency{node}` 在故障 Node 之外 | 與正常時段相同 SLO（§18 TBD） | T02, T03, T12, AC-NODE-01～02 |
| DG-02 Local-first | Finalize 不等遠端、不等 CP | 同上，加 sync service 停機期間的量測 | Finalize 只碰本地 NAS，SLO 不變 | T02, T14, T23 |
| DG-03 Eventual consistency | 故障解除後自動補齊，期限內完成 | `oldest_unfinished_age`、`net_backlog_drain_rate`、`recovery_duration` | 24 h 停機後 24 h 內截止點集合全部 COMPLETED；不需人工 re-copy | T05, T16, T24, §20 |
| DG-04 No silent loss | 缺、壞、卡、衝突都在時限內被發現，未驗證者不標完成 | `reconciliation_coverage`、`unknown_count`、`integrity_failure_count`、`identity_conflict_count`、`unrecoverable_count`、Deep check 覆蓋週期 | 注入的缺漏在一個 Shallow 週期（6 h）內、損壞在一個 Deep 週期（量測後定）內被發現；錯誤完成數 = 0；unknown 明示不假報 | T10, T11, T20, T27, T30 |
| DG-05 Operational resilience | 重啟、升級、分割、暫時 outage、重複 trigger、中斷傳輸都自動恢復 | `process_start_time` + `up`、`accepted obligation 遺失數`、`activation_failure_count` | 已接受義務遺失數 = 0；重啟 30 s 內服務；LKG 保證完整設定 | T06～T09, T13, T26, T32 |

指標定義與門檻見 docs/design/monitoring.md；數值目標見第 10 章。

## 1. 架構元件

一個 Fab 內 ≤10 個 Node（Phase），彼此 full mesh。每個 Node 自成一個 Data Plane，跨 Node 共用的只有 git + CD（設定）、AD/DNS 與監控基礎設施，三者都不是資料流的即時依賴。

```
                 git repo (configs/v<N>.json)  ──CD──▶  每個 sync 主機本機磁碟
                 Prometheus / Grafana (每 Fab 一組)  ◀──scrape──  每個 Node

┌──────────────────────── Node A ────────────────────────┐      ┌──── Node B ────┐
│  App 主機 ×N                      sync 主機 ×1          │      │                │
│  ┌──────────────┐                ┌──────────────────┐  │ HTTP │  sync service  │
│  │ Application  │                │  sync service    │◀─┼──────┼─ GET /pending  │
│  │ + library    │                │  scan / serve /  │  │ pull │  GET /file     │
│  │ (Spring Boot │                │  pull / reconcile│──┼──────┼▶ POST /report  │
│  │  starter)    │                │  / cleanup / ops │  │      │                │
│  └──┬───────────┘                └──┬───────────┬───┘  │      └────────────────┘
│     │ NFS (hard)                    │ NFS       │ JDBC │
│  ┌──▼────────────────────────────────▼──┐   ┌───▼────┐ │
│  │  Local Storage (NAS, Node 自有)       │   │ Oracle │ │
│  │  <src>/<ns>/<bucket>/<key>.manifest │   │ sync   │ │
│  └───────────────────────────────────────┘   │ schema │ │
│                                              └────────┘ │
└──────────────────────────────────────────────────────────┘
```

| 元件 | 做什麼 | 決策 |
| --- | --- | --- |
| **library**（Spring Boot starter，跑在 Application process 內） | 提供 write / Finalize / Discard / exists / read 契約；streaming 算 digest；寫 manifest 與 link 發布；向本 Node sync service 取 Policy 與 `/locate`；statfs 容量檢查；曝 Local Transaction 指標 | D3, D4, D18, D21, D23, D15 修 |
| **sync service**（每 Node 一台主機、一個 process，另一台冷備） | 掃描 NAS 發現 Source Ready、建立與持有義務；對其他 Node 提供 `/pending` `/file` `/report` `/received`；作為 Target 向各 Source 拉取、驗證、發布；Target 端 Shallow / Deep 自查；清道夫；ops API、`/policy` 與 `/locate`；config 啟用。Java + Spring Boot（D38） | D5, D10, D12, D16, D17, D19, D20, D35, D38 |
| **Local Storage**（NAS，NFSv3 hard mount） | 唯一真相：`<key>` 存在 = Ready；manifest 為 Source Ready 權威紀錄 | D1, D2, D3b |
| **Oracle**（Node-local，sync 自有 schema） | 義務進度、received 索引、inspection、audit、liveness；缺列可由 NAS 全掃補建，完成證據由對帳 ② 以條件更新匯入 Target `/received` 歷史證據；rebuild 只補不刪 | D7, D9, D24, D29, D29 修 3 |
| **git + CD** | 設定真相與下發；沒有 CP process | D17, ADR-0003 |
| **Prometheus + Grafana + Alertmanager** | 呈現與告警；驗收與其不可用時以 CLI 扇出 `/status` 為準 | D27, D20 修 |
| **CLI** | 包 ops API；扇出各 Node `/status` 為全域視圖備援 | D20 |

每個 Node 同時是自己資料的 Source 與他人資料的 Target；sync service 是同一個 process 扮演兩個角色。

## 2. Runtime 架構

### 2.1 真相層級

| 層 | 內容 | 遺失後 |
| --- | --- | --- |
| NAS `<key>` + `.manifest` | Source Ready 集合、Integrity baseline | 資料損失，不在 v1 保證內 |
| Policy（active.json） | 應有集合 = manifest × Policy | 由 lkg 或 git 重取 |
| Oracle sync schema | 義務進度、received、inspection | D29 重建 |
| JVM 記憶體 | 已知 key 集合、Target in-flight set、active config | 重啟重載 |

任何一層只能由上一層重建，永不反向。Application 自己的「檔案已 Ready」記帳不是 Framework 真相（D7）。

### 2.2 資料流 A：寫入與 Finalize（Source 端，library）

```
beginWrite(ns, class, key)
  ├─ Policy 快取查 ns / class 已登錄，否則拒絕            (D18, Q16/Q17)
  ├─ statfs 低於 reject 門檻，拒絕                        (D21)
  └─ open <writing dir>/<key>.<uuid>.writing，DigestOutputStream；writing dir = 當下小時目錄，只放暫存，不是正式位置   (D3 ①, D4, D48 修)
write(bytes) ... 
finalize()
  ① fsync .writing
  ② content_path = 宣告時刻的小時目錄（確保存在）；寫 <bucket>/<key>.manifest.<uuid>.tmp {identity, class, size, digest, source_ready_at, uuid, content_path, schema_version} + fsync   (D48 修)
       link(tmp, <bucket>/<key>.manifest) 原子宣告 → unlink tmp；bucket = hash(key) 前 3 hex，與時間無關   (D44, D48)
       EEXIST → 讀既有 manifest（必為完整）：本次請求的 identity、class、size、digest 與之四項皆同 → 續行，content_path 以既有為準；任一不同 → FAILURE(CONFLICT)   (D3, D3a, D53)
       續行先 rediscovery：content_path/<key> 存在且 digest = manifest → SUCCESS，不看年齡；不存在且需再次嘗試發布 → manifest mtime 超過 N = 7 天 → FAILURE(DECLARATION_EXPIRED)，App 換 key；link 已送出結果未明 → PENDING_CONFIRMATION   (D53 修, D51 修 2)
  ③ link(.writing, manifest.content_path/<key>)  ← commit point = Source Ready；跨目錄 link 到 manifest 記的位置，不是暫存目錄也不是當下小時   (D48 修)
       EEXIST → 重讀 <key> 算 digest = manifest → 已完成（重試路徑的當下確認，D44）
  ④ unlink .writing 與 manifest tmp（失敗不影響結果，清道夫兜底）；manifest 本身永不刪   (D35, D53)
  → SUCCESS
任一步 timeout → PENDING_CONFIRMATION；Application 重呼 finalize() 走同一序列   (D3a, D8)
```

每個 NFS 操作交給有界執行器，槽位只在 syscall 真的回來才釋放，滿了立即拒絕（D51）。Application 拿到 SUCCESS 才 commit 業務交易（D8）。同一 identity 的正式位置由第一個宣告 manifest 的人決定，跨小時重跑仍撞 EEXIST（D48）。正式位置的小時目錄取宣告時刻，寫入橫跨多日再宣告仍落在搜尋窗內；遲到發布的邊界與預算見 D56 ④（D48 修）。三句不變量（D46）：Source 掃描器不修改 NAS；Source 正式 `<key>` 只由 library 發布；清道夫僅依 Abandoned 規則清理暫存與未發布 metadata。

### 2.3 資料流 B：發現與義務建立（Source 端，sync service）

```
每 10 s：readdir 當前 + 前 2 小時的 /HH/ 內容目錄                        (D6, D26, D30)
  → 與記憶體已知 key 集合比對，只有新 key 走 ingest                        (D36)
  → ingest（冪等）：由路徑推 identity → 讀 <bucket>/<key>.manifest → 路徑 == content_path 才算 Ready，否則計 anomaly → INSERT file_identity   (D48, D53)
       → 依 Policy (source, class) 展開 Required targets → INSERT obligation PENDING；identity 與全部義務同一交易，commit 後才更新記憶體集合   (D10, D10 修)
每 6 h：全量對帳掃整個保留期 30 天，逐小時內容目錄 readdir 對 DB 該小時集合，同一段 ingest，補漏並寫 inspection   (D10, D19 ①, D50)
  同時枚舉 manifest 桶（mtime 粗篩窗內）：manifest 有、內容無、DB 曾 Ready → 交 2.5 ①′ 判定明確不存在；
     DB 無紀錄 → 「未發布或發布後遺失」候選，計 unknown；DB 剛重建 → unknown。只有不在 DB 的 manifest 才讀內容；窗外 manifest 不枚舉，窗外未完成義務由 DB 列驅動檢查   (D53, D56)
```

義務在 Source Ready 那一刻已成立（manifest 存在），掃描只是把它登記到 DB；掃描漏掉或 DB 遺失都不改變義務存在的事實（RR-02、RC-01）。掃描只讀：manifest + `<key>` = Ready 才登記；manifest + `.writing` 無 `<key>` 是 Writing，由 Application 重呼 finalize 補 link，超 TTL 由清道夫當 Abandoned，Abandoned 只忽略 `.writing`，其 `<key>` 之後出現即 ingest（D46、D53 修）。DB 列不按時間刪未完成義務，purge 以 identity 整組 COMPLETED 為條件（D56 ②、D35 修）。

### 2.4 資料流 C：傳輸（Target pull）

```
Target 每 5 s，對每個 Source：
  GET /pending?target=<self>&limit=200
     Source 回：state = PENDING 且 next_attempt_at ≤ now 且 target 未 paused，按 source_ready_at FIFO，附 manifest 欄位、incarnation、epoch   (D12, D13, D42, D55)
     Source 記憶體記該 Target 的 last_pending_at 與本輪交付集合（顯示 UNREACHABLE / IN_PROGRESS 用）
  儲存閘門（D43）：任一儲存類失敗或容量低於門檻 → 立即關閉，所有 worker 取下一筆前檢查；
     每輪 statfs 成功 → 半開，放一個 worker 拉一筆真實工作 → 成功全開、失敗再關
  每 Source 一個佇列，4 個全域槽 round-robin（D41）；總速率 ≤ 50 MB/s（D52 預算）；NFS 有界執行器滿 = 閘門關閉（D51）
     in-flight set 已有 → 跳過
     查 received 列 (inc′, r, valid)：                                                       (D55, D55 修 2)
        inc′ = inc 且 r = e 且 valid   → 直接 report DONE(e)，不碰檔
        inc′ = inc 且 r = e 且 invalid → 跳過本次：不下載、不送 DONE、不建事件；既有事件 X 的重送照常，等 X 被接受與更高代次交付
        inc′ = inc 且 r < e           → 修復授權：下載驗證、暫存 fsync 成功後 rename(.writing, <key>) 原子覆蓋 → received 交易提交 → DONE(e)；rename 逾時不得以既有正式檔當作已覆蓋，等舊操作結束   (D12 修, D12 修 2)
        inc′ = inc 且 r > e           → 過期交付（佇列中的舊交付）：跳過、不改列，記 stale_delivery_count；連續多輪才告警疑似 Source 回退
        inc′ ≠ inc 且 valid 且 identity/size/digest 與交付一致 → 換錨：列改 (inc, e)，DONE(e)，不讀檔，不重設 Deep 驗證時間
        inc′ ≠ inc 其餘               → 修復授權（同 r < e）
        無列                          → link 路徑（下）
     GET /file/<identity>?target=<self>  Source 查義務存在否則 403；`/file` 工作預算滿回 503，Target 對該 Source 跳過本輪、不記 FAILED   (D40, D30 修 3, D30 修 4)
        streaming → <content dir>/<key>.<uuid>.writing + DigestInputStream   (D12, D51)
     size / digest 符合：
        暫存 fsync 成功（穩定寫入、COMMIT、verifier 由 OS NFS client 處理並經驗收）→ link(.writing, <key>) 成功 → received 交易提交 → POST /report DONE(e)；任一關卡失敗不越過；逾時保留所有權與 in-flight，舊操作結束後才依結果查證，儲存類錯誤走 D43 閘門   (D12 修, D12 修 2)
        EEXIST → 重算 <key> digest：= manifest → received upsert valid、DONE(e)（F10）；
                 ≠ manifest → 同一 Target 交易：無列則原子 INSERT invalid{observed, event_id X, (inc, e), report_pending}，已 invalid 則沿用 X（D54 修）
                              → 刪 .writing、釋放 in-flight，不就地替換 → 背景送 CORRUPT{event_id}   (D54, D55)
     不符 → 刪 .writing → POST /report FAILED{reason}（單檔問題）
  所有 report 帶 incarnation 與交付時的 epoch；LOST / CORRUPT 另帶 event_id
Source 收 report：
     DONE(e)  → UPDATE obligation SET COMPLETED, completed_at = now(Source) WHERE incarnation = inc AND epoch = e AND state = PENDING；否則 ACK STALE   (D26, D55, D55 修 2)
     FAILED   → attempts++, next_attempt_at = now + backoff(≤15 min)；digest 不符 / 4xx 反覆 → QUARANTINED   (D12a)
     LOST / CORRUPT 新 event_id → 同一交易：obligation_history、epoch + 1、state → PENDING（不論原狀態）→ ACK{ACCEPTED, epoch}
                    同 event_id 重送 → 不加代 → ACK{DUPLICATE, accepted_epoch, current_epoch}；義務不存在 → ACK{STALE, OBLIGATION_NOT_FOUND}
                    舊 incarnation 的 LOST / CORRUPT 在義務存在、去重、非 QUARANTINED 下照常加代；QUARANTINED 只記歷史不重開   (D55, D55 修, D55 修 2)
Target 收 ACK ACCEPTED / DUPLICATE → 清同一 event_id 的 report_pending；STALE{OBLIGATION_NOT_FOUND} → 保持 invalid 與 report_pending，有上限退避重送；不改 valid；ACK 任何欄位不得用於 DONE，代次只來自交付   (D54, D54 修, D55 修)
```

Target 停機後由 Target 自限速率追平；sync 主機單獨停機時 App 照寫，恢復後最多九個 Target 同時來追，Source 對外 `/file` 總量預算 100 MB/s 管吞吐，`/file` 工作預算 48 管卡住時的占用（D52、D30 修 3）。修復不就地替換：代次只從 `/pending` 交付取得，ACK 不授權換代，代價是多等一輪輪詢加一次 MB 級重下載（D55）。

### 2.5 資料流 D：對帳

```
Source 端 Shallow check（每 6 h，D19）：
  ① NAS 內容目錄（搜尋時窗 30 天，逐小時）vs DB file_identity 該小時集合 → 差集 ingest   (D50)
  ①′ DB 全部未完成義務（含窗外）直接 stat content_path：明確不存在 → 義務轉 QUARANTINED{SOURCE_LOST} 並寫一次 obligation_history{UNRECOVERABLE}，`/file` 回 410；timeout / 權限 / 讀取失敗 → unknown，不判遺失   (D56 ③, D33 修 3)
  ①″ DB 保留中的全部 file_identity × 現行 Policy，缺少的 obligation 列只補缺列，不改既有狀態   (D10 修)
  ② 對每個 Target GET /received?since=<cursor>（每列含 incarnation、epoch、valid）：
       Target 有 valid 列且 Source 未 COMPLETED → UPDATE ... WHERE incarnation = ? AND epoch = ? AND state = PENDING；延遲抵達的舊回應同樣受此檢查   (D55 修, D29 修 2)
       Target 有 invalid 列且其 event_id 不在歷史 → 視同新報告：歷史、epoch + 1、PENDING（QUARANTINED 只記歷史）   (D55 修 3)
       digest 紀錄不符                    → QUARANTINED
       Target 不可達 / 503（rebuild 中）  → 該範圍標 unknown，保留上次結果   (D29 修)
  ③ INSERT inspection{scope, cutoff, checked, unknown, diffs, repairs}
Target 端 Shallow 自查（每 6 h，D19 修）：
  保留期內（received.source_ready_at）全部 valid received 列（invalid 列略過，列為已知異常，不計本輪覆蓋率），逐內容小時目錄枚舉名字並讀 size：缺或 size 不符 → `WHERE valid = true` 條件更新原子標 invalid + event_id X + report_pending，更新 0 列則沿用既有 X → 背景送 LOST；屬性讀不到即 unknown 不算通過，READDIRPLUS 只是優化   (D19 修 2, D19 修 3, D54, D54 修)
Target 端 Deep 自查（滾動，週期量測後定為 T30 偵測時限，D16 修）：
  讀本地 <key> 重算 digest 與 received.digest 比對 → 不符 → 同上標 invalid → 背景送 CORRUPT；讀取預算 20 MB/s
  deep_check_cycle_days 為觀測值；T30 偵測期限為 operational policy 常數，壓測後人工設定，告警 = 觀測 > 常數，不自動放寬（D52 修 2）
  兩種自查都跳過 in-flight 集合裡的 identity   (D55)
  恢復檢查：每週期對 recovery_pending 列重跑 D29 修 6 的 ①②，直到清旗標；重啟不丟；取 in-flight 排他，回寫一律 WHERE event_id = X AND recovery_pending AND NOT valid；①② 的證據須同一 Source incarnation   (D29 修 6, D29 修 7)
  Target 端對帳 ②（常駐，每週期）：對每個 Source 沿 keyset 游標 completed_seq 續讀 /pending?state=COMPLETED 到空頁，已有列在 DB 先過濾，只有缺列才 stat，只補缺列且限保留規則內；本頁 upsert 與游標同交易；ENOENT → invalid + LOST，其他 stat 失敗本頁不提交重試；游標綁 Source incarnation，換代從頭讀，再次還原全部重設；caught_up_at = 讀到空頁時刻   (D29 修 7, D29 修 8, D29 修 9)
  只回報異常；覆蓋率由 Target 曝 shallow_check_coverage / deep_check_coverage
Source 收新 event 的 LOST / CORRUPT → 寫歷史、epoch + 1、重開 PENDING（QUARANTINED 只記歷史），下輪 /pending 以新代次重給；Target 見 r < e 即執行替換   (D55, D55 修 2)
```

增量拉取抓不到缺席，所以 Target 端的遺失與損壞一律由 Target 自查回報；Source 端的 `/received` 只負責補完成與比對紀錄。

### 2.6 義務狀態機（Source 端持有）

持久化只有三種狀態（D42）：

```
   ingest              report DONE
  (manifest) ──▶ PENDING ──────────▶ COMPLETED
                  ▲   │ report FAILED 反覆（digest 不符 / 4xx）/ 內容明確不存在{SOURCE_LOST}     │ LOST / CORRUPT / 紀錄不符
                  │   ▼                                             ▼
   ops release ── QUARANTINED                              重開 PENDING（保留歷史）
```

LOST / CORRUPT 只重開 COMPLETED 與 PENDING；QUARANTINED 只記歷史，等 ops release，release 不解除 pause（D55 修 2）。未完成義務不按時間刪，永久追蹤；SOURCE_LOST 沒有任何 ops 動作能結清，只留證據（D56 ⑥）。

spec §14 其餘狀態為 `/status` 的顯示推導：

| 顯示狀態 | 推導自 |
| --- | --- |
| RETRY_WAIT | PENDING 且 next_attempt_at > now |
| IN_PROGRESS / VERIFYING | Source 記憶體：上一輪交給該 Target 且未回報（重啟即失，只影響顯示） |
| BLOCKED{paused} | `target_control.paused`（持久化，ops 寫） |
| BLOCKED{unreachable} | 記憶體 last_pending_at 過期 60 s |
| BLOCKED{capacity / storage} | Target 自己的 `capacity_alert_status`、`storage_health` 指標 |

所有非 COMPLETED 義務皆計入 backlog 與 age（§14）；pause、unreachable、capacity 都不碰 obligation 列。

### 2.7 Consumer 讀取（Target 端，library）

`exists(identity, dataClass)`：library 呼叫本 Node sync service `GET /locate/<identity>?dataClass=`。sync service 查 `file_identity`（Source 角色）或 `received`（Target 角色）：有 valid 列 → READY + path；invalid 列 → DATA_NOT_READY（D54）；無列且 source == 本 Node → DATA_NOT_READY（掃描 15 s 內出現，D46）；無列且 Policy (source, class) 含本 Node → DATA_NOT_READY，否則 NOT_EXPECTED。library 不快取 READY，每次 `read` 重新 `/locate`；sync service 不可達 → UNAVAILABLE；已開啟的串流不撤銷（D23 修 2）。判定順序：source == 本 Node 依 file_identity，不看 Required targets；source ≠ 本 Node 才 Policy 先，Policy 不要求此 Target 一律 NOT_EXPECTED，Policy 要求且無列且該 Source 自還原後尚未 caught up → UNAVAILABLE（D29 修 9）。`read` 僅 READY 時開 path 給 InputStream。不問 Source（D15 修、D23 修）。

為什麼不直接 stat：路徑含 Finalize 日期而 identity 沒有；NOT_EXPECTED 需要 Data class 而 identity 沒有。read 不在 Local Transaction SLO 內，本 Node sync service 不是 Remote Node 也不是 CP。

## 3. 持久化

**檔案**：見 docs/design/file-inventory.md。

**DB schema**（D24，Oracle，sync 自有 schema，SQL 可攜、H2 Oracle mode 測試）：

| 表 | 欄位 | 索引 |
| --- | --- | --- |
| `file_identity` | source_node, namespace, logical_key, data_class, size, digest, source_ready_at, content_path | PK (source_node, namespace, logical_key) |
| `obligation` | identity_ref, target_node, state ∈ {PENDING, QUARANTINED, COMPLETED}, epoch, attempts, next_attempt_at, last_error, completed_at, completed_seq | (target_node, state, next_attempt_at)；(state, source_ready_at) 供 age；(target_node, completed_seq) 供 Target ② 列舉，每次進入 COMPLETED 換新號（D29 修 10, D29 修 11） |
| `received` | identity_ref, size, digest, source_ready_at, published_at, valid, invalid_reason, observed_digest, event_id, incarnation, epoch, report_pending, recovery_pending, change_seq | PK identity_ref；(change_seq) 供 `/received?since`，任何對外可見變更皆換新號（D29 修 10, D29 修 11）；(report_pending) 供背景重送 |
| `rebuild_progress` | source_node, incarnation, cursor, caught_up_at | PK source_node；Target 端對帳 ② 每 Source 游標，綁 incarnation，與本頁結果同交易推進，還原後未 caught up 不宣稱覆蓋完整（D29 修 7, D29 修 9） |
| `node_meta` | incarnation（rebuild 時換新的 UUID）、rebuild_in_progress（與新 incarnation 同交易寫入，重啟見旗標維持封鎖並重做，D29 修 2 / 修 3） | 單列 |
| `seq_counter` | name ∈ {completed, change}, last | PK name；兩列各自行鎖；交易尾端 FOR UPDATE 一次保留 K 個連續號逐列分配，鎖持有到 commit（D29 修 10, D29 修 11） |
| `inspection` | scope, cutoff, started_at, finished_at, checked, unknown, diffs, repairs | (finished_at) |
| `ops_audit` | who, at, action, scope, reason, result | (at) |
| `target_control` | target_node, paused, paused_by, paused_at, reason | PK target_node |
| `obligation_history` | identity_ref, target_node, kind ∈ {LOST, CORRUPT, REOPEN, RELEASE, UNRECOVERABLE, INTEGRITY_FAILURE, IDENTITY_CONFLICT}（事故類以 (identity_ref, target_node, kind) 只寫一次，D33 修 2）, event_id, epoch_after, expected_digest, observed_digest, at, acknowledged | (identity_ref, target_node)；UNIQUE(event_id)；(acknowledged, kind) 供事故告警 gauge（D33 修 3），`/report` 與對帳 ② 同時送入同一事件只處理一次（D55 修 3） |

與 D24 的差異（D42、D47、D54、D55、D56）：obligation 只存三種 state 加 `epoch`，無 `last_handed_at` 與 `paused`；`target_liveness` 改記憶體；新增 `target_control`（pause flag）、`obligation_history`（IR-03 證據，以 event_id 去重）、`node_meta`（incarnation、rebuild_in_progress，D29 修 2）；received 加 invalid 狀態、待回報欄位與 source_ready_at（purge 與自查範圍的尺，D56）；purge 以 identity 整組 COMPLETED 為條件，未完成不按時間刪（D35 修）。單一 process 唯一寫入者；行鎖只用於 seq_counter 取號與條件更新，不做跨列鎖。

**HTTP 端點**（Node 間，每 Node 憑證 + HTTPS，呼叫者身分 = Node 名；`/pending` `/file` `/report` 的 target 取自身分、另帶不同即 403，`/received` 只回呼叫者為 Source 的列；驗證端只存 token 雜湊，D14 修 2）：`GET /pending?target=&state=PENDING|COMPLETED&cursor=&identity=`（COMPLETED 供 Target rebuild 取基準，D49 ②；identity 過濾供恢復檢查逐筆確認，D29 修 6）、`GET /file/<identity>?target=`（查義務否則 403，D40；內容明確不存在回 410，D56 ③）、`POST /report {DONE|FAILED|LOST|CORRUPT}` 帶 incarnation、epoch，LOST / CORRUPT 另帶 event_id，ACK {ACCEPTED, epoch | DUPLICATE, accepted_epoch, current_epoch | STALE, OBLIGATION_NOT_FOUND}，三種皆帶 Source incarnation，欄位僅供告知；恢復檢查用 incarnation 判定兩份證據同代（D43, D55 修, D54 修, D29 修 7）、`GET /received?since=`（每列含 incarnation、epoch、valid；rebuild 中連同 `/pending`、`/report` 一起 503，D29 修 2）。Node 間兩個對帳列舉皆 keyset 分頁，鍵為提交序號（completed_seq / change_seq），每列唯一，序號 N 可見即所有 < N 已提交，游標 = 最後序號的列，回應含 incarnation，不用 OFFSET（D29 修 8, D29 修 9）；Node 內：`GET /policy`、`GET /locate/<identity>?dataClass=`（rebuild 中 503）、ops API、Actuator。

## 4. Control Plane 與設定

### 4.1 沒有 Control Plane process

spec §11 對 CP 的要求（版本化、發布者與時間稽核、各 Node 採用狀態、不得為 Data Plane 即時依賴）由 git commit + Node metric + Grafana / CLI 扇出全部滿足；v1 Policy 不可變、operational policy 變動極少，常駐服務換不到功能（ADR-0003, D17）。「CP 壞掉」= git 或 CD 不可用 = 不能發新版，資料流、重啟、本地查詢全部不受影響。

### 4.2 設定四層與設定流

設定流（D17、D18、D45）：git `configs/v<N>.json` 不可變 → PR merge 改 `latest` = 發布 → CD 寫 sync 主機 `candidate.json.tmp` → rename → `systemctl restart` → sync service 啟動時驗證 candidate（schema、版本遞增、Policy 段與 active 完全相同、v1 固定欄位值相符，D30 修 6）→ active→lkg、candidate→active → 載入並開始服務後才曝新 `active_config_version`。設定物件 process 內不可變，不熱載入；驗證失敗留 candidate、計 `activation_failure_count`、用 active；active 載入失敗用 lkg。每次變更代價 30 s lag。library 經 `GET /policy` 取 Policy 段，記憶體快取 + 本機 fallback 檔。四層：git → sync 主機本機檔 → sync service 記憶體 → library 快取。

### 4.3 Data Plane 獨立性

每個 Node 的持久化 local state（active.json、lkg.json、Oracle sync schema、NAS manifest）足以在 CP 不可用時：繼續 replication、retry、reconciliation 與本地交易（AC-CP-01）；Data Plane 重啟後從本地設定與狀態恢復（AC-CP-02）；以 CLI 查本地狀態並執行受控操作（AC-CP-03）。唯一例外是新 Node 首次初始化需人工放第一份 active.json（ADR-0003）。

### 4.4 設定變更的邊界

可更新項目限 operational policy（第 8 章可調項清單）。Policy 段變更一律拒絕並上報（AC-CFG-01、T22）；設定更新不得靜默消除 backlog，pause / release 皆為 ops 動作寫 audit，不是設定。影響發現保證的參數為 v1 固定常數，不接受 config 覆寫，v1 不支援變更；新設定合法不代表能承接舊宣告、在途操作與版本混跑，遷移程序含 App library，未定義前不變更（D30 修 5、D30 修 6）。

## 5. Application 整合契約

Application 必須（D8、D23、D23 修）：

1. 透過 library 寫入，宣告 Namespace、Data class、Logical key；Logical key 對不同內容唯一（含 run id / timestamp）。
2. `finalize()` 回 SUCCESS 才 commit 業務交易並記錄 identity；PENDING_CONFIRMATION 不 commit，重呼 `finalize()` 至確定；FAILURE 視為交易失敗。
3. 交易重跑直接 beginWrite + finalize，Finalize 冪等保證不重複；不需先 exists（D46）。Consumer 讀取以 `exists(identity, dataClass)` 查詢。
4. 開 Actuator `/actuator/prometheus`，讓 library 的 Local Transaction 指標可被抓取。
5. 部署前 App 主機與 sync 主機 NTP 同步；NFS 以 `hard` mount。
6. library 的 NFS 執行器滿時 beginWrite 會立即回 FAILURE(UNAVAILABLE)，Application 視為本次交易失敗（D51）。

Framework 承諾：SUCCESS 後義務成立且 crash 可重發現；不參與 Application 的 DB 交易；Finalize 後 Application rollback 產生的孤兒 Ready 檔為可接受浪費。

library 設定只有四項：mount root、sync service URL、NFS 操作 timeout、Policy fallback 檔路徑。

## 6. 故障窗口表

每列：故障 → 設計如何封閉 → 決策 → 驗收測試。這是 §21.2 要求的 Requirement→Design→Test 核心；ops 動作見第 8 章。

| # | 故障窗口 | 封閉方式 | 決策 | 測試 |
| --- | --- | --- | --- | --- |
| F1 | Finalize 第①②步之間 crash | 只有 `.writing`，無 manifest → Writing / Abandoned 候選，超 TTL 清道夫刪；Application 未拿到 SUCCESS 不 commit | D3, D11, D35 | T29 |
| F1b | library 暫存檔被清道夫依 TTL 刪掉時操作仍在進行 | link 尚未送出且來源不在 → FAILURE；link 已送出結果未明 → PENDING_CONFIRMATION，重呼 finalize 走 rediscovery；不因暫存檔消失推論未發布 | D51 修 2 | T25, T29 |
| F2 | Finalize 第②③步之間 crash | manifest 有、`<key>` 無、`.writing` 有 = Writing；Application 重呼或重跑 finalize 由 library 四項比對 → rediscovery → 依 manifest.content_path 跨目錄補 link；掃描器不代勞，超 TTL 清道夫當 Abandoned；宣告超過 N = 7 天才需再次嘗試發布 → DECLARATION_EXPIRED 換 key | D3, D3a, D46, D48 修, D53 修 | T29 |
| F2b | 寫入橫跨多日後才宣告，或宣告後多日才 link | content_path 取宣告時刻的小時目錄，`.writing` 留原處跨目錄 link；再次嘗試發布受 N = 7 天限制；固定預算 10.25 天，清道夫停跑（含 NAS 中斷、rebuild 隔離、服務停機）< 19 天，超過失去發現保證並告警 | D48 修, D53 修, D56 ④ | T11, T29 |
| F3 | Finalize 第③步後回覆遺失 | 重呼 finalize：manifest EEXIST 同 digest、link EEXIST 且重讀 `<key>` digest 相符 → SUCCESS，不重複資料 | D3a, D44 | T29 |
| F4 | manifest 寫到一半（NFS 中斷或兩個寫者並發） | manifest 以 tmp + link 原子建立，NAS 上只有「完整存在」或「不存在」；tmp 殘留由清道夫刪 | D44 | T13, T20, T29 |
| F5 | 同 identity 不同內容（含跨小時重跑） | manifest 位置由 identity 決定，EEXIST 且四項任一不同 → CONFLICT，不覆寫；`<key>` link 原子失敗 | D3, D48, D53 | T20 |
| F5b | 卡住的舊 link 在 manifest 被換掉後才成功 | manifest 永不刪、不提供 release-claim，宣告不會被換掉；`<key>` 路徑 ≠ manifest.content_path 者不算 Ready | D53 | T20, T25 |
| F6 | Source Ready 後、task 建立前 sync service crash | 義務 = manifest × Policy，掃描重發現；不依賴 task 表 | D1, D10, D19 | T18, T11 |
| F7 | 掃描漏掉（lost trigger） | 增量掃與全量掃共用冪等 ingest；全量掃每 6 h 覆蓋整個保留期必補 | D10, D19 ①, D50 | T11 |
| F8 | sync service process crash / host restart / upgrade | process 無狀態；DB 義務原樣；systemd 5 s 重啟；30 s 內服務 | D5, D34 | T06, T07, T32 |
| F8b | sync 主機硬體死亡 | `up{node}` 消失即 page；值班依 fencing 清單確認原機已死後啟動專屬冷備；目標 15 min 含偵測與反應；期間 App 照寫、lag 漲、義務不丟 | D37, D37 修 | T07 |
| F9 | Target 下載中斷 | `.writing` 非正式名，不可見；下輪重拉 truncate 覆蓋；殘留由清道夫刪 | D12, D28, D35 | T08 |
| F10 | Target publish 後、received 寫入前 crash | 下輪重拿 → 無列走 link 路徑 → EEXIST → 重算 digest 對基準 → 相符補 row、DONE，不重下載；不符走 F14 | D12, D55 | T19 |
| F11 | `/report DONE` 遺失 | 同 F10；Source Shallow ② 由 `/received` 補 COMPLETED | D12, D19 | T19 |
| F12 | Duplicate trigger / 同義務重複交付 | Target 先查 received 與 in-flight set；link 原子；Source 收重複 DONE 冪等 | D12, D3 | T09 |
| F13 | Integrity mismatch（傳輸中） | digest 不符不發布、report FAILED；反覆 → QUARANTINED 留 expected/observed | D12a | T10, T17 |
| F14 | 已完成 Target 檔其後遺失或損壞 | Target 發現 → 同一交易 received 標 invalid + event_id → 背景 LOST / CORRUPT；Source 寫歷史、epoch + 1、重開；下輪交付 r < e 即修復授權，rename 覆蓋；延遲的舊 DONE 因加代與條件更新失效 | D16 修, D19 修 2, D54, D55 | T30 |
| F14b | 異常已寫 Target DB、report 送出前 crash | received 列的 report_pending 持久化，重啟後背景重送同一 event_id；`/locate` 期間不回 READY | D54 | T30 |
| F14c | ACK 遺失後重送 / 舊 ACK 抵達 | Source 以 event_id 去重回 DUPLICATE 與當初的 accepted_epoch；ACK 只清同一 event_id 的 pending，不改 valid，不授權換代 | D54, D55 修 | T19 |
| F14d | Source DB 重建後舊代次回報 | 舊 DONE 因 incarnation 不符 STALE；舊 LOST / CORRUPT 在義務存在、去重、非 QUARANTINED 下加代重交付；Target 見 incarnation 不同：valid 且基準一致換錨 DONE 不讀檔，否則修復 | D55, D55 修 2, D29 修 2 | T18, T19 |
| F14e | DONE(e) 遺失或延遲，自查同時發現異常 | 交付 e 遇 invalid 跳過，不同代補完成、不建第二事件；X 接受後 e+1 修復；延遲的 DONE(e) 在加代後抵達 → STALE | D55 修 2 | T19, T30 |
| F14f | 過期交付：佇列中的舊交付在修復後才輪到 | 同 incarnation r > e → 跳過、不改列，不算衝突；連續多輪才告警疑似 Source 回退 | D55 修 2 | T19 |
| F14g | LOST / CORRUPT 得 STALE{OBLIGATION_NOT_FOUND} | 保持 invalid 與 report_pending，退避重送；Source 之後補建義務即接受、加代、修復，不與「跳過等 X」互鎖 | D54 修 | T19, T30 |
| F15 | Target Node / Storage 停機（≤24 h） | Target 不來拉，Source 以 staleness 推 UNREACHABLE；義務留 Source DB；其他 Target 不受影響（各自拉） | D12a, ADR-0001 | T02, T03, T04, T12, AC-NODE-01～05 |
| F16 | Target 恢復後 catch-up | Target 自限並發 4、50 MB/s；FIFO；24 h 內追平 1 TB 有 2× 餘裕，只在 Target 合計 1 TB/日前提下；平均 1 MB 時無餘裕 | D13, D30, D31, 事實 | T05, T16, T24, §20 |
| F17 | 遠端 NAS hang 污染本機 | 不做 NFS-to-NFS；Source 只讀自己 NAS，Target 只寫自己 NAS；每個 NFS 操作獨立 thread + timeout | ADR-0002, D3b | T12, T25 |
| F18 | 本機 NAS 長時間無回應 | 有界執行器無佇列：卡住的 syscall 占槽不釋放，HTTP 或呼叫者 timeout 都不是釋放依據，滿了立即拒絕；library 回 PENDING_CONFIRMATION / UNAVAILABLE；hard mount 不假失敗；systemd 不因 health 重啟；恢復後舊操作靠 uuid 與 link 語意無害完成 | D3b, D34 修, D51, D51 修, D30 修 4 | T13, T25 |
| F19 | NFS failover / stale handle | 重試同一 operation identity（同 uuid、同 manifest）；結果由 rediscovery 查證 | D3a, SR-04 | T13 |
| F20 | Target 容量不足或 NAS 不健康 | 共用閘門立即關閉，最多 4 筆執行中失敗；每輪 statfs 成功後半開一筆探路；Source 只見 UNREACHABLE，原因看 Target 指標；library 端拒寫 | D43, D21 | T12, T21 |
| F21 | 已知曾 Ready 的 Source 正式內容遺失且義務未完成 | Shallow ①′ 對 content_path 明確得到不存在 → QUARANTINED{SOURCE_LOST} + 一次 UNRECOVERABLE 歷史事件 → 事故告警看事件增量；manifest 消失不等於內容遺失；讀取失敗只計 unknown；永久追蹤，無 ops 動作能結清 | D56, D33 修 2, RT-01 | T21, T30 |
| F22 | CP（git / CD）不可用 | 不能發新版；Node 只讀本機 active.json；資料流無關 | D17 | T14, T23, AC-CP-01～03 |
| F23 | 壞設定 / 啟用中 crash | 啟動時驗證，失敗留 candidate 用 active；rename 原子；active 缺或載入失敗用 lkg；新版本只在成功服務後回報 | D17, D45 | T15, T22, T26, AC-CFG-01～05 |
| F24 | sync DB 部分遺失或 ops `rebuild`（表仍在） | 隔離（Node 間端點全 503、停排程、等回寫工作結束、`rebuild_in_progress` 持久化）→ 換 incarnation → 只補不刪：全掃 30 天搜尋窗補建缺的 file_identity / obligation，既有 COMPLETED / QUARANTINED / PENDING 一律保留，PENDING 的 epoch 重設為 1 → 對帳 ② 跑一次：Target 列 incarnation 不同且 valid 且四項相符 → `WHERE incarnation = 現行 AND epoch = 1 AND state = PENDING` 補 COMPLETED，`/received` 游標重設為 0 → received 依 D49 ② 只補缺列 → inspection。不可達的 Target 留 PENDING，下輪 ② 或交付換錨補齊；crash 後重啟見旗標重做；期間該 Node 同步暫停，App 照寫，完成時間待量測 | D29, D29 修, D29 修 2, D29 修 3, D49, D50, D55 修 2 | T18, T19 |
| F24b | sync DB 整庫遺失，或備份還原時 identity 已在窗外 | 恢復限制：窗外未完成義務無法重建，連 unknown 都不顯示；DB 備份為 ops 責任，還原後仍 rebuild 換 incarnation | D56 ⑤ | T18 |
| F25 | Target DB（received）遺失或從備份還原 | 永不從檔案算基準：向 Source 拉 COMPLETED 義務的 manifest 欄位重建，只補缺列，既有 valid 列與驗證時間保留，digest 由 Deep check 後驗。既有 invalid 列標 recovery_pending 持久化，每週期重試直到清除：一律以原 event_id 重送 X 確認已保存（不信備份旗標）；DUPLICATE 後向 Source 取該 identity 的 COMPLETED 證據，基準相符且本地重算通過才恢復 valid；重驗不符或缺 → 換新事件；ACCEPTED 或 Source PENDING / QUARANTINED / STALE / 不可達 → 維持 invalid，等修復交付或下輪重試。缺列由常駐的 Target 端對帳 ② 沿游標續補，還原後未 caught up 前 `/locate` 回 UNAVAILABLE；DUPLICATE 與 COMPLETED 證據須同一 Source incarnation；回寫皆以 event_id = X 條件更新 | D49, D29 修 2, D29 修 6, D29 修 7, D29 修 9 | T19, T30 |
| F25b | Source 從備份還原成 COMPLETED，Target 的 invalid 已 ACK | Target 不重送、Source 不交付；對帳 ② 見 invalid 列 event_id 不在歷史 → 視同新報告重開 → 交付 → Target 見 incarnation 不同走修復；Target 503 下輪補 | D55 修 3 | T18, T19 |
| F26 | Reconciliation 期間 Target 不可讀 | 該範圍 unknown，保留上次結果與時間，不假報一致 | D19 | T27 |
| F27 | Consumer 提前讀 | `/locate` 回 DATA_NOT_READY / NOT_EXPECTED；sync service 不可達回 UNAVAILABLE 不假報不存在；直接讀路徑只見 `<key>` 存在與否，partial 永不以 `<key>` 存在 | D15 修, D23 修, D3 | T28 |
| F28 | Pause toward one Target | `target_control.paused` 一個 flag，`/pending` 過濾該 Target，新舊義務一律涵蓋，仍計 backlog / age；resume 翻回 | D22, D42 | T31 |
| F30 | 一個 Source 大量 catch-up 餓死其他 Source | Target 每 Source 一佇列，4 槽 round-robin | D41 | T16 |
| F31 | 非 Required target 的 Node 來拉檔，或冒用他 Node 的 target 參數 | `/pending` `/file` `/report` 的 target 取自憑證身分，另帶不同 → 403；`/file` 查義務，無則 403；`/received` 只回呼叫者為 Source 的列 | D40, D14 修, D14 修 2 | — |
| F29 | Prometheus / Alertmanager 不可用 | watchdog 停止 → NMS 告警；CLI 扇出仍可回答 §15 | D27 修, D20 修 | T14, AC-CP-03 |

## 7. Recovery 與容量算術

| 項目 | 數值 | 來源 |
| --- | --- | --- |
| Envelope 最壞 backlog | Target 停 24 h：9 Source × 2.4×10⁵ = 2.16×10⁶ 檔；位元組取 spec 的 Target 合計 1 TB/日，隱含平均約 463 KB；實際範圍 KB～5 MB，平均 1 MB 即 2.16 TB/日；分佈 TBD | §18, 事實 |
| 追平所需淨排空 | 1 TB 欠量 + 1 TB/日新流量，24 h 內 → ≈ 23 MB/s；平均 1 MB 時 2.16 + 2.16 TB → ≈ 50 MB/s | D31, 事實 |
| 頻寬預算（共用 NAS） | Deep 20 MB/s、Target 拉取 50 MB/s、Source 供檔 100 MB/s；本地交易優先由合併負載壓力測試證明 | D52 |
| Target 拉取上限 | 50 MB/s（10 GbE 的 4%） | D30 |
| 餘裕 | Target 合計 1 TB/日前提下 2×、6 h 停機 16×；平均 1 MB 時 1×，無餘裕；以大小分佈量測為準 | D31, 事實 |
| 單一 pair 追平檔案速率 | 2 × 2.4×10⁵ / 86400 ≈ 5.6 筆/s（舊比較情境 2.3），`/pending` 上限 40 筆/s | D31, 事實 |
| Source 總 DONE 速率 | 穩態 9 × 2.4×10⁵ / 日 ≈ 25 筆/s；同 Node Target 發布同量級；兩把序號行鎖分開；10⁵/日只作比較情境 | 事實, D29 修 11 |
| 重啟恢復 | systemd 5 s + 啟動 ≤30 s；不做全量掃 | D34 |
| 主機死亡恢復 | ≤ 15 min，含偵測、值班反應、冷備啟動 | D37 修 |
| 設定變更 | 重啟 30 s lag，不熱載入 | D45 |
| DB 重建 | 30 天 × 2.4×10⁵ × 9 ≈ 6.5×10⁷ 義務；完成時間由全量重建測試量測，期間該 Node 同步暫停；災難路徑 | D29, D29 修 2, D50 |
| 常駐對帳讀取量 | Target ② 每日每 Target 約 2.2×10⁶ 筆完成紀錄，DB 先過濾已有列，缺列才 stat；Source ② 對稱；序號行鎖在追平峰值下的等待列入壓測 | D29 修 10, D29 修 11 |
| DB 穩態列數 | 已完成部分 obligation ≈ 1.3×10⁸ / Source（identity 整組 COMPLETED 後 60 天 purge）；未完成義務不按時間刪，QUARANTINED 累積量 = 隔離率 × 天，無上限，處置在 ops，release / evict / 換 key 不結清 SOURCE_LOST | D32, D35 修, D56 |
| 遲到發布預算 | 固定項 10.25 天（N 7 + TTL 1 + 清道夫間隔 1 + 時鐘裕度 1 + 對帳 0.25）；清道夫停跑（含 NAS 中斷）< 19 天，operational policy | D56 ④ |
| 容量門檻 | reject 10 TB、alert 20 TB；NAS 遠大於此，本版不做容量規劃 | D30 修 |

結構性事實：Source Node 整體停機時 App 同停、不產新檔；sync 主機單獨停機時 App 照寫，Source 恢復後九個 Target 同時追，由 Source 供檔預算 100 MB/s 承接（D52）；Target 停機的 catch-up 由 Target 自限速率，九個 Source 同時供給也不會塞爆（D31）。

## 8. Ops

**Ops API**（每 Node，token 授權，操作寫 `ops_audit`，D20、D22、D25、D29）：

| 動作 | 效果 |
| --- | --- |
| `GET /status` | §15 十題的本地答案 |
| `retry <identity> <target>` | RETRY_WAIT → next_attempt_at = now |
| `release <identity> <target>` | QUARANTINED → PENDING，attempts 清零，重走完整驗證；不解除 pause，resume 另做（D55 修 2） |
| `evict --target <node> <identity>` | 刪 Target 正式路徑不符檔（release 前用） |
| `ack <event_id>` | 事故事件 acknowledged = true，寫 audit；事故告警只由此清除（D33 修 3） |
| `pause <target>` / `resume <target>` | `target_control.paused` 翻 flag，`/pending` 過濾；顯示為 BLOCKED{paused} |
| `reconcile` | 立即觸發 Shallow check |
| `rebuild` | D29 修 2 / 修 3：隔離（Node 間端點全 503、停排程、等回寫工作結束、旗標持久化）→ 換 incarnation → 只補不刪：全掃補建缺列、PENDING epoch 重設 1、對帳 ② 條件更新匯入 Target `/received` 歷史證據（游標重設 0）、依 D49 ② 補缺的 received；一切既有列與歷史、audit、控制旗標保留；crash 後重啟自動重做 |
| `config` | 檢視 active / lkg / candidate（第 4 章） |

不提供 force-complete。CLI 包以上，並可扇出所有 Node。

**Operational policy 可調項**（D30）：掃描間隔、輪詢間隔、`/pending` limit、傳輸 timeout、Target 並發與速率、`/file` 並發連線、UNREACHABLE 週期、退避上限、容量門檻、告警門檻、每 Node 憑證輪替。

**v1 固定常數**（D30 修 5）：宣告年齡上限 N 7 天、Abandoned TTL 24 h、清道夫排程每日、全量對帳週期 6 h、搜尋時窗 30 天、遲到發布停跑容忍 19 天。不接受 config 覆寫，設定若保留欄位必須等於固定值否則啟用拒絕；時鐘裕度 1 天亦固定。遷移範圍含 App library；具體程序未定義前 v1 不支援變更（D30 修 6）。

## 9. Observability

見 docs/design/monitoring.md：五軸（Completeness、Freshness、Integrity、Availability、Trust of view）× 三層（告警、診斷、趨勢），每軸第一層一個告警指標，門檻見 D33 與 D33 修。粒度 `{source,target}` 或 `{node}`，Grafana 做 overall → by phase 下鑽（D27）。

## 10. SLO 建議值（填 §18 TBD）

| 項目 | 建議 | 依據 |
| --- | --- | --- |
| Normal replication lag | P95 ≤ 30 s、P99 ≤ 60 s | 掃描 10 s + 目錄 cache + 輪詢 5 s + MB 級傳輸（D6、D30） |
| Recovery | 24 h 停機後 24 h 內追平（待驗證目標，D52） | D31 |
| Shallow check 週期 | 6 h | D30 |
| Deep check 完整覆蓋 = T30 偵測時限 | 量測後定 | Target 30 天 × 1 TB/日 = 30 TB，20 MB/s 純讀取約 17.4 天；平均 1 MB 時 65 TB 約 37.5 天，超過 30 天保留期，檔案會在被 Deep 驗過一次前到期；壓測後在提高預算與接受較長期限之間選，屆時才設告警（D52 修） |
| sync 主機恢復 | ≤ 15 min 含值班反應 | D37 修 |
| Unknown outcome 查證期限 | 服務恢復後 1 個 Shallow 週期（6 h） | D19 |
| 遲到發布發現保證 | 清道夫停跑 + NAS 中斷 < 19 天內，遲到的 `<key>` 於下一個 6 h 全掃登記 | D56 ④ |
| Local availability / latency | **仍 TBD**：需在目標 NAS 上量 Finalize 的基準（每筆 1 create + 1 fsync + 1 link + 1 unlink） | D3 |
| 容量門檻 | reject 10 TB、alert 20 TB | D30 修 |
| Measurement | Source 時鐘；統計窗 5 min，分位數按 pair | D26 |

## 11. Requirement → Design → Test 對照

| 需求 | 設計 | 測試 |
| --- | --- | --- |
| DG-01 Node independence | 每 Node 獨立 NAS / DB / process；pull 模型；不 mount 遠端 NAS（ADR-0002, D5, D7） | T02, T03, T12, AC-NODE-01～02 |
| DG-02 Local-first | Finalize 只碰本地 NAS；Policy 快取 + fallback；sync service 停機不影響 Local Transaction（D3, D18, D34） | T02, T14, T23 |
| DG-03 Eventual consistency | 義務持久於 Source；Target 自拉 catch-up；QUARANTINED 可 release（D12a, D25, D31） | T05, T16, T24, §20 |
| DG-04 No silent loss | manifest 為真相；Shallow + Deep；unknown 不假報；unrecoverable 告警（D1, D16, D19, D33） | T10, T11, T27, T30 |
| DG-05 Resilience | 無狀態 process、冪等 ingest / pull / report、LKG（D3a, D10, D12, D17, D34） | T06, T07, T09, T26, T32 |
| SR-01 | library 契約 D23 | T28, T29 |
| SR-02 / SR-03 | hard mount、`lookupcache=positive`、每操作 thread + timeout；NFSv3 驗證項列於 D3b | T13, T25 |
| SR-04 | 三態回傳；PENDING_CONFIRMATION 重呼同序列（D3a） | T13, T29 |
| SR-05 | 有界執行器、槽位隨 syscall 釋放、滿即拒絕；不 mount 遠端 NAS（D51, ADR-0002） | T12, T25 |
| SR-06 | `/locate` 查本 Node 表 + Policy；不問遠端；不可達回 UNAVAILABLE（D15 修, D23 修） | T28 |
| FR-01 | 正式位置由 identity 決定、`<key>` link 原子、不覆寫；Target 副本保留 identity（D3, D48） | T20 |
| FR-02 | manifest + link = Source Ready；rediscovery 四種組合（D3） | T18, T29 |
| FR-03 | `.writing` 非正式名；link 發布（D12, D28） | T08, T28 |
| FR-04 | manifest tmp + link 原子宣告 + digest 比對 → CONFLICT（D3, D44） | T20 |
| RR-01 | 純掃描發現，Finalize 不等 sync（D6） | T01 |
| RR-02 | 義務在 Source DB；manifest × Policy 可重建，掃描覆蓋整個保留期（D1, D19, D29, D50） | T06, T07, T18 |
| RR-03 | received 表 + in-flight set + link 原子 + 代次協定擋延遲回報（D12, D55） | T09, T19 |
| RR-04 | 單檔退避 ≤15 min；Target 儲存閘門擋整體故障；per-target 隔離；QUARANTINED 留原因（D12a, D43） | T12, T17 |
| RR-05 | Target 自限並發與速率；Source 供檔預算；FIFO（D13, D30, D31, D52） | T16, T24 |
| RC-01 | 應有集合 = manifest × Policy（D10, D19 ①） | T11, T18 |
| RC-02 | Source Shallow 含 manifest 桶枚舉 + Target Shallow 全保留期 / Deep 自查（D16 修, D19 修 2, D53） | T11, T27, T30 |
| RC-03 | LOST / CORRUPT 自動重開；交付代次授權 rename 覆蓋；衝突 QUARANTINED（D19 修, D55） | T30 |
| RC-04 | inspection 表（D19 ③, D24） | T27, §20 |
| IR-01 | library streaming digest 寫入 manifest，永不重算取代；Target rebuild 亦不從檔案算基準（D4, D49） | T10, T19 |
| IR-02 | size + digest 符合才 link 與 DONE（D12） | T10, T19 |
| IR-03 | QUARANTINED 留 expected/observed；LOST / CORRUPT 以 event_id 寫 obligation_history 後重開，Target 端 invalid 列持久化待回報（D12a, D16 修, D54, D55） | T17, T30 |
| RT-01 | Framework 只刪暫存 / Abandoned / Target 殘留；紅線（D35）；未完成義務不按時間刪，purge 以 identity 整組 COMPLETED 為條件（D56） | T21 |
| RT-02 | statfs 門檻拒寫、告警；Target 閘門關閉停拉（D21, D43） | T21 |
| §11～13 CP / Config | git + CD、無 CP process、重啟啟用、LKG（D17, D18, D45） | T14, T15, T22, T23, T26, AC-CP, AC-CFG |
| §14 states | 三種持久化 + 顯示推導 §2.6（D42） | T17, T31 |
| §15～17 Ops | `/status` 十題、ops API、audit（D20, D22, D25） | AC-CP-03, T31 |
| §16 metrics | monitoring.md 五軸三層（D27, D33） | §20 |

### 情境驗證清單（T18 / T19 / T30 交錯案例，協定審查 3）

| # | 情境 | 預期 | 規則 |
| --- | --- | --- | --- |
| 1 | DONE(e) 遺失，Shallow 同時發現 LOST | 交付 e 遇 invalid 跳過；X 接受後 e+1 修復 | D55 修 2 |
| 1a | DONE(e) 延遲而非遺失，在 X 接受加代後抵達 | 拒絕 STALE | D55 |
| 2 | STALE{OBLIGATION_NOT_FOUND} 後 Source 補建義務 | 退避重送的 X 被接受、加代、修復 | D54 修 |
| 3 | Shallow 與 Deep 同時讀到 valid | 只有一方建立 X，另一方沿用 | D54 修 |
| 3a | 事件 ACK 遺失與重送 | 同 X 只加代一次；舊 ACK 不清另一事件的 pending | D54, D55 修 |
| 4 | Source rebuild 期間 Target 發現異常 | report 503 重送到恢復；valid 且基準一致的列由匯入或換錨完成，不讀檔；invalid 或不符走修復；舊 X 被接受多一次重交付 | D29 修 2, D55 修 2 |
| 4a | 舊 `/received` 回應延遲抵達一般對帳 | 補帳仍查 (incarnation, epoch)，不繞過 | D55 修, D29 修 2 |
| 5 | rebuild 前有 QUARANTINED 與 paused | 兩者保留；release 解隔離、resume 解暫停，符合條件才收新 incarnation 工作 | D29 修 2, D55 修 2 |
| 6 | 舊 DONE(inc_old) 在 rebuild 後抵達 | STALE，不完成 | D55 |
| 7 | 過期交付 e=7 在修復到 r=8 後才處理 | 跳過，不改列，不算衝突 | D55 修 2 |
| 8 | rebuild 中 crash：旗標持久化後、補建中、部分匯入後 | 重啟維持封鎖重做；既有列、控制狀態與待回報事件保留 | D29 修 2, D29 修 3 |
| 9 | identity 有一 Target 長期 QUARANTINED、另一 Target 已完成且過 60 天，之後 rebuild | 已完成義務不復活為 PENDING，不誤報 unrecoverable | D29 修 3, D56 ② |
| 10 | 對帳 ② 讀到 valid 快照後 Source 接受 CORRUPT 加代 | 條件更新 epoch = 1 失敗，不補 COMPLETED | D29 修 3 |
| 11 | 第 1 天 link 成功但回覆遺失，第 8 天重呼 finalize | 四項比對 → rediscovery 得 SUCCESS，不回 DECLARATION_EXPIRED | D53 修 |
| 12 | 寫入從第 1 天持續到第 32 天才宣告 | content_path 取第 32 天小時目錄，6 h 全掃登記 | D48 修 |
| 13 | 宣告後 `.writing` 被清道夫刪，App 第 9 天重試 | 四項相同、`<key>` 不存在、需再次嘗試發布且超過 N → DECLARATION_EXPIRED，換 key | D53 修, D51 修 2 |
| 14 | Source 還原舊備份成 COMPLETED，Target invalid 已 ACK | 下輪 ② 把 invalid 列當新報告重開，交付後修復；QUARANTINED 者只記歷史 | D55 修 3 |
| 15 | Target 還原舊備份：帶已 ACK 的 invalid X（Source 已修好）與缺少備份後新增的 B | 重送 X 得 DUPLICATE → Source COMPLETED 證據 ＋ 基準相符 ＋ 重算 digest 通過 → valid；B 由 F25 補列 | D29 修 6 |
| 15a | 同上，但 Source 也還原到 X 之前（備份旗標 report_pending = false 失真），且 Target 恢復期間 `/received` 回 503 | 重送 X 得 ACCEPTED → Source 補回 X 並加代 → 維持 invalid 等修復交付；不得依備份旗標清事件 | D29 修 6 |
| 15b | 同上，但 Source 不可達或該義務為 PENDING | 維持 invalid 與 unknown，旗標保留；`/locate` 不回 READY | D29 修 6 |
| 15c | 15b 之後 Source 恢復可達且為 COMPLETED，或 Target 中途重啟 | 旗標與補建游標皆持久化，下輪重跑 → X 退出 invalid，B 由游標續行補回；不會再互等 | D29 修 6, D29 修 7 |
| 15d | 恢復檢查 ① 得 DUPLICATE 後 Source 還原到 X 之前並換 incarnation，再回舊 COMPLETED | ② 見 incarnation 不同 → 重做 ① → ACCEPTED → 等修復交付；X 不被清掉 | D29 修 7 |
| 15e | 恢復工作取得 X 快照後，正常修復先完成又產生 Y | 回寫條件 event_id = X 不符 → 丟棄舊結果；Y 保留 | D29 修 7 |
| 15f | 補建中 Source 換 incarnation（空頁亦帶）、或 Target 再次從備份還原 | 前者該 Source 從頭枚舉、已補列保留；後者全部進度重設 | D29 修 8 |
| 15g | 補建某頁：一項 ENOENT、另一項 stat timeout；補建期間某列 COMPLETED → 重開 → 再 COMPLETED | ENOENT 建 invalid + LOST；timeout 本頁不提交、游標不動、重試；重開後再完成者取新 completed_seq，由修復交付或下週期續讀建列，keyset 不跳項 | D29 修 8, D29 修 9 |
| 15h | Target 發布 A 送 DONE 逾時，還原到無 A 的備份後，舊 DONE 才抵達 Source 完成 A | A 的 completed_seq 大於游標，下一週期續讀補列；期間 `/locate` 回 DATA_NOT_READY 屬保守 | D29 修 9 |
| 15i | 兩個 DONE 並發，A 已取號但延遲提交 | B 取不到號直到 A 提交；列舉看到 B 即 A 已提交 | D29 修 10 |
| 15j | 兩個 DONE 並發，A 尚未取號時 B 先取較小號並提交，列舉恰在其間發生 | 游標推到 B；A 之後取得較大號，下次讀到；一頁補 201 列各有唯一號，不因頁界漏列 | D29 修 11 |
| 16 | ingest 在 identity 與第 5 個義務之間 crash | 同一交易回滾，記憶體集合未更新，下輪重做；①″ 找不到孤立 identity | D10 修 |
| 17 | Target link 成功後、received 提交前 crash；或 fsync 前斷電 | 前者不送 DONE，下輪交付 EEXIST 驗證補列；後者暫存不算持久化，重拉 | D12 修 |
| 17a | 修復的 rename 逾時，正式檔本就存在 | 保留所有權與 in-flight，不刪暫存、不送 DONE、不重拉；舊操作結束後依結果與原代次查證 | D12 修 2, D51 |
| 18 | 事故寫入後 process 立即重啟，未被 scrape | `unacked_incident_count` 由 DB 算出仍 > 0，告警不丟；ack 後才清 | D33 修 3 |
| 19 | 已 READY 的檔被 Deep 標 invalid 後 Consumer 再次 read | 每次 read 重新 `/locate` → DATA_NOT_READY；已開啟的串流不撤銷 | D23 修 2 |
| 20 | Node C 以自己的憑證呼叫 `/pending?target=B` | 身分 ≠ target → 403 | D14 修 2 |
| 20a | Source A 正常讀 Target B 的 `/received` | 只取得 source_node = A 的列 | D14 修 2 |

## 12. 已知未決

- §18 Local availability / latency 數值：待目標 NAS 上量測 Finalize 基準。
- Alertmanager 告警規則與 Grafana 儀表板 JSON：實作階段交付。
- 新 Node 首次初始化（人工放第一份 active.json）不在 AC-CP-02 保證內（ADR-0003）。
- rebuild 完成時間：由全量重建測試量測（D29 修 2）。
- 階段結論（協定審查 3、驗收審查 5）：本輪協定分支已形成明確規則與故障驗證清單（§11），尚待實作測試確認。D56 已定案（搜尋時窗、identity 整組 purge、遲到發布預算、恢復限制）。 D1～D56 及修訂已封閉，剩餘為下列兩項驗收，估算列為前提不作承諾。
- 備選架構（架構審查 4，未採用）：薄表 ＋ Source repair 授權。Target 不比較代次，交付規則 7 → 5；但災難匯入、事件 outbox、F25 都得保留，並需「週期宣告 → ACK 裁決 → 重新觀察 → 換事件」流程與區分 COMPLETED / QUARANTINED 的 ACK；收益與未封閉情境待驗證。central sync service 與 push 已評估關閉。
- 檔案大小分佈（§18 TBD）：每 Target 每日 2.16×10⁶ 檔，1 TB/日 envelope 隱含平均 463 KB；範圍 KB～5 MB，平均 1 MB 即 2.16 TB/日，追平無餘裕、Deep 一輪 37.5 天超過保留期。量測後重算 D31 餘裕與 D52 三項預算（事實列）。
- NAS failover 驗收（D12 修 2）：實際 OS / NFS client / NAS 組合下 fsync 後的穩定寫入保證、逾時操作的未知結果恢復；文件中的持久化關卡以此驗收為前提。

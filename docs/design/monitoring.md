# Observability 設計（草稿，併入 system-design.md）

詞彙見 CONTEXT.md；指標最低要求見 spec §16；表結構見 D24；時戳規則見 D26。

監控分軸與分層。軸是要保證的性質，每軸對應一條設計目標；層是誰看、多急：第一層告警叫醒人，第二層診斷在儀表板查原因，第三層趨勢做容量規劃。每軸第一層只有一個指標。除 Availability 軸的本地交易指標由 library 在 Application 主機曝出外，全部由 Source Node 的 sync service 從自身 DB 與 statfs 算出，粒度為 Source→Target。

## 粒度與呈現

Node 端不做任何跨 Node 計算，只曝帶標籤的原始值：Completeness、Freshness、Integrity 三軸帶 `{source,target}`，Availability 與 Trust 帶 `{node}`。每個 Node 的 sync service 與 library 以 Actuator 曝 Prometheus 格式，Prometheus 抓每個 Node，Grafana 呈現：

- **Overall**：跨標籤聚合，age 取 max、backlog 取 sum、lag 取全體分位數。
- **By phase**：以 `source` 或 `node` 標籤下鑽，再以 `target` 下鑽到一對。

Prometheus 的 `up{node}` 與 `absent()` 即為「停止更新」告警的實作，Trust of view 軸的 `last_status_update_time` 由此取代；CLI 扇出 `GET /status`（D20）保留給 ops 操作與 metrics 管線不可用時的備援查詢。

## 總表：軸 × 層

| 軸 | 保證 | 第一層：告警 | 第二層：診斷 | 第三層：趨勢 |
| --- | --- | --- | --- | --- |
| Completeness | 應有的都有義務、都到了 | scan 過期 / unknown > 0 | coverage、missing、backlog by state | 掃描耗時、DB 列數 |
| Freshness | 到得多快、最糟卡多久 | oldest_pending_age | lag P95/P99、drain rate、reachable | `/pending` 延遲、傳輸速率分佈 |
| Integrity | 到的是對的 | 未確認事故 > 0 | deep check coverage、failure by reason | 摘要重算耗時 |
| Availability | 本地交易與容量不受影響 | local_write_availability、capacity | node/storage/service health、bytes 分類 | NFS timeout 次數、容量消耗速率 |
| Trust of view | 看到的是現在的 | last_status_update_time 過期 | config 版本一致性 | 扇出延遲 |

## 1. Completeness：應有的都有義務、都到了嗎（DG-04；§15 Q1、Q4、Q9）

應有集合 = NFS manifest × Policy。Completeness 的答案是「對帳 coverage 完整 ＋ unknown = 0 ＋ scan 未過期」，不是 success rate；success rate 只統計做過的義務，漏掉的不在分母裡。

| 層 | 指標 | 計算來源 | 門檻（擬 D33） |
| --- | --- | --- | --- |
| 一 告警 | `last_complete_scan_time`、`unknown_count` | `inspection` 最近一筆 finished_at、unknown 欄 | scan 過期 > 2 個週期警告；unknown 持續 > 1 個週期警告 |
| 二 診斷 | `reconciliation_coverage` | `inspection.scope`、`cutoff`：對帳到哪一天；超出 D 天的範圍明示未對帳（D10） | |
| 二 診斷 | `missing_count`、`mismatch_count` | Source 收到的 LOST / CORRUPT 計數，加 `/received` 比對的紀錄不符 | |
| 二 診斷 | `unfinished_outside_window_count{source,target}` | 窗外未完成義務數，由 Shallow ①′ 直接 stat content_path 檢查（D56 ③） | |
| 二 診斷 | `backlog_obligation_count{state,reason}`、`backlog_bytes` | `obligation` 按 state 與 last_error reason 分組；bytes 為 `file_identity.size` 加總。reason 至少分 paused / capacity / unreachable / retry | |
| 三 趨勢 | 掃描耗時、`obligation` / `file_identity` 列數、`cleanup_deleted_count{kind}` | 每次掃描 finished_at − started_at；DB 列數；清道夫（D35）刪除計數 | |

## 2. Freshness：到得多快、最糟卡多久（DG-03；§15 Q2、Q7）

用兩個數字並列回答：lag P99 說正常有多快，oldest_unfinished_age 說最糟卡了多久。只給 lag 會掩蓋卡住的資料。兩者時戳皆由 Source 時鐘打（D26）。

| 層 | 指標 | 計算來源 | 門檻（擬 D33） |
| --- | --- | --- | --- |
| 一 告警 | `oldest_pending_age{source,target}` | now − min(`obligation.source_ready_at`) where state = PENDING；涵蓋退避中、paused、unreachable、窗外 | > 2 h 警告（以 `paused{source,target}` gauge 排除）；> 12 h 事故（不排除）（D33 修 2） |
| 二 診斷 | `oldest_unfinished_age{source,target}`、`oldest_quarantined_age{source,target}` | 全部非 COMPLETED 的原始指標與 QUARANTINED 的 age；QUARANTINED 進入時已各自觸發事故，之後為人工佇列，只上儀表板 | |
| 二 診斷 | `replication_lag{source,target}` P95 / P99 | 收到 DONE 時 now − source_ready_at，只算 COMPLETED；正常與 recovery 時段分開 | |
| 二 診斷 | `net_backlog_drain_rate`、預估剩餘時間 | 時間窗內 COMPLETED 數減新增義務數；剩餘 = backlog / drain rate。只在 recovery 時段有意義 | |
| 二 診斷 | `target_reachable{source,target}`、`paused{source,target}` | 記憶體 last_pending_at 過期 60 s → UNREACHABLE（D42）；`target_control.paused` | |
| 三 趨勢 | `/pending` 回應時間、單筆傳輸速率分佈 | HTTP server 端計時；Target `/report` 附 bytes 與耗時 | |

## 3. Integrity：到的是對的嗎（DG-04；§15 Q6）

| 層 | 指標 | 計算來源 | 門檻（擬 D33） |
| --- | --- | --- | --- |
| 一 告警 | `unacked_incident_count{kind ∈ unrecoverable, integrity_failure, identity_conflict}` | DB 查詢的 gauge：`obligation_history` 中 acknowledged = false 的事故事件數；事故與未確認狀態同交易持久化，重啟不丟、未 scrape 不丟 | > 0 事故，只由 ops `ack` 清除（D33 修 3）；`incident_events_total{kind}` counter 與存量 `{kind}_count` 只作統計與儀表板 |
| 二 診斷 | `deep_check_cycle_days{node}` | Target Deep 自查上一輪完整覆蓋所花天數，觀測值 | 壓測後 T30 期限設為 operational policy 常數，告警 = 觀測 > 常數，不隨觀測放寬（D52 修 2） |
| 二 診斷 | `received_invalid_count{node}`、`report_pending_count{node}` | Target 端已發現、待修復的異常數與待回報數（D54）；pending 持續不降 = Source 不可達、ACK 失敗或 STALE 退避中（D54 修） | |
| 二 診斷 | `stale_delivery_count{source,target}` | Target 收到代次低於 received 列的過期交付次數（D55 修 2） | 連續多輪同一列較低代次 → 警告疑似 Source 狀態回退 |
| 二 診斷 | `shallow_check_coverage{node}`、`deep_check_coverage{node}` | Target 端自查進度（D16 修、D19 修）：Shallow 每 6 h 覆蓋保留期內全部 received（D19 修 2）；Deep 滾動一輪週期為量測值；分母只含 valid 列，invalid 列以 `received_invalid_count` 呈現為已知異常，略過的不算已檢查（D54 修）；只回報 LOST / CORRUPT，覆蓋率靠此證明 | |
| 二 診斷 | `replication_failure_rate{reason}`、`retry_count` | `obligation.attempts`、`/report FAILED` reason 計數；標明分母與時間窗 | |
| 三 趨勢 | Deep check 每輪耗時 | Target 回報 VERIFIED 的批次計時 | |

## 4. Availability：本地交易與容量受影響嗎（DG-01、DG-02；§15 Q3、Q10）

| 層 | 指標 | 計算來源 | 門檻（擬 D33） |
| --- | --- | --- | --- |
| 一 告警 | `local_write_availability`、`local_write_latency` | library 於 Application 主機曝出，掛 Application 的 Actuator `/actuator/prometheus`，標籤 `{node, app_instance}`（D23 修）：Finalize SUCCESS 率、P95/P99 | 綁 §18 TBD |
| 一 告警 | `capacity_alert_status` | statfs usable_free_bytes 對 Policy 門檻 | 進入 alert 警告；進入 reject 事故 |
| 二 診斷 | `node_health`、`storage_health`、`replication_service_health`、`storage_gate_state` | `/health` readiness（DB、NFS 狀態，D34 修）、statfs 成功與否、NFS 操作 timeout 計數、Target 閘門 open / half-open / closed（D43）；皆帶最後更新時間，過期顯示 unknown | |
| 二 診斷 | `usable_free_bytes`、`protected_source_bytes`、`temporary_bytes` | statfs 與 RT-01 保護集合；backlog_bytes 與實際占用分開呈現 | |
| 二 診斷 | `cleanup_last_success_time{node}` | 清道夫上次完整成功跑完三項職責的時間；啟動、部分完成、任一 NAS 失敗都不更新（D35 修） | now − 值 > 2 天警告；> 19 天事故：遲到發布發現保證失效（D56 ④） |
| 二 診斷 | `nfs_pool_exhausted_count{host}`、`nfs_pool_in_use{host}` | 有界執行器滿而拒絕的次數、當前占用槽數（D51）；持續占滿 = NAS 卡住 | |
| 三 趨勢 | NFS 操作 timeout 次數（每主機）、容量消耗速率、各項頻寬預算實際用量 | library 與 sync service 的 timeout 計數；usable_free_bytes 的日變化；Deep / 拉取 / 供檔 bytes/s（D52） | |

## 5. Trust of view：我看到的是現在的嗎（§11、§12；§15 Q3、Q8）

全域視圖是 CLI 扇出各 Node `GET /status`（D20），沒有常駐 CP。看不到的 Node 必須顯示為 unknown 加上次成功時間，不能延續舊的綠燈。

| 層 | 指標 | 計算來源 | 門檻（擬 D33） |
| --- | --- | --- | --- |
| 一 告警 | `up{node}` | Prometheus 抓取狀態；不用 `absent()`（新 Node 無義務時誤報）。CLI 扇出時失敗者標 unknown 並附上次成功時間 | 抓不到 > 3 個抓取週期即事故級 page：這是 15 min 主機恢復目標的起點（D37 修） |
| 二 診斷 | `rebuild_in_progress{node}` | D29 修 2 隔離期間為 1：Node 間端點全 503，其他 Node 對它顯示 UNREACHABLE 不告警 | |
| 二 診斷 | `active_config_version{node}`、`activation_failure_count{node}` | sync service 記憶體 active config；D17 驗證失敗計數；全域比對版本一致 | |
| 三 趨勢 | 扇出延遲 | CLI 每 Node `GET /status` 耗時 | |

## 橫切規則

1. **每個告警指標都要有「停止更新」告警**。指標消失與指標為零在儀表板上長得一樣，沉默必須可偵測。
2. **每個指標可回溯到 D24 的一張表或一次 statfs**；算不出來的不列。
3. **正常與 recovery 時段分開呈現**；完成 lag 的分位數必須與未完成量與 age 並排。

## Runtime performance（第三層，跨軸）

Performance 是手段不是保證，不另立軸：結果性能在 Freshness 軸，元件性能在各軸第三層。以下是 process 層級的 runtime 指標，全部不告警，後果已由第一層指標接住（NFS 慢反映到 local_write_latency，DB 慢反映到 age）。

| 類別 | 指標 | 來源 | 用途 |
| --- | --- | --- | --- |
| HTTP | `http_server_requests` 分位數，按 uri 分 `/pending`、`/file`、`/report` | Actuator 內建 | /file 慢 = NAS 讀或網路；/pending 慢 = DB 查詢 |
| DB | `hikaricp_connections_pending`、query 耗時 | Actuator 內建 + JPA 統計 | obligation 表變大後最先出問題處 |
| JVM | heap、GC pause、thread count | Actuator 內建 | 違反「禁止整檔進記憶體」會在 heap 現形 |
| NFS | 每種操作（stat、create、link、read）耗時直方圖與 timeout 次數，library 與 sync service 各一份 | 自訂 | 證明 D3b 隔離有效 |
| 傳輸 | 每 Target bytes/s、並發數 | Target sync service 自訂 | 對照 catch-up 算術（擬 D30） |

自訂的只有 NFS 直方圖與傳輸 bytes/s，其餘為 Micrometer 預設。

## 基礎設施（D27 修）

Prometheus 與 Grafana 用公司現有監控基礎設施，每 Fab 一組，資料保留 90 天。告警規則與儀表板 JSON 進 repo，Alertmanager 評估；watchdog 永遠 firing 送既有 NMS 作死人開關，NMS 另收事故級。全域視圖 = Grafana；驗收與 Prometheus 不可用時 = CLI 扇出（D20 修）。

## 待決

- Availability 軸門檻綁 §18 的 local availability / latency TBD。

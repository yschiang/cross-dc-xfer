# 實作路線圖（Plan 00）

> 依據：`docs/spec.md` v0.3、`docs/design/system-design.md`、`docs/design/design-decisions.md`（D1–D56 及修訂）、`docs/design/monitoring.md`、`docs/design/file-inventory.md`、ADR-0001～0003。
> 交付分為 M1、M2；實作範圍與依賴以 P01–P14 管理。協作方式見 [README 工作流](../../../README.md#工作流)。

## Milestone → Feature → 實作計畫對照

| Milestone | 交付成果 | 驗收條件 |
| --- | --- | --- |
| **M1：跨 Node 檔案讀取** | App 發布後，交易能在指定 Node 讀到正確副本。 | 發布 → ingest → Target 下載、驗證與持久化 → Consumer 讀取的端到端路徑通過；完成 P13 對應 NAS 驗收與 P14 基本交付測試。 |
| **M2：故障恢復與事故追蹤** | 約定故障後，副本恢復可讀，事故與恢復結果可查。 | LOST／CORRUPT、重送、重啟及支援的 DB 還原情境通過；invalid 讀取保護、事故告警與操作稽核正確。恢復限制依 F24b／D56；完成 P13／P14 對應故障與容量驗收。 |

| Milestone | Feature | 主實作範圍 | 整合依賴 |
| --- | --- | --- | --- |
| M1 | 可靠發布來源檔案 | P01、P03 | P02；P09 暫存清理與保留規則 |
| M1 | 拉取並保存指定副本 | P04、P05 | P02、P03；P09 暫存清理 |
| M1 | App 透過 library 讀寫檔案 | P10 | P01、P02；sync service `/locate`；完整讀取路徑依賴 P03–P05 |
| M2 | 副本自動修復 | P06、P07 | P04 事件處理、P05 修復交付、P10／`/locate` 讀取保護 |
| M2 | 重建與還原後恢復同步 | P08 | P03、P06、P07；P04／P05 代次與交付契約 |
| M2 | 事故告警與追蹤 | P11、P12 | P05–P11 的狀態、事故與指標 |

- **共用支援**：P02 提供服務、schema、認證與設定；P09 提供檔案生命週期與保留規則，納入 M1，M2 延用。
- **驗收排程**：P13、P14 按 Milestone 執行對應子集；完整回歸與容量驗收在全部功能就緒後執行。M2 整體驗收建立在 M1 上，個別工作依 Pxx 依賴排程。

## 建置假設（可改，改了只動 P01 的 Task 1）

| 項目 | 值 |
| --- | --- |
| Build | Maven 3.9 多模組（parent pom + 子模組） |
| Java | 21（LTS） |
| Framework | Spring Boot 3.x 最新 GA（僅 library starter 與 sync service；core 不依賴 Spring） |
| 測試 | JUnit 5、AssertJ；DB 測試 H2 Oracle mode（本機）/ Oracle Free container（CI，D9 註） |
| JSON | Jackson 2.x |
| Migration | Flyway（P02 起） |
| Root package | `com.example.filesync` |

## 模組

| 模組 | 內容 | 依賴 |
| --- | --- | --- |
| `file-sync-core` | 無 Spring、無 DB。FileIdentity、PathLayout、Manifest 編解碼、SHA-256、NFS 有界執行器、Finalize 協議、rediscovery、Policy / config 資料模型與驗證 | Jackson |
| `file-sync-library` | Spring Boot starter。`/policy` client + fallback 檔、statfs WriteGate、`/locate` 的 exists / read、Local Transaction 指標 | core |
| `file-sync-sync-service` | Spring Boot app。schema、掃描 ingest、Node 間端點、Target puller、對帳、自查、rebuild、清道夫、ops API、config 啟用 | core |
| `file-sync-cli` | 包 ops API、依 Policy 扇出 `/status` | core（Policy 模型） |
| `file-sync-nfs-acceptance` | SR-03 六項、D12 修 failover、D3b 隔離的驗收腳本；在真實 NAS 上跑 | core, library |

## 子計畫

| # | 交付 | 相依 | 主要決策 | 負責 F（D57） | 建議分工 / 模型 |
| --- | --- | --- | --- | --- | --- |
| **P01 core Finalize** | `file-sync-core`：identity、路徑、manifest、digest、有界執行器、beginWrite / write / finalize / discard 的完整協議與冪等重試；本機檔案系統測試覆蓋 F1–F5b、F18 的 library 側 | — | D1–D4, D44, D48 修, D51, D51 修 2, D53 修, D56 ④ | F1, F1b, F2, F2b, F3, F4, F5, F5b, F18, F19, F32（library 側） | Claude / Opus 5 |
| **P02 sync-service 骨架** | Spring Boot app 啟動序列（D34）、config 載入 / candidate 驗證 / active↔lkg（D17, D45, D30 修 5/6）、Flyway schema = D24 修 全表（含 epoch、incarnation、seq_counter、rebuild_progress、node_meta）、每 Node token 認證 filter（D14 修 2）、Actuator + `/health` readiness（D34 修）、`/policy` | P01 | D17, D24 修, D45, D14 修 2, D34, D34 修 | F8, F18（NFS 探測）, F22, F23, F31（認證）, F33 | Codex / Sonnet 5 |
| **P03 掃描 ingest** | 增量掃（當前 + 前 2 小時、記憶體 key 集合）、ingest 交易（file_identity + 全部 obligation 同交易）、全量對帳掃 ①（30 天逐小時）+ manifest 桶枚舉、①″ 補缺列、inspection 寫入 | P02 | D6, D10 修, D36, D50, D53, D56 ③ | F2, F2b, F6, F7 | Codex / Sonnet 5 |
| **P04 Source 端點** | `/pending`（PENDING FIFO + `state=COMPLETED` keyset）、`/file`（403 / 410 / 503 工作預算、streaming、100 MB/s 預算）、`/report` 的 DONE / FAILED / LOST / CORRUPT 與三種 ACK、epoch / incarnation 條件更新、obligation_history 去重、`/received` keyset、seq_counter 取號 | P03 | D12a, D13, D40, D30 修 3/4, D52, D55, D55 修, D55 修 3, D29 修 10/11 | F11, F12, F14c, F14d, F14g, F28, F31 | Claude / Opus 5 |
| **P05 Target puller** | 每 Source 佇列 + 4 全域槽、儲存閘門（D43）、下載 → 驗證 → fsync → link / rename → received 交易 → DONE 的關卡、交付分支表（D55 修 2 全部分支）、invalid 列與單一事件 X、report_pending 背景重送與 ACK 處理 | P04 | D12 修, D12 修 2, D41, D43, D51 修, D54, D54 修, D55 修 2 | F9, F10, F11, F12, F13, F14, F14b, F14c, F14e, F14f, F14g, F15, F16, F18, F20, F30, F32 | Claude / Opus 5 |
| **P06 Source 對帳** | Shallow ①′（stat content_path → SOURCE_LOST + UNRECOVERABLE 歷史）、② `/received` 比對（含 invalid 視同新事件）、③ inspection；`unacked_incident_count` | P04, P05 | D19, D56 ③, D55 修 3, D33 修 3 | F14, F21, F26, F32 | Codex / Sonnet 5 |
| **P07 Target 自查** | Shallow（readdir + size，全保留期）、Deep 滾動 20 MB/s、Target 端對帳 ②（keyset completed_seq、rebuild_progress 游標）、coverage 指標 | P05 | D16 修, D19 修 2/3, D29 修 9, D52 修 2 | F14, F14b, F14e, F25, F26, F32 | Claude / Opus 5 |
| **P08 rebuild 與恢復** | Source rebuild 五步（隔離 503、換 incarnation、只補不刪、epoch 重設 1、匯入）、Target 還原恢復檢查（recovery_pending、D29 修 6/7 的 ①②）、crash 重做 | P06, P07 | D29 修 2/3/6/7 | F14d, F24, F24b, F25, F25b, F32 | Claude / Opus 5 |
| **P09 清道夫** | 三職責、紅線、purge 以 identity 整組、`cleanup_last_success_time`、跳過 in-flight uuid | P03 | D35 修, D56 ②④, D51 修 | F1, F1b, F2b, F9 | Codex / Sonnet 5 |
| **P10 library starter** | `/policy` 快取 + fallback 檔、WriteGate = Policy 登錄 + statfs 門檻、`exists` / `read` 走 `/locate`（每次重查）、Local Transaction 指標、四項設定 | P01, P02 | D18, D21, D23 修 2, D46 | F20, F22, F27 | Codex / Sonnet 5 |
| **P11 ops API + CLI** | `/status` 十題、retry / release / evict / ack / pause / resume / reconcile / rebuild / config、ops_audit、CLI 扇出 | P06 | D20, D22, D25, D33 修 3 | F28 | Codex / Sonnet 5 |
| **P12 監控交付** | monitoring.md 全部指標、Alertmanager 規則、Grafana JSON、watchdog | P05–P11 | D27, D33 系列 | F29 | Codex / Sonnet 5 |
| **P13 NFSv3 驗收** | SR-03 ①–⑥、D12 修 failover、D3b 隔離、O_EXCL / link 語意在目標 NAS 上的實測 | P01, P05 | D3b, D12 修 2 | F17, F18, F19（目標 NAS 實測） | 人工 + 腳本 |
| **P14 E2E 與壓測** | 三 Node docker-compose（含 NFS server 容器）、T01–T32 可自動化子集、§20 場景、D31 / D52 預算壓測；按 M1／M2 分批驗收 | 各驗收子集依賴其對應實作；完整回歸依賴全部 | §11 | F8, F8b, F15, F16, F17；全表可自動化子集 | Claude / Opus 5 |

## 平行化

```
P01 ──┬── P02 ──┬── P03 ── P04 ── P05 ──┬── P06 ──┬── P08
      │         │                       │         └── P11
      │         │                       └── P07 ──┘
      │         └── P10
      └── P13 Source 子集（在真實 NAS；完整 P13 等 P05 等相關實作）
P09 依賴 P03；P12 全量整合等 P05–P11；P14 按 Milestone 分批，完整回歸最後。
```

P02 與 P13 的 Source 驗收子集可在 P01 完成後平行；P10 與 P03 的獨立部分可平行，但 Consumer 完整讀取驗收須等 `/locate` 與對應資料路徑可用。

## 每份計畫的固定規則

- 各項並行實作使用獨立 branch／worktree，PR 連到對應 ticket 與實作計畫。
- 每個 task 以測試先行，`mvn -q -pl <module> test` 綠燈才 commit。
- ticket 的 AC 引用本 P「負責 F」欄的故障列；plan 依 system-design §6 的測試策略，為每條負責的 F 列出具體操作的測試矩陣（D57）。測試欄的 T 見 §6 各列。
- 對外契約（HTTP 欄位、DB 欄位、manifest 欄位）以 system-design.md 與最新適用的設計修訂為依據；衝突時先釐清並同步文件。單純修正實作計畫不新增決策；需要改變契約時，先完成設計審查並更新 design-decisions.md 與相關文件。
- 「禁止整檔進記憶體」與「每個 NFS 操作經有界執行器」是 code review 硬規則。

# Requirement → Design → Test 對照

依據 `docs/spec.md` v0.3；D 編號見 `design-decisions.md`；§ 指 `system-design.md` 章節。

| Requirement | 設計封閉點 | 驗證 |
| --- | --- | --- |
| DG-01 Node Independence | 每 Phase 獨立 NAS / Oracle / sync（§1）；pull 使 Target 故障 = 不來拉（D12a）；per-Source thread pool 互不阻塞（§8） | T02, T03, T12, AC-NODE-01/02 |
| DG-02 Local-First | library 只碰 NAS + 快取 Policy（D18, D23）；Finalize 不碰 DB / sync service（D3） | T02, T14, AC-NODE-02 |
| DG-03 Eventual Consistency | 義務持久於 Source DB + NFS 可重建（D1, D8）；Target 復活自動拉（D12） | T05, T24, AC-NODE-04 |
| DG-04 No Silent Data Loss | manifest 為基準（D3）；Shallow / Deep check（D19, D16）；外部刪除 → unrecoverable（RT-01） | T10, T11, T30, AC-NODE-05 |
| DG-05 Operational Resilience | Target 零狀態 crash 重問（D12）；Finalize 冪等（D3a）；config LKG（D17） | T06, T07, T09, T26, T32 |
| SR-01 Standardized Access | library API（D23）；Namespace / Data class 登錄檢查（D18）；Logical key 唯一性為 App 義務（D8） | T20, T29 |
| SR-02 NFS Ownership | Framework 只面對 mount 語意；mount 參數為 infra 交付（D3b, §5） | T13 |
| SR-03 NFSv3 Compatibility | §5 驗證清單 ①–⑥ | T13, T25 |
| SR-04 Operation Outcome | 三態 Finalize + 冪等重試（D3, D3a）；ESTALE → PENDING_CONFIRMATION（§5） | T13, T25, T29 |
| SR-05 I/O Isolation | NFS op executor 有界 pool + timeout（§5, §8） | T25 |
| SR-06 Read Availability | `exists()` = stat + Policy，不碰 DB / 遠端（D23）；NOT_EXPECTED（D15） | T28 |
| FR-01 Immutable Ready | link 拒絕覆蓋（D3）；Target 保留原 identity 與路徑（D2） | T20 |
| FR-02 Source Ready Boundary | commit point = link（D3）；Rediscovery 規則（§2.4）；Abandoned TTL（D11） | T18, T29 |
| FR-03 Target Visibility | Target 以 link 發布，`.writing` 不可見（D12）；半截 manifest 視同不存在 | T08, T28 |
| FR-04 Identity Conflict | O_EXCL manifest + link EEXIST（D3）；Target 不符 → QUARANTINED + evict（D25） | T20 |
| RR-01 Async | Finalize 不等任何 Target（D3, D8） | T01 |
| RR-02 Durable Work | obligation 表（D24）；manifest 重建（§2.6） | T06, T07, T18 |
| RR-03 Idempotency | in-flight set + received 查重 + link EEXIST（D12, §3.4） | T09, T19 |
| RR-04 Retry & Isolation | Source 端退避 next_attempt_at（D12a）；per-Source pool（§8）；QUARANTINED / BLOCKED 轉移（§3.3） | T12, T17 |
| RR-05 Controlled Catch-up | Target 自控並發 / bytes/s（D12a）；FIFO（D13） | T16, T24 |
| RC-01 Independent Discovery | 三層掃描 + Policy 推導（D10）；不依 task 表（D1） | T11, T18 |
| RC-02 Coverage | Shallow check 三步（D19）；Deep check（D16）；unknown 標示 | T11, T27, T30 |
| RC-03 Repair & Re-verify | 重開義務走完整驗證（§3.3）；QUARANTINED 不自動覆寫（D25） | T10, T30 |
| RC-04 Inspection Evidence | `inspection` 表（D24）；覆蓋範圍 metric（D10） | T27, AC-NODE-05 |
| IR-01 Verification Baseline | library streaming digest 寫入 manifest，不重算（D4）；O_EXCL 防覆寫（D3） | T10, T20 |
| IR-02 Completion Conditions | DONE 只在 digest 驗證 + link + received 之後（§3.2） | T08, T10 |
| IR-03 Persistent Integrity Failure | QUARANTINED 保留 expected / observed；VERIFIED 不符重開並留歷史（§3.3） | T17, T30 |
| RT-01 Retention Protection | Framework 不刪 Source（domain Q19）；Abandoned 留原地（D11） | T21 |
| RT-02 Capacity | library / sync / Target 三處 statfs 與門檻（D21） | T21 |
| §11 Control Plane | git + CD + CLI 扇出，無 process（D17, D20, ADR-0003）；Policy 段變更拒絕 | T15, T22, AC-CFG-01/04 |
| §12 Data Plane Independence | 本機 active.json / lkg.json；啟動不碰 git（D17） | T14, T23, AC-CP-01/02/03 |
| §13 Configuration Flow | candidate → 驗證 → active / lkg 兩次原子 rename（§6） | T15, T26, AC-CFG-02/03/05 |
| §14 Operational States | obligation.state 狀態機（§3.3） | T17 |
| §15 Operational Readiness | `GET /status` 十題 + CLI 扇出（D20） | AC-CP-03 |
| §16 Metrics | Actuator；時戳依 ADR-0001 / D26 | — |
| §17 Operational Control | ops API + `ops_audit`；release 不跳驗證（D25）；pause 在 Source（D22） | T31 |
| §20 E2E | 上述全部 | §20 |

## 未封閉項（需驗收前補）

- §18 全部數字（system-design §10）。
- SR-03 驗證清單需在實際 NAS 型號上執行，結果回填 §5。
- Oracle Free container 與 H2 Oracle mode 的行為差異清單（D9）。

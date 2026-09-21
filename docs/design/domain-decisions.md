# Spec v0.3 決策紀錄（grilling 2026-09-21）

供改寫 spec v0.2 → v0.3 用。詞彙定義見 CONTEXT.md。

| # | 決策 | 影響的 spec 段落 |
| --- | --- | --- |
| Q11 | 1 Fab 含多個 Node（Phase）；Node 是唯一正式詞 | §2 Terminology |
| Q14 | §20 的 A/B/C 為同一 Fab 的三個 Phase | §20 |
| Q1 | 本輪產出為 spec v0.3 + domain model，不直接進設計 | — |
| Q2 | File identity = (Source Node, Namespace, Logical key)，Application 提供 Logical key；digest 為屬性 | §2, FR-01, FR-04, IR-01 |
| Q3 | Full mesh：任一 Node 可為自身資料的 Source | §3 DG-01, §11 |
| Q4 | Policy key = (Source Node, Data class)；Data class 於 write 時宣告、Source Ready 時持久化 | §2 Required targets, RC-01 |
| Q5 | Finalize 明確呼叫、三態回傳；新增 Discard 動詞與 Writing TTL → Abandoned | SR-01, SR-04, FR-02 |
| Q6 | Consumer 讀取：contract 路徑提供 DATA_NOT_READY；直接讀路徑只有存在/不存在；T28 只驗 contract 路徑 | SR-06, FR-03, T28 |
| Q7 | Application 主機與 sync 主機分開、共 mount 同一 NAS；跨 Node 共用僅 CP + AD/DNS；state store 為 Node 自有 | §1.2 shared dependency 清單, §12 |
| Q8 | Replication obligation 由 Source Node 擁有；Target 只持有 Completion evidence | RR-02, RR-03, RC-01, AC-NODE-03, T19 |
| Q9 | Local Transaction = 一次 write + Finalize；read 不計入 SLO，只受 SR-06 功能性約束 | §2, DG-01, DG-02, T02, §18 |
| Q9 註 | 未來可能依 lot 下一站動態決定 Target（routing-based targets）；v1 明列 out of scope，Policy 維持靜態 | §21.1 |
| Q10 | Workload 量級：每 Fab ≤10 Node；每 Node 每日 ≥10⁵ 檔案；檔案為 MB 級；推算單一 Target 24h 累積 ≈ 10²–10³ GB（百 GB 至 TB） | §18 Workload envelope, RT-02 |
| Q12 | 同 Fab 各 Phase 獨立 NAS；Storage 不是 intra-Fab 的 correlated failure domain | §1.2, DG-01, T12 |
| Q15 | v1 只支援同 Fab 的 Phase 間同步；跨 Fab 明列 out of scope；§2 刪「Site / Data Center」對應；Q13 隨之作廢 | §2, §21.1, §18, T04 |
| Q16 | Namespace 每 Application 一個，於 Policy 預先登錄；未登錄者 write 時拒絕 | §2, §11, FR-04 |
| Q17 | Data class 於 Policy 預先登錄，未登錄者 write 時拒絕；本地限定資料以空 targets 的 Data class 表示 | §11, RC-01, DG-04 |
| Q18 | Reconciliation = 每輪 Shallow check + 滾動 Deep check；T30 偵測時限 = 一個 Deep check 完整覆蓋週期（§18 TBD 之一） | RC-02, RC-04, IR-03, T30, RR-05 |
| Q19 | Framework 不刪 Source 資料、不提供刪除動詞；Source 保存由現有 NAS 管理政策負責；外部清理刪掉未完成義務的 Source → reconciliation 報 unrecoverable。RT-01 清理約束只涵蓋 Framework 自己的暫存 / Abandoned / 未 Publish 殘留。Retire 動詞留 v2 | RT-01, RT-02, DG-04, §21.1 |
| Q20 | 所有 lag / age 時戳由 Source Node 時鐘打；Target Ready 時刻 = Source 收到 Completion evidence；§18 Measurement 不再需要跨 Node 時鐘誤差參數 | §2, §16, §18 |
| Q21 | Application 的 Logical key 已含自然版本（run id / timestamp / sequence），重測即新 identity；SR-01 明寫「Logical key 對不同內容唯一」為 Application 義務 | SR-01, FR-01, FR-04 |
| Q22 | 新增 NOT_EXPECTED 回應：依 Policy 不會抵達本 Node；與 DATA_NOT_READY、UNAVAILABLE 區分 | SR-06, T28 |
| Q26 | Consumer 以完整 identity（含 Source Node）查詢；不提供跨 Source 查同 key；同 key 多 Source 由 Application 在 Logical key 消歧 | SR-06, FR-04 |

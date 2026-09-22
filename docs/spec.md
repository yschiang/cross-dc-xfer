# Cross-Node File Synchronization Framework — System Specification

Version: 0.3  
Status: Design baseline；domain model 已釘死（見 CONTEXT.md、docs/design/domain-decisions.md、docs/adr/）；量化 SLO 與容量參數須於正式驗收前完成  
Date: 2026-09-21

## 1. Purpose

建立共用的 Cross-Node File Synchronization Framework，使多個 Node 之間的檔案能持續、可靠地非同步同步，並在 Node、Network、Storage 或服務暫時故障後，自動且可驗證地恢復一致性。

**核心保證：在本 Node 的必要服務、Storage、容量正常，且交易所需資料已於本地可用時，其他 Node 的停機不得阻塞本地交易。故障解除後，系統自動補齊未完成的同步義務。**

本文件定義 System Requirements、Constraints、Operational Readiness、Test Cases 與 Acceptance Criteria。Component、Class、Protocol Implementation、Database Schema 與 Deployment Design 留待後續設計。

### 1.1 已確認的第一版範圍

| 項目 | 決定 |
| --- | --- |
| File lifecycle | Ready 後內容不可變；不支援 overwrite、append、rename、delete 的跨 Node 同步 |
| 遠端資料未抵達 | 明確回報資料未就緒；由業務流程等待或重試，不影響其他獨立交易 |
| Supported downtime | 以單一 Target 連續停機 24 小時作為容量與恢復驗收情境 |
| 容量耗盡 | 提早告警；不得靜默清除待同步資料；無法安全接受新寫入時明確拒絕 |
| 故障保護 | 保證暫時故障後的恢復；不保證 Source Storage 永久毀損後零資料損失 |
| Topology | 固定的 Phase（Node）集合，≤10 Node，任一 Node 可為自身資料的 Source；第一版不支援跨部署範圍同步，也不支援運行期間新增、移除或變更同步對象 |
| Identity 與 Policy 登錄 | Namespace 與 Data class 須於 Policy 預先登錄；未登錄者於 write 時明確拒絕，不得接受後靜默不同步 |
| Source 資料刪除 | Framework 不刪除、不提供刪除 Source 資料的動詞；Source 保存由現有 NAS 管理政策負責 |

### 1.2 保證成立的條件

- 至少一份可供恢復的有效資料與必要識別資訊仍然存在。
- 故障端的服務、連線與 Storage 最終恢復，權限及資料衝突等阻礙已解除。
- 負載位於已驗收範圍內，並具備足夠保留容量與恢復處理能力。
- 所有需要同步的寫入都遵循 Framework 的 Storage Access 與 Ready contract。
- Node independence 不代表 shared infrastructure 故障時所有 Node 都可保持正常。已確認的跨 Node 共用依賴僅有 Control Plane 與 AD / DNS；各 Phase 擁有獨立 NAS，Storage 不是部署範圍內的 correlated failure domain。設計若新增任何共用依賴，必須補列其故障影響範圍。

24 小時是保證驗收的停機範圍，不是資料保留期限。超過此範圍仍須持續追蹤、告警並嘗試恢復，不得靜默丟棄義務。

## 2. Terminology

正式詞彙以 `CONTEXT.md` 為準；本表為摘要。

| Term | Definition |
| --- | --- |
| Node | 可獨立運行的部署單位，對應一個 Phase；擁有自己的 Local Storage 與 state |
| Local Storage | 本 Node 自有的 NAS / NFS Storage，由 Application 主機與 sync service 主機共同 mount；不與其他 Node 共用 |
| Source Node | 某份 File identity 的唯一權威 Node，即原始寫入的 Node |
| Target Node | 依 Policy 必須取得副本的 Node；副本保留原始 identity，永不成為新 Source |
| File identity | (Source Node, Namespace, Logical key)；Application 提供 Logical key，Framework 不鑄造 id |
| Namespace | 每個 Application 一個的 identity 分區，於 Policy 預先登錄 |
| Logical key | Application 依業務語意給定、自帶版本的鍵；同 Namespace 內對不同內容唯一，由 Application 保證 |
| Integrity baseline | Source Ready 時與 identity 綁定的 size 與內容 digest；是 identity 的屬性 |
| Data class | Application 於 write 時宣告、Policy 預先登錄的資料分類；Source Ready 時持久化為 identity 屬性 |
| Policy | 初始固定對照表：(Source Node, Data class) → Required targets |
| Required targets | 依 Policy 推導的 Target Node 集合；只由 Policy 與 identity 屬性決定 |
| Writing | 已開始寫入、尚未 Finalize 的狀態；不可消費、不可複製 |
| Finalize | Application 明確宣告寫入完成；結果為 SUCCESS / FAILURE / PENDING_CONFIRMATION |
| Source Ready | Finalize SUCCESS 後的狀態；Framework 對同步義務的承擔自此開始 |
| Discard | Application 主動放棄 Writing 檔案；只允許對 Writing 狀態 |
| Abandoned | Writing 超過 TTL 未 Finalize 或 Discard，由 Framework 判定可清理的狀態 |
| Target Ready | Target 上完成傳輸、Integrity 驗證、持久化與 Publish 的狀態 |
| Publish | Target 端以正式名稱原子地讓已驗證內容對 Consumer 可見 |
| Consumer | Target Node 上讀取已 Publish 資料的 Application |
| Replication obligation | 一份 File identity 對一個 Required target 的義務；由 Source Node 擁有並持久化，Source Ready 時即成立 |
| Completion evidence | Target 持有的「已 Publish 且已驗證」證據；供 Source 查證收斂，不是義務的權威紀錄 |
| Replication Lag | Source Ready 至 Source 收到該 Target 的 Completion evidence 的經過時間；兩端時戳皆由 Source 時鐘打 |
| Backlog | Source Node 上所有尚未 COMPLETED 的義務，含 blocked、quarantined、paused 及恢復中 |
| Reconciliation | 對照應有集合（Source Ready 集合 × Policy）與實際狀態；每輪 Shallow check，滾動 Deep check |
| Shallow check | 不讀內容：比對存在性、size、雙方持有的 Integrity baseline 紀錄 |
| Deep check | 重讀 Target 內容重算 digest 比對；滾動分批，一個完整覆蓋週期即 T30 偵測時限 |
| Local Transaction | Application 對本 Node contract 的一次 write + Finalize；DG-01 / DG-02 SLO 的對象 |
| Control Plane | 跨 Node 共用的管理面：Policy、operational policy 版本與全域可視性；非 Data Plane 即時依賴 |
| Data Plane | 每個 Node 自有的執行面：同步、驗證、重試、reconciliation 與 state store |
| LKG | Last Known Good configuration，可恢復使用的有效設定 |

## 3. Design Goals

### DG-01 — Node Independence

任一 Node 因 Annual PM、Application shutdown、OS maintenance、Storage maintenance、Network isolation 或 Unexpected failure 不可用時，其他健康 Node 的獨立本地交易須持續符合既定 availability 與 latency SLO。

對故障 Node 的同步工作不得阻塞其他 Target 的工作，也不得耗盡正常交易所需資源。

### DG-02 — Local-First Availability

Application 完成本地交易不得等待 Remote Node、Remote replication 或 Control Plane 成功。

本地 Storage 或可恢復接受條件未達成時，不得宣告寫入成功。非同步複製不免除本地持久化責任。

### DG-03 — Eventual Consistency

故障期間保留同步義務與恢復所需資料。故障解除後自動 catch up，並在約定負載及期限內完成，不應要求人工重新搬運資料。

無法由一般 retry 解決的問題須被明確識別；問題解除後可透過受控操作恢復。

### DG-04 — No Silent Data Loss

Framework 必須能在定義的檢查範圍及時限內識別 missing file、partial file、corruption、failed/stuck replication、lost trigger、identity conflict 與 Source / Target mismatch。

不得把未驗證或無法確認的狀態標為 COMPLETED／Healthy；無法恢復的損失必須告警並保留證據。

### DG-05 — Operational Resilience

須可從 Process / Host restart、Service upgrade、Network partition、暫時 Storage / Remote Node / Control Plane outage、Duplicate trigger 與 Interrupted transfer 自動恢復。

## 4. System Scope

Framework 提供下列系統能力：

| 能力 | 必須提供的結果 |
| --- | --- |
| Standard Storage Access | 統一存取、完成寫入、結果查證與錯誤語意 |
| Local readiness | 可持久恢復的 Source Ready 與完整性基準 |
| Cross-Node replication | 依固定同步對象執行非同步傳輸及發布 |
| Reconciliation | 獨立發現缺失義務及資料不一致，修復後再驗證 |
| Operations | 可觀測、受控重試、暫停、恢復與故障查證 |
| Configuration Management | Versioned desired state、本地持久化與安全啟用 |

Standard Storage Access contract 應能作為 Application 與 synchronization service 共用的存取邊界；是否包裝成獨立 library、如何部署屬於設計。

## 5. Storage Access Requirements

### SR-01 — Standardized Access

Application 與 synchronization service 必須遵循統一 Storage Access contract。主要 Client 環境為 Java / Spring Boot。

Contract 須明確定義下列語意；不規定 API signature：

- **write**：須宣告 Namespace、Logical key 與 Data class。Namespace 或 Data class 未於 Policy 登錄者，write 時明確拒絕。
- **Finalize**：Application 明確宣告寫入完成；回傳 SUCCESS（進入 Source Ready）、FAILURE 或 PENDING_CONFIRMATION。close() 或 rename 慣例不得取代 Finalize。
- **Discard**：Application 主動放棄 Writing 檔案；只允許對 Writing 狀態。
- **read / exists**：以完整 File identity（含 Source Node）查詢；回應語意見 SR-06。
- 可重試錯誤與結果待確認的語意見 SR-04。

Logical key 對不同內容唯一（同 lot 重測即新 key）是 Application 的義務；Framework 只負責偵測違反（FR-04）。

### SR-02 — NFS Protocol Ownership

Application 不實作 NFS protocol stack。NFS connection、mount、protocol retransmission 與 Storage controller HA 由 infrastructure 負責。

Framework 面對 mounted filesystem semantics，負責應用可見的完成條件、故障隔離、結果查證及恢復。Infrastructure 必須提供可驗收的持久化與 HA 行為。

### SR-03 — NFSv3 Compatibility

必須在實際 NFSv3 環境驗證 Read / Write、timeout、Storage failover、stale file handle、interrupted I/O、跨 client 的正式發布可見性，以及 endpoint/storage 恢復後的行為。

### SR-04 — Operation Outcome

| 結果 | 語意 |
| --- | --- |
| SUCCESS | 已確認達到該操作約定的完成與持久化條件 |
| FAILURE | 已確認未完成約定操作；殘留暫存資料不得對 consumer 正式可見 |
| UNKNOWN / PENDING_CONFIRMATION | 尚無法判定結果，須可追蹤並在恢復後查證 |

不得將 timeout 直接解讀為沒有副作用，也不得把未知結果當成成功。查證或重試須保留同一邏輯 operation identity。

多個 HA endpoint 必須代表相同 authoritative filesystem；不同權威 Storage 不得被當成單純 client-side failover。

### SR-05 — I/O Failure Isolation

卡住或反覆重試的 Storage I/O 不得耗盡其他正常工作的資源。Application timeout 不得被直接視為底層 I/O 已終止。

故障恢復後，仍在執行的舊操作不得破壞新操作結果或已發布內容。具體隔離與取消方式留待設計。

### SR-06 — Read Availability

Consumer 透過 contract 以完整 File identity 查詢時：依 Policy 應抵達本 Node 但尚未 Target Ready 者回報 DATA_NOT_READY；依 Policy 不會抵達本 Node 者回報 NOT_EXPECTED；無法查證存在性時回報 UNAVAILABLE／UNKNOWN，不得誤報為已確認不存在。

Consumer 直接讀取 NFS 路徑亦允許，但只能得到「存在／不存在」，且「不存在」不代表「不會來」；Framework 只保證 Publish 為原子、partial 不可見。不提供跨 Source Node 以 (Namespace, Logical key) 查詢；同 key 多 Source 由 Application 在 Logical key 消歧。

本地已 Ready 的資料讀取不得依賴 Remote Node 或 Control Plane。缺少某份遠端資料不得阻塞不依賴它的其他交易。

## 6. File Identity & Readiness Requirements

### FR-01 — Single Authority & Immutable Ready File

File identity = (Source Node, Namespace, Logical key)，具有唯一 Source authority；Source Ready 後內容不再修改。不同內容必須以不同 Logical key 成為新的 File identity，不覆寫既有 Ready 內容。

第一版不提供跨 Node overwrite、append、rename、delete propagation。Replicated copy 保留原始 identity，不得被當作新的來源資料形成循環複製。

### FR-02 — Source Ready & Acceptance Boundary

只有完成下列條件，才可向 Application 回報 Ready／finalize SUCCESS：

1. 內容已完整寫入並達到約定的本地持久化條件。
2. Identity、size 與內容完整性基準已建立，且可在故障後恢復。
3. 可恢復地辨識該檔案已被 Framework 接受，並能推導全部 Required targets。
4. 即使 replication task 尚未建立便 crash，仍可重新發現檔案並重建同步義務。

此處是 Framework 對已接受資料承擔同步責任的起點；一般 write 呼叫成功不能取代 Source Ready 的完成確認。

Writing 狀態超過 TTL（operational policy）仍未 Finalize 或 Discard 者，由 Framework 標記 Abandoned 後清理；Abandoned 不產生義務，清理須留下紀錄。

若在接受過程中 crash，恢復後必須能查證結果：已接受者續傳；未接受或未完成者不得誤報 Ready。未能確認者維持可追蹤的待確認狀態。

### FR-03 — Target Visibility

Target consumer 只有在 transfer、identity/size/integrity verification、持久化及 publish 全部成功後，才能看到正式資料。

Partial file 不得以正式名稱或正式讀取入口被消費；暫存檔案的清理不得誤刪正在使用或恢復所需的資料。

### FR-04 — Identity Conflict

同 identity、相同內容的重複操作須安全收斂；同 identity、不同內容或正式目的位置存在衝突時，不得靜默覆寫，須隔離並留下證據。

## 7. Replication Requirements

### RR-01 — Asynchronous Replication

Replication 不屬於 Application 的 synchronous transaction。Local Ready 成功不代表任一 Target 已 Ready。

### RR-02 — Durable Work & Obligation Recovery

已建立 task 的狀態必須持久化，Process crash、Agent / Host restart、Service upgrade 不得遺失未完成工作。

尚未建立 task 的已接受檔案，也必須可重新發現並重建全部同步義務。不得只依 task table 是否有紀錄判斷資料完整性。

Replication obligation 的權威狀態由 Source Node 擁有並持久化；Target Node 只保存 Completion evidence，供 Source 在完成紀錄遺失時查證收斂，不得成為義務的唯一保存處（見 ADR-0001）。

### RR-03 — Idempotency

允許 at-least-once execution。Duplicate trigger、重試及重啟後執行同一義務，不得造成額外正式資料、內容破壞或錯誤狀態。

Target 已發布但完成紀錄尚未保存時 crash，恢復後須可查證並收斂；不能只因缺少完成紀錄便假設 Target 不存在。

### RR-04 — Retry & Isolation

Temporary failure 自動 retry，須避免 tight loop、retry storm 與恢復時瞬間過載。單一 Target 的失敗不得阻塞其他 Target。

持續失敗必須呈現原因、次數、最後嘗試時間與下一步；超出一般 retry 可處理範圍時進入 BLOCKED／QUARANTINED。

### RR-05 — Controlled Catch-up

Backlog 須持久保存，恢復後自動 drain，並控制 recovery rate。持續有新流量時，仍須在約定負載範圍內於恢復期限完成故障期間 backlog。

Recovery、reconciliation 與正常 replication 的合計資源使用不得使正常本地交易超出已約定的 SLO。

## 8. Reconciliation Requirements

### RC-01 — Independent Discovery

Trigger 不得成為完整性的唯一保證。Reconciliation 必須能從可恢復的 Source Ready 集合與固定 policy 推導應有義務，而非只重查既有 tasks。

### RC-02 — Coverage

至少須發現 missing target、integrity mismatch、incomplete/stuck replication、lost trigger、從未建立的 task，以及完成紀錄與實際 Target 不一致。

檢查分兩層：每輪對全部義務做 Shallow check（存在性、size、雙方 Integrity baseline 紀錄）；Deep check（重讀內容重算 digest）以滾動方式分批覆蓋，不得每輪全量重讀。Deep check 的完整覆蓋週期即 T30 的偵測時限，且須揭露為 metric。

對 Source 或 Target 無法讀取的範圍，標示無法確認並保留最後有效結果及時間，不得將檢查失敗解讀為一致或資料不存在。

### RC-03 — Repair & Re-verify

可修復問題須自動建立或恢復同步工作，完成後重新驗證。衝突與無有效副本等問題須隔離／告警，不得無限制覆寫或無聲 retry。

### RC-04 — Inspection Evidence

須記錄每次檢查的範圍、截止點、開始／完成時間、已檢查與未確認數量、差異及修復結果。

對持續新增資料，驗收須使用明確截止點形成應有集合。Shallow check 週期、Deep check 完整覆蓋週期須於正式驗收前定義；Shallow 與 Deep 的覆蓋率分開呈現，不得宣稱涵蓋未實際檢查的資料。

## 9. Integrity Requirements

### IR-01 — Verification Baseline

Source Ready 時須建立與 File identity 綁定的 size 及內容完整性基準；比對不能僅使用檔名、mtime 或 size。

完整性基準須可恢復且不可在 retry 時任意重算取代原值，以免把事後損壞接受為新基準。具體 digest、儲存方式與驗證演算法留待設計。

### IR-02 — Completion Conditions

每個 Target 必須依該基準完成驗證，並確認持久化及發布結果，才可標記 COMPLETED。資訊不足或操作結果待確認時不得完成。

### IR-03 — Persistent Integrity Failure

重複 integrity failure 須進入可識別異常狀態，保留 Source、Target、identity、expected/observed 結果與時間。無法自動修復者須告警。

後續檢查發現已完成資料遺失或損壞時，須重新呈現異常與修復義務，保留原完成紀錄及後續發現的歷史。

## 10. Retention, Capacity & Node Maintenance

### RT-01 — Retention Protection

所有 Required targets 完成驗證前，須保留至少一份可供重傳的有效完整資料，以及重建同步義務所需資訊。

停機滿 24 小時、進入 QUARANTINED 或重試次數達門檻，都不是自動清除的理由。正常清理政策不得刪除仍受保護的資料。

Framework 不刪除 Source 資料，也不提供刪除 Source 資料的動詞；Source 的保存與清理由現有 NAS 管理政策負責，容量規劃（RT-02）以此為前提。本條的「正常清理」只約束 Framework 自身的清理：暫存檔、Abandoned 檔與 Target 端未 Publish 的殘留。

若外部清理刪除了義務未完成的 Source 資料，reconciliation 須報 unrecoverable 並保留證據（DG-04），不視為 Framework 缺陷。跨 Node 刪除與 Source 端刪除動詞（Retire）不在第一版範圍。

### RT-02 — Capacity Management

容量評估須涵蓋 24 小時 downtime、新流量、恢復期間保留量、暫存檔案、state / metadata 與必要餘裕。

接近容量門檻時須告警；無法安全接受新寫入時，明確拒絕受影響儲存範圍的新寫入，不得回報成功後丟棄。

容量耗盡的影響範圍須明確呈現；不得透過靜默刪除未完成資料來維持表面 availability。已接受資料仍須保留義務並繼續恢復。

### Maintenance Acceptance

| ID | Acceptance |
| --- | --- |
| AC-NODE-01 | B shutdown 後，健康 A / C 的獨立本地交易符合 SLO |
| AC-NODE-02 | A / C 不必連線 B 才能完成本地交易 |
| AC-NODE-03 | B 停機期間的全部義務與可恢復資料被保留 |
| AC-NODE-04 | B 恢復後自動 catch up，對 C 的同步持續進行 |
| AC-NODE-05 | 對照應有資料集合證明全部必要副本完整；不能僅以 queue 清空證明 |

## 11. Control Plane Requirements

Control Plane 管理初始固定 topology / target policy、可調整的 operational policy、設定版本及整體可視性。

**第一版不提供運行期間新增、移除 Node 或變更 Required targets 的能力。** 涉及此類變更的設定須被拒絕，不得順帶取消或新增既有同步義務。

Control Plane 不得成為既有 Data Plane 工作的即時必要依賴；全域視圖須顯示各 Node 的狀態更新時間及未知／過期狀態。

## 12. Data Plane Independence

每個 Node 必須具備足夠的持久化 local state，包含有效設定、固定同步義務判斷所需資訊及恢復中的工作狀態。

| ID | Acceptance |
| --- | --- |
| AC-CP-01 | CP shutdown 後，既有 replication、retry、reconciliation 與獨立本地交易繼續 |
| AC-CP-02 | CP 不可用時，既有已初始化 Node 的 Data Plane 重啟後，仍可從本地設定與狀態恢復 |
| AC-CP-03 | 全域資訊中斷時仍可於健康 Node 查詢本地狀態及執行必要受控操作 |

CP outage 期間允許不能發布新設定。新 Node 的首次初始化不屬於上述重啟保證。

## 13. Configuration Flow

設定採 versioned desired state，流程為：

1. 建立設定變更並驗證。
2. 發布不可變的 Version N。
3. Node 取得設定並執行本地相容性及有效性驗證。
4. 持久化候選設定與恢復所需資訊。
5. 啟用完整版本；成功後回報 active version。
6. 啟用失敗時保留／恢復 LKG，留下失敗原因。

每個 Node 至少保存 Active Config 及可恢復的 LKG。設定啟用不得留下混合版本；啟用中 crash 後須恢復至一個完整有效版本。

可更新項目限於不改變資料同步義務的操作參數，例如 retry、rate limits 與 reconciliation schedule。不得透過設定更新靜默消除 backlog。

| ID | Acceptance |
| --- | --- |
| AC-CFG-01 | Invalid／不相容／超出第一版範圍的設定不影響 active config |
| AC-CFG-02 | Activation failure 保留或恢復 LKG |
| AC-CFG-03 | CP unavailable 時既有設定持續工作，重啟亦可恢復 |
| AC-CFG-04 | 可追蹤發布者、發布時間、版本、各 Node 採用／失敗狀態 |
| AC-CFG-05 | 啟用中 crash 後使用完整有效設定，不遺失既有義務 |

## 14. Operational States

Replication state 至少以 File identity × Target 為粒度。

| State | Meaning |
| --- | --- |
| PENDING | 已知義務尚未開始 |
| IN_PROGRESS | 傳輸中 |
| VERIFYING | 驗證、持久化或發布結果確認中 |
| COMPLETED | 此 Target 已達到完整完成條件 |
| RETRY_WAIT | 暫時失敗，等待下一次重試 |
| BLOCKED | 已知外部條件、受控暫停或結果待確認等阻礙，須顯示原因與恢復條件 |
| QUARANTINED | 一般 retry 無法解決，例如持續完整性失敗、metadata 不合法、持續權限問題或內容衝突 |

整份檔案完成代表所有 Required targets 都已完成。BLOCKED、QUARANTINED、pause 及結果待確認均不免除同步義務，須計入 backlog 與未完成時間。

UNKNOWN / PENDING_CONFIRMATION 是 operation outcome；可映射至上述狀態，但必須明確顯示待確認原因。

## 15. Operational Readiness

Ops 必須能回答：

1. 哪些資料及哪些 Source→Target 義務尚未完成？
2. 最舊未完成義務多久，原因是什麼？
3. 哪個 Node、Storage 或同步服務異常？資訊是否過期？
4. Backlog 有多少檔案、多少 bytes？
5. 哪些工作持續失敗、blocked 或 quarantined？
6. 有哪些 integrity mismatch、衝突或無法恢復的資料？
7. 哪個 Node 正在 catch up，drain rate 與剩餘量如何？
8. 各 Node active config 是哪一版？
9. 最近一次完整檢查涵蓋什麼範圍？還有多少無法確認？
10. 剩餘容量是否足夠承受既定停機及恢復情境？

## 16. Required Operational Metrics

| 類別 | 最低要求 |
| --- | --- |
| Availability | node_health、storage_health、replication_service_health、local_write_availability / latency |
| Replication | pending_count、unfinished_obligation_count、success/failure rate、retry_count、replication_lag |
| Aging | oldest_unfinished_age，自 Source Ready 起計，涵蓋 blocked / quarantined / paused |
| Integrity | integrity_failure_count、identity_conflict_count、unrecoverable_count |
| Reconciliation | missing_count、mismatch_count、unknown_count、last_complete_scan_time、coverage |
| Recovery | backlog_obligation_count、backlog_bytes、net_backlog_drain_rate、recovery_duration |
| Capacity | usable_free_bytes、protected_source_bytes、temporary_bytes、capacity_alert_status |
| Config / Freshness | active_config_version、activation_failure_count、last_status_update_time |

Oldest unfinished age 與 Replication Lag 為核心指標。若沿用 oldest_pending_age 名稱，仍須涵蓋所有未完成狀態。兩者的起點與終點時戳一律由 Source Node 時鐘打，不做跨 Node 時鐘比對（見 ADR-0001）。

指標至少可依 Source→Target 區分；正常與 recovery 時段分開呈現。完成 lag 的 P95/P99 須搭配未完成量與 age，避免只統計成功工作而掩蓋卡住資料。

Backlog bytes 按未完成 Target 義務加總，不等同 Source 實際占用容量；兩者須分開呈現。Success/failure rate 必須標示分母、時間窗與是否按 attempt 計算。

## 17. Operational Control

必須提供受控的 status inspection、failed/quarantined task inspection、retry/replay、pause/resume toward a Target、trigger reconciliation 與 active config inspection。

Pause 保留既有義務，新資料仍持續累積待同步工作；resume 後受控恢復。操作需有適當授權，並記錄操作者、時間、範圍、理由與結果。

正常維運不得要求工程師直接修改 backend DB。解除隔離或人工處理未知結果，不得跳過完整性確認直接標記完成。

## 18. SLO / Service Objectives

**正式驗收前須完成本表所有 TBD；不能以 TBD 宣告生產就緒。**

| Objective / Parameter | Requirement |
| --- | --- |
| Supported Target downtime | 24 hours |
| Workload envelope | 每套部署 ≤10 Node；每 Node 每日 ≥10⁵ 檔案；檔案 MB 級；推算單一 Target 24 小時累積 10²–10³ GB。持續／尖峰 files/sec、bytes/sec、file size distribution、最大檔案大小：TBD |
| Local availability & latency | 正常、單一 Target 停機及 recovery 期間的成功率與 P95/P99 latency：TBD |
| Normal replication freshness | Source→Target P95 / P99 replication lag：TBD |
| Accepted obligation durability | 定義故障測試中，已接受且仍有有效資料來源的義務遺失數 = 0 |
| Integrity | 注入的缺漏／損壞於約定檢查範圍及時限內被發現；錯誤完成數 = 0 |
| Recovery | 24 小時 downtime 後，在持續新流量下完成故障期間 backlog 的期限：TBD |
| Reconciliation | Shallow check 週期、Deep check 完整覆蓋週期（= T30 最長偵測時間）、可修復差異恢復期限：TBD |
| Capacity | 保留容量、暫存空間、state 容量、告警及拒絕新寫入門檻：TBD |
| Unknown outcome | 必要服務恢復後的查證期限：TBD |
| Measurement | 時間來源 = Source Node 時鐘（無跨 Node 時鐘誤差參數）；統計窗口與樣本規則：TBD |

永久 Source Storage 毀損後零資料損失不在第一版保證內。Replication freshness 與災難 RPO 不得混用；如未來納入永久毀損保護，另訂 RPO 與保護機制。

Reconciliation、Recovery 時限的起訖點及例外條件必須明列。已知人工處理障礙可獨立分類，但原始 elapsed age 不得停止或被重設以掩蓋等待。

## 19. Required Acceptance Tests

測試須在約定 workload、容量與實際 NFSv3 條件下執行，保留應有資料清單、故障注入時間、狀態轉移、指標及完整性證據。

| ID | Scenario | Acceptance |
| --- | --- | --- |
| T01 | Normal replication | 全部 Required targets 最終取得完整、可讀資料 |
| T02 | Remote Node offline | 健康本地獨立交易符合 SLO |
| T03 | Annual PM | 其他健康 Node 的本地交易與非故障路徑同步符合 SLO |
| T04 | Network partition | 本地交易繼續，義務與恢復資料保留 |
| T05 | Network recovery | 自動 catch up 並重新驗證 |
| T06 | Replication process crash | Restart 後 unfinished work 恢復 |
| T07 | Host restart | 接受的義務與必要狀態不遺失 |
| T08 | Interrupted transfer | Partial file 不正式可見，恢復後完整發布 |
| T09 | Duplicate trigger | 同義務重複執行不造成額外正式資料或 corruption |
| T10 | Integrity mismatch | 不得標記完成；保存證據並處理 |
| T11 | Lost trigger | Reconciliation 發現並補齊，包括完全未建立的 task |
| T12 | Target storage unavailable | 不阻塞其他 Target 與健康本地獨立交易 |
| T13 | NFS endpoint/storage failover | 不誤報成功；未知結果可追蹤、恢復後可查證；無靜默內容破壞 |
| T14 | Control Plane down | 既有 Data Plane 工作及本地查詢繼續 |
| T15 | Invalid config | Active config 不受影響 |
| T16 | Large backlog recovery | Controlled drain，無 recovery storm，正常交易符合 SLO |
| T17 | Persistent bad task | Quarantine 可見並計入未完成量與 age |
| T18 | Source Ready 後、task 建立前 crash | 自動重建全部 Required targets 的義務 |
| T19 | Target publish 後、完成紀錄保存前 crash | 查證後正確收斂，不重複發布或破壞內容 |
| T20 | 同 identity、不同內容 | 偵測衝突，不靜默覆寫 |
| T21 | 24 小時停機與 retention/容量門檻 | 必要資料不被清除；門檻告警；容量耗盡明確拒絕新寫入 |
| T22 | 嘗試變更固定 topology/targets | 設定被拒絕，既有設定與義務保留 |
| T23 | CP down + Data Plane restart | 已初始化 Node 從本地設定與狀態恢復 |
| T24 | 持續新流量 + backlog recovery | 在約定期限完成故障 backlog，正常交易符合 SLO |
| T25 | NFS 長時間無回應／結果不明 | 不誤報成功、不耗盡無關資源，恢復後結果可查證 |
| T26 | Config activation failure / crash | 保留或恢復完整有效版本，不遺失義務 |
| T27 | Reconciliation 期間 Source / Target 不可讀 | 呈現 unknown 與檢查缺口，不假報一致 |
| T28 | Target consumer 提前讀取 | 已知尚未到齊者回報資料未就緒；不出現 partial data |
| T29 | Source Ready 接受流程中 crash／回覆遺失 | 可查證是否已接受；重試不形成重複資料或漏同步 |
| T30 | 已完成 Target 檔案其後遺失或損壞 | 在定義檢查範圍內被發現；有效來源仍在時可修復 |
| T31 | Pause / resume toward one Target | 暫停期間義務保留，其他 Target 正常；resume 後受控追完 |
| T32 | Service upgrade | 有效設定與未完成義務保留，恢復執行 |

## 20. End-to-End Critical Acceptance Scenario

### Given

- A / B / C 為同一部署範圍內的三個 Phase（Node），各自獨立 NAS，健康，初始固定 Policy 已設定。
- Workload、容量、lag、local transaction 及 recovery SLO 已填妥。
- 使用已知 File identities、size、integrity baseline 建立驗收依據。

### When

1. 將 B 完整離線 24 小時。
2. A 持續以約定負載產生並成功接受新檔案，本地 Application 持續交易。
3. 驗證往 C 的同步持續正常，往 B 的義務與有效資料完整保留。
4. 在復原 B 前固定「停機期間已接受資料」的截止點與應有集合。
5. 恢復 B，A 繼續產生新流量。
6. 等待受控 catch-up，並執行涵蓋該截止點的 reconciliation 及完整性驗證。

### Then

- A / C 的健康本地交易全程符合約定 SLO。
- C 的同步符合非故障路徑 SLO，未被 B 拖住。
- B 的停機期間全部義務與有效資料被保留。
- B 自動恢復並在期限內完成該截止點以前的 backlog。
- 復原期間不出現 partial data、錯誤完成或靜默覆寫。
- 應有集合內所有 File identity × Required target 均通過驗證。
- 無未解決的 missing、mismatch、unknown、blocked 或 quarantined 義務；例外不得被排除後宣稱通過。
- 不需人工 re-copy；新流量回到正常 lag SLO。
- Reconciliation 證據涵蓋驗收集合；不能僅憑 backlog counter 歸零宣告通過。

持續有新流量時，不要求任意瞬間所有 queue 都為零；驗收以截止點前的集合完整，以及新流量符合正常 SLO 判定。

測試須可重複通過並保留證據；測試次數與故障注入時點列入正式測試計畫。

## 21. Out of Scope & Design Handoff

### 21.1 第一版功能範圍外

- Ready 檔案的 overwrite、append、rename、delete 跨 Node 同步。
- 多 Node 共同修改同一 File identity 的衝突合併。
- 運行期間新增、移除 Node 或變更同步對象，以及其歷史資料回補策略。
- Source Storage 永久毀損後零資料損失的災難復原保證。
- 跨部署範圍同步（WAN）。
- 依 lot 下一站動態決定 Required targets（routing-based targets）；Policy 維持靜態。
- Source 端刪除動詞（Retire）與 Framework 主導的 Source retention。

### 21.2 留給 System / Software Design

- Component、Java class / package、thread model。
- Queue、transfer protocol、database technology / schema。
- Reconciliation algorithm、integrity algorithm 與 state persistence 實作。
- NFS client、mount、HA、deployment 與 resource isolation 實作。
- UI implementation、library packaging 與 API signatures。

設計者須提交 Requirement→Design→Test 對照，說明如何封閉 Source Ready／task、Target publish／completion record 等故障窗口，並提出容量與 SLO 數值供驗收。

**最終驗收原則：任何一個 Node 在約定範圍內暫時停機，其他健康 Node 仍能完成獨立本地交易；故障解除後，系統自動且可驗證地補齊全部同步義務。**

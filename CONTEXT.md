# Cross-Node File Synchronization

讓多個 Node 之間的檔案非同步、可驗證地同步，且任一 Node 停機不阻塞其他 Node 的本地交易。

## Language

### Topology

**Node**:
可獨立運行的部署單位，擁有自己的 Local Storage 與 state；對應一個 Fab 內的一個 Phase。
_Avoid_: Site, Data Center, Phase（作為技術詞時）

**Fab**:
一個廠區，包含多個 Node（Phase）。Fab 不是同步單位，只是 Node 的分組。
_Avoid_: Site

### Identity

**File identity**:
由 (Source Node, Namespace, Logical key) 組成的唯一識別；Application 在寫入時提供 Logical key，Framework 不鑄造 id。
_Avoid_: path, filename, file id, hash

**Logical key**:
Application 依業務語意給定的鍵（例如 lot/wafer/recipe id + version）；同一 Logical key 在同一 Namespace 內只能對應一份不可變內容。
_Avoid_: key, name

**Namespace**:
每個 Application 一個的 identity 分區，於 Policy 預先登錄；write 時未登錄的 Namespace 一律拒絕。
_Avoid_: app id, tenant, prefix

**Integrity baseline**:
Source Ready 時與 File identity 綁定的 size 與內容 digest；是 identity 的屬性，不是 identity 本身。
_Avoid_: checksum, hash（作為 identity 時）

**Source Node**:
某份 File identity 的唯一權威 Node，即該檔案原始寫入的 Node。任一 Node 都可以是自己產生資料的 Source Node。
_Avoid_: origin, master, primary

**Target Node**:
依 Policy 必須取得某份 File identity 副本的 Node。Target 上的副本保留原始 identity，永不成為新的 Source。
_Avoid_: replica, mirror, secondary

### Policy

**Data class**:
Application 在寫入時宣告的資料分類，須於 Policy 預先登錄，未登錄者 write 時拒絕；Source Ready 時持久化為 File identity 的屬性，是 Policy 的查詢鍵之一。「只在本地、不同步」的資料以 targets 為空的 Data class 明確登錄。
_Avoid_: file type, category, directory

**Policy**:
初始固定的對照表：(Source Node, Data class) → Required targets。第一版運行期間不可變。
_Avoid_: topology, routing table, config（泛指時）

**Required targets**:
依 Policy 推導出某份 File identity 必須抵達的 Target Node 集合；只由 Policy 與 identity 屬性決定，不由 trigger 或 task 紀錄決定。
_Avoid_: destinations, recipients

### File lifecycle (Source side)

**Writing**:
Application 已開始寫入但尚未 finalize 的狀態；內容不可被消費、不可被複製。
_Avoid_: draft, temp, staging

**Finalize**:
Application 明確宣告寫入完成的動作；結果為 SUCCESS（進入 Source Ready）、FAILURE 或 PENDING_CONFIRMATION。
_Avoid_: commit, close, publish（Source 端）

**Source Ready**:
Finalize SUCCESS 後的狀態：內容持久化、Integrity baseline 建立、Required targets 可推導、crash 後可重新發現。Framework 對同步義務的承擔自此開始。
_Avoid_: ready, done, complete

**Discard**:
Application 主動放棄一份 Writing 中的檔案；只允許對 Writing 狀態，Source Ready 後不可 Discard。
_Avoid_: delete, cancel, abort

**Abandoned**:
Writing 狀態超過 TTL 仍未 Finalize 或 Discard，由 Framework 判定為孤兒並可清理的狀態。
_Avoid_: orphan, stale, expired

### File lifecycle (Target side)

**Target Ready**:
某個 Target Node 上，該 File identity 已完成傳輸、Integrity baseline 驗證、持久化與 Publish 的狀態。
_Avoid_: replicated, synced, arrived

**Publish**:
Target 端把已驗證的內容以正式名稱、原子地對 consumer 可見的動作；Publish 之前 partial 內容不得以正式名稱存在。
_Avoid_: release, expose, commit

**Consumer**:
Target Node 上讀取已 Publish 資料的 Application。透過 contract 以完整 File identity（含 Source Node）讀取，可得到 DATA_NOT_READY / NOT_EXPECTED；直接讀路徑只能得到「存在／不存在」，且「不存在」不代表「不會來」。
_Avoid_: reader, client

**DATA_NOT_READY**:
Consumer 透過 contract 查詢時，該 File identity 依 Policy 應抵達本 Node 但尚未 Target Ready 的回應。
_Avoid_: not found, missing

**NOT_EXPECTED**:
Consumer 查詢的 File identity 依 Policy 不會抵達本 Node 的回應；等待無意義。
_Avoid_: not found, not applicable

### Infrastructure

**Local Storage**:
一個 Node 自有的 NAS / NFS Storage，由該 Node 的 Application 主機與 sync service 主機共同 mount；不是任何主機的本機磁碟，也不與其他 Node 共用。
_Avoid_: disk, local disk, shared storage

**Control Plane**:
跨 Node 共用的管理面：持有 Policy、operational policy 的版本與全域可視性；不是任何 Data Plane 工作的即時依賴。
_Avoid_: CP server, central server, master

**Data Plane**:
每個 Node 自有的執行面：同步、驗證、重試、reconciliation 與 state store；CP 不可用時仍可獨立運行與重啟恢復。
_Avoid_: agent, worker（泛指時）

### Replication

**Replication obligation**:
一份 File identity 對一個 Required target 的同步義務；由 Source Node 擁有並持久化，Source Ready 時即成立（不論 task 是否已建立）。
_Avoid_: task, job, transfer（作為義務時）

**Completion evidence**:
Target Node 持有的「已 Publish 且已依 Integrity baseline 驗證」證據；供 Source Node 在紀錄遺失時查證收斂，不是義務的權威紀錄。
_Avoid_: ack, receipt

**Backlog**:
Source Node 上所有尚未 COMPLETED 的 Replication obligation，包含 blocked、quarantined、paused 與恢復中的義務。
_Avoid_: queue, pending list

### Availability

**Local Transaction**:
Application 對本 Node Storage Access contract 的一次 write + Finalize；是 DG-01 / DG-02 availability 與 latency SLO 的對象。Consumer 的 read 受 SR-06 獨立性約束，但不計入此 SLO。
_Avoid_: transaction（泛指業務交易時）, request

### Reconciliation

**Reconciliation**:
對照「應有集合」（Source Ready 集合 × Policy）與實際狀態，找出缺漏、不一致與未建立的義務；每輪對全部義務做 Shallow check，另以滾動方式做 Deep check。
_Avoid_: audit, scan, sync check

**Shallow check**:
不讀內容的比對：Target 上存在性、size、雙方持有的 Integrity baseline 紀錄是否一致。
_Avoid_: metadata check, quick scan

**Deep check**:
重讀 Target 內容重算 digest 與 Integrity baseline 比對；以滾動方式分批覆蓋，一個完整覆蓋週期即 T30 的偵測時限。
_Avoid_: full scan, verify

### Measurement

**Replication Lag**:
Source Ready 時戳到 Source Node 收到該 Target 的 Completion evidence 時戳之間的經過時間；兩個時戳都由 Source Node 時鐘打，不做跨 Node 時鐘比對。
_Avoid_: latency, delay, sync time

**Oldest unfinished age**:
Backlog 中最早 Source Ready 的義務至今的經過時間，涵蓋 blocked / quarantined / paused；不因人工處理而暫停或重設。
_Avoid_: oldest pending age

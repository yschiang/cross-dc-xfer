# PR #5 Review — P02 sync-service skeleton

> 獨立 review（fresh opus，只讀 PR diff、validation doc、設計文件），對 HEAD `3f81329`。依 goal.md「每個 feature 的固定流程」第 4 步。

審查基準：`pr5.diff`（3013 行）、`docs/validation/P02-validation.md`、worktree `.worktrees/p02-sync-service-skeleton` 的設計文件與原始碼。
本機重跑 `mvn test`：`BUILD SUCCESS`，core 120 + sync-service 55 = 175，與驗收紀錄相符。

## 結論

`Request changes` — 整體架構、認證 fail-closed 設計與 config 啟用協定都站得住，但有三處 merge 前該改：V1 migration 的欄位寬度在 Oracle 是 byte 語意且無對應的長度驗證（V1 一旦發布就只能靠 V2 補）、`ConfigStore` 啟用 candidate 時重讀檔案造成可繞過 D17「policy 段須相同」的 TOCTOU、以及 active.json 損毀時的失敗信號在 candidate 啟用路徑被丟棄（違反 D45「active 載入失敗用 lkg 並計失敗」）。三項合計約 15 行。

## 必修（merge 前）

### 1. `V1__schema.sql:7,12,39,41,128` — `VARCHAR(n)` 在 Oracle 是 byte 語意，且程式端沒有對應的長度上限

`logical_key VARCHAR(512)`、`content_path VARCHAR(1024)` 在 H2 是字元數，在 Oracle（`NLS_LENGTH_SEMANTICS=BYTE`，預設值）是 **位元組數**。而 `FileIdentity.requireSegment`（`gigaxfer-core/.../identity/FileIdentity.java:19`）只檢查 null / 空 / `.` 開頭 / 含 `/` 或 `\0`，**完全沒有長度上限**。

失敗情境：Application 用 200 個中文字當 logical key（UTF-8 約 600 bytes）。core 接受、manifest 寫成功、`<key>` 發布成功、NAS 上是合法的 Source Ready 檔。P03 掃描器要寫 `file_identity` 時 Oracle 回 ORA-12899（value too large），insert 失敗 → 義務永遠建不起來 → 該檔永遠不同步，而且每輪掃描重試都同樣失敗。H2 `MODE=Oracle` 測試抓不到（字元語意 + 測試 key 都是短 ASCII），所以這條在上 Oracle 實機前不會被發現。`content_path`（= `<source>/<ns>/<class>/<yyyy-mm-dd>/<HH>/<key>`）同理，而且比 `logical_key` 更容易超。

另外要注意：直接把 DDL 改成 `VARCHAR2(1024 CHAR)` **在標準 Oracle 建不起來**——CHAR 語意下宣告長度會以 `1024 × 4 bytes（AL32UTF8）= 4096 > 4000` 判定，回 ORA-00910。

建議修法（擇一，但兩端要一致）：
- 保留 BYTE 語意，在 `FileIdentity.requireSegment` 加 `v.getBytes(UTF_8).length <= N` 的上限（node 64、namespace 128、logical_key 512），並在 `PathLayout` 產生 `content_path` 時檢查 ≤ 1024 bytes，超過即在 `beginWrite` 就拒絕（跟 P01 偏差 ③ 的保留字尾拒絕同一層）；或
- 改 `VARCHAR2(512 CHAR)` / `content_path VARCHAR2(1000 CHAR)`（1000×4 = 4000，剛好合法），並在測試 profile 加一筆長 key/長路徑的 insert 當回歸。

不論選哪個，請在 `SchemaTest` 補一筆「邊界長度 insert 成功、超界 insert 失敗」的測試，這是目前唯一能在 H2 下守住這條契約的方式。

### 2. `ConfigStore.java:54-67` — candidate 驗證後重讀檔案，可讓未通過 D17 驗證的設定被啟用

```java
String reason = validateCandidate(candidate, baseline.orElseThrow());   // 第一次讀 + 驗證
if (reason == null) {
    accepted = ConfigCodec.decode(Files.readAllBytes(candidate));        // 第二次讀，只驗 schema
    ...
    Files.move(candidate, active, ATOMIC_MOVE, REPLACE_EXISTING);
```

`validateCandidate` 讀一次 candidate 做完整驗證（schema、version 嚴格遞增、policy 段相同），回傳 `null` 後**把驗過的物件丟掉**，第 58 行重新 `readAllBytes` + `decode`。第二次的 `decode` 只跑 `ConfigCodec.validate`（schema / 內部一致性），**不再驗 version 遞增與 policy 段相同**。

失敗情境：CD pipeline 把 v5 rename 成 `candidate.json`，ops 同時 `systemctl restart`。process 在第 54 行讀到 v4（policy 相同，通過驗證），CD 在第 54 與 58 行之間再 rename 進一版 v5（policy 段被誤改）。第 58 行讀到 v5，通過 schema 驗證，接著被 rename 成 `active.json` 並生效——D17 / AC-CFG-01「Policy 段須與 active 完全相同，否則拒絕並上報」被繞過，而且 `activation_failure_count` 是 0，ops 完全看不到。視窗很窄，但 D17 是硬規則，且修法只是刪掉重讀。

修法：`validateCandidate` 改為回傳已解碼的 `NodeConfig`（或 `record Result(NodeConfig ok, String reason)`），成功路徑直接用它。順帶消掉第 59-61 行那個「candidate validated a moment ago」的 `IllegalStateException` 死分支——它存在的唯一理由就是這次重讀。

### 3. `ConfigStore.java:41-45, 67` — active.json 損毀/缺失的失敗信號在 candidate 啟用成功時被丟棄

第 41-45 行算出 `failure = "active.json unreadable, using lkg"`，但第 67 行的成功路徑固定回 `Optional.empty()`，`failure` 在這條路上是死變數。

失敗情境：磁碟問題讓 `active.json` 變成半截檔，同時 CD 已放好合法 candidate。啟動時：以 lkg 當 baseline → candidate 驗證通過 → `Files.move(candidate, active, REPLACE_EXISTING)` 把損毀的 `active.json` **直接覆蓋掉**（證據沒了）→ 回報 `activation_failure_count = 0`。也就是說最該告警的一次啟動（本機設定檔曾經損毀）是完全靜默的，違反 D45「active 載入失敗用 lkg **並計失敗**」。現有測試 `corrupt_active_does_not_overwrite_good_lkg_when_candidate_activates` 只斷言 lkg 沒被覆蓋，沒斷言 `activationFailure`，所以這條沒被擋下。

修法：第 67 行改成 `new ConfigActivation(accepted, Source.ACTIVE, failure)`，並在該測試加 `assertThat(a.activationFailure()).isPresent()`。（若希望保留損毀檔供事後檢查，另外把它 rename 成 `active.json.bad.<ts>` 再覆蓋，但這一步可留 follow-up。）

## 建議（follow-up 可）

### 1. `application.yml:16,19` + README「端點」節 — 未認證的 `/actuator/**` 與 `/policy` 跟 Node 間端點共用同一個 port，README 的部署假設在目前設定下做不到

README 寫「部署假設：`/actuator/**` 不對外開放，只綁內部介面」，但 `server.port: 8080` 是唯一的 port，而 Node 間端點（`/pending`、`/file`、`/report`、`/received`）**必須**讓其他 Node 連得到。結果是任何連得到 sync port 的主機，不帶 token 就能拿到：`/policy` 的完整拓樸（deployment 名、node 清單、namespaces、required_targets）、`/actuator/prometheus` 全部指標、以及 `show-details: always` 之下 `/actuator/health` 裡的 `lastError`（DB 連線失敗時通常含 JDBC URL）與 NFS root 路徑。D40 特意把「shared token 不等於任意讀取」收緊，這裡等於把 Policy 全文免費送出。
另一方面 D27 修要求 Prometheus 從監控基礎設施跨主機抓，所以也不能單純綁 loopback。建議：`management.server.port` 另開一個 port（firewall 只放行 Prometheus 來源），並把 `show-details` 降為 `when-authorized`/`never`，或至少把 README 那句「不對外開放」改成實際成立的敘述。

### 2. `NfsHealthIndicator.java:27` + `HealthMetrics.java:18` — health 探測吃共用的 16 槽 NFS pool，且 timeout 30 s

每次 `/actuator/health/readiness` 與**每次 Prometheus scrape** 都會經 `BoundedNfsExecutor` 做一次真實 stat。hard mount 卡住時 `BoundedNfsExecutor.call` 的 permit 只在 syscall 真正返回時才釋放（`BoundedNfsExecutor.java:44-51`，D51 刻意如此），所以 NAS hang 期間每一次探測都**永久漏掉一個 permit**：15 s 一次 scrape + readiness 輪詢，約 4 分鐘就把 16 槽用光，P03 之後的掃描／傳輸會被 health 探測餓死。另外 30 s 的 `nfs-timeout` 大於 Prometheus 預設 10 s scrape timeout，NAS 卡住時得到的是 `up{node}=0`（依 D27 修 = Trust 軸「Node 死了」告警），而不是設計要的 `storage_health=0`。
建議：health 探測用自己的 1 槽 executor + 短 timeout（例如 2 s），或把探測結果快取 10 s，讓 scrape 與 health 共用同一次探測。

### 3. `ConfigBootstrap.java:40` — `activation_failure_count = 1` 的路徑沒有任何測試

唯一斷言這個 gauge 的是 `PolicyEndpointTest.config_metrics_are_registered_with_node_tag`，值是基準 0。`ConfigStoreTest` 驗的是 `ConfigActivation.activationFailure`，沒有驗到 gauge 的接線。依 P02 偏差 ④，這個 gauge 就是 ops 唯一的啟用失敗信號；lambda 寫反或 bean 沒註冊都不會被測出來。建議加一個獨立 context 的測試：candidate 版本不遞增 → gauge = 1.0。

### 4. `V1__schema.sql:141` — `obligation_history` 缺 D33 修 2 要求的「事故類只寫一次」約束

設計 §3 寫「事故類以 (identity_ref, target_node, kind) 只寫一次（D33 修 2）」，schema 只有 `UNIQUE(event_id)`，而 `event_id` 可為 NULL（對帳 ② 產生的列沒有 event_id），Oracle 與 H2 的 unique index 都允許任意多個 NULL。等於這條去重規則在 DB 層完全沒有保護，只能靠 P04/P05 的應用碼。若要留給應用碼，請在 P02 偏差補記；若要 DB 守住，加 `CREATE UNIQUE INDEX ... ON obligation_history (source_node, namespace, logical_key, target_node, kind)`（僅限事故類 kind 的話 Oracle 需用 function-based index，H2 不支援，這點得先裁定）。

### 5. `SyncProperties.java` / `application.yml:32-33` — `nfs-slots`、`nfs-timeout` 被放在本機部署參數，但 D51 說 pool 大小是 operational policy

D51：「library 與 sync service 各一個固定大小 pool（**operational policy**，預設 library 16、sync 32）」。實作把它放進 `gigaxfer.*` 本機屬性，不在 config 的 `operational` 段，所以它不受版本化、不受 D17 的發布稽核，各 Node 可以不一致。P02 偏差 2 ⑦ 只宣告了「暫時只有一個 16 槽 pool」，沒宣告「改成本機參數」。請補進 P02 偏差，或移進 `operational`。同理 D51 要求的 `nfs_pool_exhausted_count` 指標本 PR 沒有曝出來（README 指標節只有四個）。

### 6. `V1__schema.sql:41` — `received.content_path` 是設計文件沒列、P02 偏差也沒列的新增欄位

P02 偏差 ⑥ 明列了 `received.data_class` 是實作新增，但 `received.content_path`（Task 4 補充時加的）沒進偏差表。這個專案把 design-decisions 當偏差帳本，漏記就等於下一個人對不上。補一句即可。

### 7. `ConfigStore.java:100-108` — 只有「解碼失敗」會退回 lkg，I/O 失敗會讓 process 死掉

`read()` 的 `Files.readAllBytes` 若丟 `IOException`（磁碟錯誤、權限、fd 耗盡），會直接往外拋出 `load()` → `ConfigBootstrap` → context 啟動失敗 → process 退出，即使 `lkg.json` 完好。D17 的「缺則 lkg」語意在這條路上不成立，而 `Restart=always` 會讓它進入無用的重啟迴圈。建議 `read()` 一併 catch `IOException`（記 error log 後回 `Optional.empty()`），跟現在處理 `InvalidConfigException` 的方式一致。

### 8. `DbBootstrap.java:61` — 重試迴圈只 catch `RuntimeException`

`state.runOnce()` 若丟出 `Error`（`NoClassDefFoundError`、驅動的 `ExceptionInInitializerError`、OOM），背景執行緒直接死掉，之後**不再重試**，`DbState.lastError` 停在上一次的值或 null，health 永遠回「migration not complete」且沒有任何原因。D34 修 的「留在 process 內重試」在這條路上失效。建議 catch `Throwable`（或至少把未預期的 Throwable 記 lastError 後繼續迴圈）。

### 9. 品質雜項

- `ConfigStore.java:46` 的 `source` 變數只在 `current.isPresent()` 的分支被用到，而該分支恆為 `ACTIVE`；另外兩條路徑都直接寫死 enum。可以直接刪掉。
- `ConfigBootstrap.java:36-47` 的空標記類別 `ConfigMetrics` 只是為了讓 `@Bean` 有回傳值；同一份程式碼裡的 `HealthMetrics` 是在 `@Component` 建構子註冊 gauge，兩種寫法並存。統一成 `HealthMetrics` 的寫法可以少一個類別。
- `HealthGaugeTest` 為了「確保沒有其他測試先打過 /actuator/health」而獨立一個 Spring context，但 gauge 改成 supplier-backed 之後這個前提已不成立，斷言內容與 `HealthEndpointTest` 重複，代價是多一次 context 啟動。建議併回去。
- `SchemaTest.bootstrap_is_idempotent_across_restarts` 名字說 restart，實際只在同一個 process、同一個 in-memory DB 呼叫 `db.runOnce()`。行為是對的（runOnce 就是重啟會做的事），但名字會誤導下一個讀的人。
- `config/v3.json` 在 `gigaxfer-core/src/test/resources` 與 `gigaxfer-sync-service/src/test/resources` 逐字重複。`ConfigCodecTest` / `ConfigStoreTest` 大量用 `s.replace("字串常數", …)` 去 mutate fixture，兩份一旦漂移，其中一邊的 mutate 會靜默變成 no-op（`ConfigCodecTest.mutate` 有 `assertThat(s).contains(find)` 保護，`ConfigStoreTest.withVersion` 沒有）。建議 core 出 test-jar 或把 fixture 收到單一來源。
- `application-test.yml` 用 `DATABASE_TO_UPPER=false`，這跟 Oracle 的識別字自動轉大寫**方向相反**，等於讓 H2 離 Oracle 更遠一點。不是 bug，但「H2 MODE=Oracle 已驗證」的說服力比看起來低，驗收紀錄的「Oracle 實機未驗證」那條要繼續留著。
- 所有 `TIMESTAMP` 欄位無時區，`SchemaTest` 用 `CURRENT_TIMESTAMP`。Oracle 下 `CURRENT_TIMESTAMP` 是 session 時區、寫進 `TIMESTAMP` 會丟掉時區資訊。`source_ready_at` 是跨 Node 比較的業務時間（D56 ②），目前靠「Fab 內所有 Node 同一時區」（P01 偏差 ②）撐著。建議在 schema 或 README 明寫「一律 UTC 寫入」，不然這是個很難查的跨 Node 時間偏差。

## 設計文件本身的問題

### 1. D17 的尾句「熱載入 operational policy」與 D45 / §4.2 直接矛盾

`design-decisions.md:29`（D17）：「…→ rename active→lkg、candidate→active → **熱載入 operational policy**」。
`design-decisions.md:70`（D45）：「設定不熱載入：設定物件啟動時讀一次、process 內不可變」。
`system-design.md` §4.2：「設定物件 process 內不可變，不熱載入」。
程式碼跟的是 D45（正確）。D17 那一句從沒被改掉，而 D17 又是 schema / 啟用協定最常被引用的那一列。建議在 D17 的敘述上標註「該句由 D45 取代」，否則下一個實作者讀 D17 會做出熱載入。

### 2. `system-design.md` §3 `obligation` 那一列的索引引用了同一列自己沒有的欄位

欄位清單是 `identity_ref, target_node, state, epoch, attempts, next_attempt_at, last_error, completed_at, completed_seq`，索引卻寫「**(state, source_ready_at) 供 age**」。`source_ready_at` 在 `file_identity`（與 `received`），不在 `obligation`。所以設計文件自己就不自洽：要嘛 obligation 該有一份 `source_ready_at` 冗餘欄位（為了 age 查詢不 join），要嘛這個索引應該寫成「join file_identity 取 age」。實作選了後者（沒建這個索引），驗收紀錄的 Parked 也誠實記下了，但這是文件要裁定的事，不是實作偏差。`oldest_unfinished_age`（§16 / D13）是告警的主要指標，全表 join 算 age 的成本要在裁定時一併看。

### 3. `system-design.md` §3 的欄位清單本身不完整，`received` 少了兩個必要欄位

`received` 的清單是 `identity_ref, size, digest, source_ready_at, published_at, valid, …`，但 Target 必須記下 Source 選定的 `content_path`（D2 修：路徑第一段是 source node，Target 不能自己推）與 `data_class`（`/locate` 需要）。實作兩個都加了，而且是對的；是文件的清單漏了。建議補進 §3，不要只記在 P02 偏差的「實作新增」裡——那會讓人以為是實作自作主張。

### 4. D34 的「health check 打 Actuator `/health`」已被 D34 修 推翻但未標註

`design-decisions.md:49`（D34）：「重啟由 systemd `Restart=always`、`RestartSec=5` 負責，**health check 打 Actuator `/health`**」；`design-decisions.md:58`（D34 修）：「systemd 只看 process 存活，**不打 health**」。同 #1，屬於 ledger 的「修」列取代原列，但 D34 這句會讓人在 systemd unit 裡加 health check，正好是 D34 修 要避免的無效重啟迴圈。README 已經把正確行為寫清楚了，文件也該對齊。

## 驗收紀錄核對

| AC | 結果 | 說明 |
| --- | --- | --- |
| P02-01 獨立啟動 | ✅ supported | `StartupRefusalTest` 兩個案例確實讓 context 啟動失敗並斷言訊息；不掃描是 P03 範圍，紀錄有說明。注意該測試刻意不啟用 `test` profile 的理由（`properties()` 只是 defaultProperties）寫在註解裡，是對的。 |
| P02-02 Policy 登錄契約 | ✅ supported | `/policy` 確實輸出 `deployment` + 排序後的 `namespaces`；`allowsWrite` / `isNamespaceRegistered` 有單元測試。附註：這兩個方法目前**只有測試在呼叫**，production（sync-service）沒有使用點，真正的消費者是 P03 之後的 library。 |
| P02-03 設定驗證 | ✅ supported | `ConfigCodecTest` 實際就是 20 個測試（本機計數相符），schema / version / policy 相等 / fixed 段 / token 雜湊唯一皆有覆蓋。`FixedConstants.V1 = (7,24,24,6,30,19,1)` 與 system-design §8「v1 固定常數」逐項相符。 |
| P02-04 安全啟用 | ✅ supported | 但 `config_is_immutable_while_process_runs` 在實作上是恆真的（`PolicyController` 在建構子就把 body 轉成 byte[]，程式裡沒有任何地方會再讀 `active.json`）。它證明了 AC 的可觀察行為，只是不具回歸防護力。 |
| P02-05 失敗與回退 | ⚠️ partially | 拒絕留 candidate、lkg 回退、壞 active 不覆蓋好 lkg 都確有斷言。但 (a) `activation_failure_count = 1` 這條路徑沒有任何測試（紀錄引用的 `config_metrics_are_registered_with_node_tag` 只驗基準值 0，紀錄自己也註明了）；(b) 見必修 #3——壞 active + 合法 candidate 這條路實際回報的是 `activationFailure` **空**，與 D45「並計失敗」不符，紀錄把它列為通過。 |
| P02-06 中斷恢復 | ✅ supported | `crash_between_renames_recovers_on_next_start`、`ignores_candidate_tmp_still_being_written_by_cd` 都如描述斷言。 |
| P02-07 schema 與冪等 bootstrap | ✅ supported | 五個測試名稱與斷言相符。兩點保留（紀錄已自陳）：沒有斷言索引建立；`bootstrap_is_idempotent_across_restarts` 是同 process 內再呼叫 `runOnce()`，不是真的 restart。 |
| P02-08 DB 故障恢復 | ✅ supported | `DbOutageRecoveryTest` 確實驗了「啟動時 DB 不可用 → process 活著、`/policy` 200、readiness 503 且 db DOWN → 恢復後不重啟即 ready」。涵蓋的是**啟動期**斷線；ready 之後才斷線的路徑（`DbState.ready()` 的 latch 不會反轉，靠 `DbHealthIndicator` 的 `SELECT 1 FROM DUAL` 即時探測）沒有測試，但行為是對的。 |
| P02-09 health 語意 | ✅ supported | 四個測試如描述；`NfsTimeoutHealthTest` 確實斷言 `details.op == stat-root`。`HealthGaugeTest` 的價值見建議 #9。 |
| P02-10 Node 認證 | ✅ supported | 行為全部有覆蓋（401 無/未知 token、非 Bearer、bearer 大小寫、`WWW-Authenticate`、403 不洩漏節點名、`/file/**` 子路徑、`;` 參數與 `%70` 編碼繞過、dot-segment fail-closed）。小出入：紀錄寫「`NodeAuthFilterTest`（10 個測試）」，實際是 12 個（多了 `lowercase_bearer_scheme_is_accepted` 與 `unauthorized_response_carries_www_authenticate_and_forbidden_hides_node_names`）。 |
| P02-11 角色身分 | ⚠️ partially | 三個引用的測試都成立，但它們打的是**測試專用**的 `EchoCallerController`（`src/test/.../auth/EchoCallerController.java`）。production 端目前只有 filter 把 caller 放進 request attribute，`CallerIdentity.requireTarget` 沒有任何 production 呼叫點；`/received` 只回「caller 為 Source 的列」也明確留給 P04。也就是說 P02-11 驗到的是「契約工具可用」，不是「端點遵守契約」。紀錄備註有提到 P04，但「通過」這個結論偏樂觀。 |
| P02-12 可交接可重現 | ✅ supported | README 涵蓋首次初始化、更新/回退、DB 斷線觀察、curl 範例、指標範例、HTTPS 要求；`mvn test` 175/175 本機重現成功。唯一與事實不符的是 README「`/actuator/**` 不對外開放，只綁內部介面」這句（見建議 #1），在目前單一 port 的設定下做不到。 |

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


---

## Scoped re-review（sonnet，對 fix commit `a9c309d`）

# PR #5 Re-review — 必修三項複查

- 必修 1（byte caps）：✅ addressed — `FileIdentity` 新增 `MAX_NODE_BYTES=64`／`MAX_NAMESPACE_BYTES=128`／`MAX_DATA_CLASS_BYTES=128`／`MAX_LOGICAL_KEY_BYTES=512`，與 `V1__schema.sql` 的 `VARCHAR(64)`/`VARCHAR(128)`/`VARCHAR(128)`/`VARCHAR(512)` 逐一核對相符。四個入口都已接上：`beginWrite`（`LocalStore.java:73-74`，經 `FileIdentity` 建構子驗 sourceNode/namespace/logicalKey，另外呼叫 `requireSegment` 驗 dataClass）、config（`ConfigCodec.java` 的 `policy.nodes`/`policy.namespaces`/`required_targets.source_node`/`required_targets.data_class` 皆帶對應 maxBytes）、manifest（`ManifestCodec.java:75` 驗 data_class）。`content_path` 未另加程式檢查，但採用「由片段上限推得 849 < 1024」的方案，計算正確（64+128+128+10+2+512+5=849）。邊界測試確實驗了 byte vs char 語意：`FileIdentityTest.segment_limits_are_utf8_bytes_matching_schema_widths` 用 512 個 ASCII 字元（通過）、513 個（拒絕）、171 個中文字（513 bytes、171 字元，仍拒絕），另加 `SchemaTest.identity_column_widths_match_core_segment_limits` 在 DB 層插入 512-byte key 成功、513-byte 失敗（`DataIntegrityViolationException`）。全庫 grep `requireSegment(` 確認所有呼叫端（含測試碼）都已改用三參數版本，無殘留的舊雙參數呼叫。

- 必修 2（candidate 啟用不重讀）：✅ addressed — `ConfigStore.load()` 現在直接用 `validateCandidate(...)` 回傳的已解碼 `NodeConfig accepted`，第二次 `Files.readAllBytes(candidate)` 已刪除；`validateCandidate` 改為驗證通過即回傳解碼物件、失敗則丟 `InvalidConfigException`。舊的「candidate validated a moment ago」死分支 `IllegalStateException` 已整段移除。catch 區塊只 `catch (InvalidConfigException e)`（`ConfigStore.java:63`），是 `IOException` 的子型別但用型別窄化的 catch子句，故 `validateCandidate` 內部 `Files.readAllBytes` 若拋出**非** `InvalidConfigException` 的一般 `IOException`（如磁碟錯誤），不會被這個 catch 攔到，會直接往外傳出 `load()`，不會被誤判成「candidate 被拒絕」；`Files.move` 拋出的 `IOException` 同理不受影響。

- 必修 3（成功路徑保留 failure）：✅ addressed — 第 62 行成功路徑改為 `return new ConfigActivation(accepted, ConfigActivation.Source.ACTIVE, failure)`，不再固定回 `Optional.empty()`；`failure` 由第 39-45 行依 active.json 缺/損毀狀態設定，並沿用到 candidate 啟用成功的分支。測試 `corrupt_active_does_not_overwrite_good_lkg_when_candidate_activates` 已新增 `assertThat(a.activationFailure()).hasValueSatisfying(r -> assertThat(r).contains("active.json unreadable"))` 斷言。

## 新問題

（無）

- 正常路徑迴歸檢查：active 存在且合法、candidate 也合法時，`failure` 從初始化就是 `Optional.empty()`（因為 `current.isPresent()` 為真，不會進入第 41-45 行的賦值），成功路徑回傳的 `failure` 仍是空值，行為與修改前相同；對應測試 `assertThat(a.activationFailure()).isEmpty()` 仍然成立。原本啟用成功分支恆為 `ACTIVE` 的死變數 `source` 已被直接刪除、下方 `current.isPresent()` 分支改為寫死 `ConfigActivation.Source.ACTIVE`，與刪除前的實際行為一致（此變數原本在該分支下必為 ACTIVE），不構成行為變更。`InvalidConfigException` 建構子支援 `(String, Throwable)`，`validateCandidate` 包裝原始例外的寫法可正常編譯。

CLEAN


---

## 第 2 輪獨立 review（fresh opus，對 #6 `fe582d9`、#5 `442c418` 與合併樹；同一份涵蓋兩個 PR）

# Review：PR #6（`p01-lifecycle-fix`）＋ PR #5（`p02-sync-service-skeleton`）

審查基準：合併試跑樹 `.worktrees/review-merged` HEAD `4da0e7b`（main + #6 + #5）。本機 `mvn -q test`（JDK 27 → release 21）：**core 125、sync-service 56，共 181，0 failures／errors**。PR #6 head 單獨跑 core：103。GitHub CI（`gh pr view`，唯讀）：PR #3 head `ad57714`、PR #5 head `442c418`、PR #6 head `fe582d9` 的 `test` check 皆 SUCCESS。

重現用的暫時測試放在 `scratchpad/review2-tmp/`（`ReviewDiscardReproTest.java`、`ReviewConfigStoreReproTest.java`、`ReviewFlywayReproTest.java`），跑完已從 worktree 移除，`git status --short` 為空。

## 結論

- **PR #6：Request changes**：Failure 終態重放、link future 回收都正確，也有測試保護。但「刪除成功才進 DISCARDED」的改法漏掉一般 `IOException`：這時 handle 留在 WRITING，之後呼叫 `finalizeWrite()` 會把 Application 已決定放棄的內容發布出去。main 在同一情境回 `Failure`，所以這是本 PR 造成的退化（已重現）。修法只要一兩行。
- **PR #5：Request changes**：`ConfigStore` 有兩個已重現的缺陷，直接違反 D17／D45 與 P02-05／P02-06。(a) 讀不到的 active／candidate 會讓 process 拒絕啟動，不會退回 LKG，也不會把 candidate 當成被拒；(b) CD 在驗證與 rename 之間換掉 candidate 時，未驗證、甚至改了 Policy 的版本會成為 `active.json`，下次啟動直接採用，而且不留任何失敗信號。其餘問題可以放 follow-up。
- **先 #6 後 #5 合併是安全的**：唯一的文字衝突（`LocalStore.beginWrite` 兩邊都保留）解得正確。sync-service 目前還沒使用 `LocalStore`／`WriteHandle`，兩個 PR 在執行期沒有交互，合併樹 181 個測試全綠。只有兩份驗收文件的測試數會在合併後失效（見驗收核對）。

## 必修（merge 前）

### 1. [#6] `discard()` 的刪除丟一般 `IOException` 時，handle 仍可發布（已重現）

- **位置**：`gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java:113-137`。
- **問題**：舊碼在刪除前就把 lifecycle 設成 `DISCARDED`，任何失敗之後 `finalizeWrite()` 都回 `Failure`。新碼改成刪除成功才設 `DISCARDED`，失敗分支卻只 `catch (NfsException)`。`channel.close()` 或 `Files.deleteIfExists()` 丟出 `AccessDeniedException`、EIO、ESTALE 這類一般 `IOException`（或 RuntimeException）時，會直接穿出 `discard()`，lifecycle 停在 **WRITING**，`failure` 仍是 null。
- **失敗情境**：Application 寫了 ≥ 64 KiB（單次 write 繞過 buffer，buffer 為空），然後呼叫 `discard()`。NAS 回 EACCES／EIO，`discard()` 丟出例外。之後同一 handle 再呼叫 `finalizeWrite()`（例如錯誤處理流程或重試迴圈又把 handle 推回 finalize）：flush 是 no-op，fsync 會以重開暫存檔的方式成功，最後 link 發布並回 `Success`。Application 已經放棄的內容就此成為 Source Ready，並產生同步義務。
- **重現**：`ReviewDiscardReproTest.discard_eacces_then_finalize_publishes_discarded_content`（把暫存所在目錄暫時 chmod 成 `r-xr-xr-x`，讓刪除失敗）。合併樹得到 `Success[... contentPath=P3/mes/metrology/2026-09-22/08/X1]`；同一測試在 `origin/main` 得到 `Failure[reason=IO, detail=handle discarded]`。
- **建議修法**：失敗分支改成涵蓋所有例外，任何「刪除沒有確定完成」都讓 handle 中毒：
  ```java
  } catch (Exception e) {
      synchronized (stream) {
          if (failure == null) failure = new FinalizeResult.Failure(FailureReason.IO,
              failedOp != null ? "stream failed at " + failedOp : "discard delete unresolved at discard-writing: " + e);
          lifecycle = Lifecycle.FAILED;
      }
      if (e instanceof NfsException ne) throw new NfsUnavailableException(ne.op(), ne);
      if (e instanceof IOException io) throw io;
      throw (RuntimeException) e;
  }
  ```
  同時在 `WriteHandleLifecycleTest` 補一個案例：`nfs.failBefore("discard-writing")` 之後 `discard()` 丟 `IOException`，接著 `finalizeWrite()` 必須是 `Failure`，正式路徑不存在。這個案例在目前程式碼下會失敗。README 規則 5 也要把「池滿／timeout」改寫成「任何刪除失敗」。

### 2. [#5] `ConfigStore` 遇到 `IOException` 就拒絕啟動，不回退 LKG，也不把 candidate 當成被拒（已重現）

- **位置**：`gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/config/ConfigStore.java:95-105`（`read()` 只 catch `InvalidConfigException`）、`:79-83`（`validateCandidate` 的 `readAllBytes`）、`:52-67`（兩次 `Files.move` 與只 catch `InvalidConfigException` 的 catch）。
- **問題**：`Files.readAllBytes` 丟出的 `AccessDeniedException` 等 `IOException` 不是 `InvalidConfigException`，會一路穿出 `load()`，`ConfigBootstrap` 的 bean 建立失敗，context 起不來。這違反 D45／§4.2：「active 載入失敗用 lkg」、「驗證失敗留 candidate、用 active」，也違反 P02-05。
- **失敗情境**：
  1. CD 以 root 身分、umask 077 寫出 `candidate.json.tmp` 再 rename，得到 0600 root 擁有的 `candidate.json`，service 使用者讀不到。這次 push 本該只是一次被拒的 candidate，結果每次啟動都丟 `AccessDeniedException`，搭配 `Restart=always` 進入重啟迴圈。這個 Node 的 sync service 整個停擺，正是 D17 要防的「壞設定取代好設定」。
  2. `active.json` 權限或擁有者錯誤（有效的 `lkg.json` 還在）時，也是拒絕啟動，不會退回 LKG。
  3. 第一次 `move(active→lkg)` 成功、第二次 `move(candidate→active)` 丟 `IOException`（例如權限不足）時，下次啟動會重跑同一條失敗路徑，永遠停在拒絕啟動，儘管 lkg 有效。
- **重現**：`ReviewConfigStoreReproTest.unreadable_active_with_valid_lkg_refuses_to_start`、`unreadable_candidate_with_valid_active_refuses_to_start`，`load()` 皆丟 `AccessDeniedException`。
- **建議修法**：`read()` 對 `IOException` 一律回 `Optional.empty()` 並附上原因（"active.json unreadable: …"）。candidate 的讀取、驗證與兩次 move 包在同一個 `catch (IOException e)` 裡，視為 candidate 被拒：記下原因，回傳當下仍存在的 active；若 active 已被移成 lkg，就回 lkg（`Source.LKG`）。補上面兩個 chmod 測試，外加一個 move 失敗的測試。

### 3. [#5] CD 在驗證與 rename 之間換掉 candidate，未驗證的版本會成為 active（已重現）

- **位置**：`ConfigStore.java:52-62`；對應宣稱：`docs/design/design-decisions.md:131`「P02 偏差 3 ⑩」，以及 `ConfigStore.java:54` 的註解「CD 在中間換掉 candidate 也繞不過 D17」。
- **問題**：這次修正讓 process 使用驗證時解碼出的那一份 `accepted`，但 `Files.move(candidate, active)` 搬的是**當下**磁碟上的 `candidate.json`。CD 依 §4.2 是以 `candidate.json.tmp → rename` 發布，這個 rename 可以落在「讀完 candidate」到「move」之間，這時被搬成 `active.json` 的是沒有驗證過的檔案。下一次啟動時，`read(active)` 只做 schema 解碼，不檢查版本遞增，也不比對 Policy 是否與前版相同，所以改了 Policy 的版本會直接生效，`activationFailure` 也是空的。
- **失敗情境**：連續兩次發布（例如 v4 之後立刻推一個誤改 required_targets 的 v6）。第一次重啟時，process 以 v4 驗證並在記憶體中執行 v4，但 `active.json` 在磁碟上已經是 v6。第二次重啟直接跑 v6，v1 Policy 不可變（D17、AC-CFG-01、P02-03／P02-06）被繞過；`/policy` 與 `active_config_version` 也在兩次重啟之間和磁碟不一致。
- **重現**：`ReviewConfigStoreReproTest.candidate_swapped_during_activation_lands_in_active_unvalidated`（另一執行緒以 0–300 µs 延遲做 `candidate.json.tmp → candidate.json` 的 ATOMIC_MOVE）。第 14 次迭代命中：`process runs v4 but active.json on disk is v6`，下次啟動 `runs v6, policy equal to v3? false, failure=Optional.empty`。
- **建議修法**：不要搬 candidate 檔本身。把驗證時讀到的 bytes 寫成 `active.json.tmp` 並 fsync，然後 `move(active→lkg)`，再 `move(active.json.tmp→active)`。最後只有在 `candidate.json` 目前的 bytes 仍等於已驗證的 bytes 時才刪除它；否則保留，讓下次啟動重新驗證那個較新的 candidate。中斷在任兩步之間時，下次啟動都會重新驗證 candidate，仍能收斂。同步更正 P02 偏差 3 ⑩ 的敘述與程式註解。

## 建議（follow-up 可）

### 1. [#5] health／gauge 在 DB 或 NFS 卡住時同步阻塞，scrape 逾時，指標在它要監控的故障中失效
- **位置**：`gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/health/HealthMetrics.java:15-19`、`DbHealthIndicator.java:29`、`NfsHealthIndicator.java:27-28`；`application.yml:8-11` 沒有設定 `hikari.connection-timeout`（預設 30 s）；`gigaxfer.nfs-timeout: 30s`。
- **情境**：bootstrap 完成後 DB 斷線時，`jdbc.queryForObject` 會在 Hikari `getConnection` 上等滿 30 s，`setQueryTimeout(5)` 管不到取連線這一段。每次 `/actuator/prometheus` scrape 都會呼叫 `db.health()`，所以 scrape ≥ 30 s，超過 Prometheus 預設的 10 s scrape timeout，結果是 `up{node}=0`（monitoring.md 的事故級 page），而不是 README:66 所說的「`db_health` gauge 為 0」，該 Node 所有指標也一起消失。NFS 以 hard mount 卡住時也一樣：每次 scrape 或 readiness 探測都會占一個槽 30 s，而且逾時後不釋放；16 次之後整個共用 pool（D51 修的「本地工作」pool，P03 掃描、發布、自查都會用）全被 `stat-root` 占滿。這也和 monitoring.md 第二層「皆帶最後更新時間，過期顯示 unknown」的設計不一致。另外 `DbState.ready()` 是一次性 latch（`DbState.java:8` 說「後續排程都看這個旗標」），DB 之後斷線它仍是 true。
- **修法**：探測改成排程執行、single-flight：前一次探測還沒結束就不再送出新的，直接回報 DOWN／unknown。結果與時間戳快取起來，gauge 與 health 只讀快取。DB 探測另外設定短的 connection timeout。P03 在 `DbState.ready()` 上加排程之前，要先分清楚「bootstrap 已完成」與「DB 目前可用」。

### 2. [#5] V1 migration 進行到一半時 DB 斷線，之後永遠不會就緒（已在 H2 重現）
- **位置**：`gigaxfer-sync-service/src/main/java/com/gigaxfer/sync/db/DbBootstrap.java:29-44, 55-71`。
- **情境**：Oracle 的 DDL 會隱式 commit，無法回滾。首次 migration 在 `CREATE TABLE inspection` 時斷線，前面已建的表會留下，Flyway 記下 V1 failed，或因連線已斷而沒記下、重跑時撞上「already exists」。DB 恢復後，每 5 s 的重試都是 `Validate failed: Migrations have failed validation`，readiness 永遠 DOWN，liveness 仍是 UP，也就不會重啟。P02-08 宣稱「DB 恢復後自動轉為就緒」，對這個窗口不成立。
- **重現**：`ReviewFlywayReproTest.outage_in_the_middle_of_v1_never_recovers`，以 JDBC proxy 讓 `CREATE TABLE inspection` 失敗一次，之後三次重試都失敗。
- **修法**：窗口很小（每個 Node 一生只有一次），不必硬做自動修復。README 與 P02-validation 要寫明人工恢復程序（刪除半套物件、`flyway repair`），health detail 對「failed migration」給出和「DB 連不上」不同的 reason，讓 ops 看得出需要人工介入。另外 `catch (RuntimeException)`（:61）遇到 `Error`（如 driver 的 `ExceptionInInitializerError`）時重試執行緒會無聲結束，建議改成 `catch (Throwable)`，記錄後繼續重試或讓 process 退出。

### 3. [#5] Node 內端點與 Node 間端點共用同一個 listener，README 的部署假設無法照做
- **位置**：`application.yml`（只有 `server.port: 8080`，沒有 `management.server.port`／`address`）、`NodeAuthFilter.java:29`（`/policy`、`/locate`、`/actuator` 免認證）、`gigaxfer-sync-service/README.md:90`。
- **情境**：Node 間的 `/pending`、`/file` 必須讓其他 DC 連得到 8080，而同一個 port 上 `/actuator/health`（`show-details: always`，含 DB `lastError` 與 NFS 路徑）、`/actuator/prometheus`、`/policy` 都免認證。P10 將來的 `/locate`（回傳正式路徑）也會跟著落在這裡。README「`/actuator/**` 只綁內部介面」在單一 port 下做不到。
- **修法**：把 actuator 放到 `management.server.port` 並綁 `management.server.address=127.0.0.1` 或內網介面；`/policy`、`/locate` 也應有對應的邊界（另一個 connector，或文件寫明由反向代理做路徑 ACL）。P02-12 要求「說明存取邊界」，說明必須是做得到的做法。

### 4. [#5] `TIMESTAMP` 沒有時區語意，第一筆資料寫入前需要定案
- **位置**：`gigaxfer-sync-service/src/main/resources/db/migration/V1__schema.sql`（`source_ready_at`、`next_attempt_at`、`completed_at`、`at` 等全部是 `TIMESTAMP`）。
- **情境**：JDBC 以 JVM 預設時區把 `Instant` 轉成牆上時間存入。若 JVM 所在時區有夏令時間，回撥那一小時的兩個不同 instant 會存成同一個值；若 JVM 與 DB session 時區不同，或主機改過時區，SQL 端以 `SYSTIMESTAMP` 比對（例如 D56 ② 的 purge「早於窗 + 30 天」、`next_attempt_at <= now`）時會差好幾個小時。P02 還沒寫入任何時間，現在改成本最低。
- **修法**：擇一並寫進設計文件：統一 UTC（`-Duser.timezone=UTC` 加上 ojdbc session TZ），或改用 `TIMESTAMP WITH TIME ZONE`。

### 5. [#5] Oracle 位元組語意的前提沒有寫明；有兩個識別字超過 30 bytes
- **位置**：`gigaxfer-core/src/main/java/com/gigaxfer/core/identity/FileIdentity.java:13-16`、`V1__schema.sql:33-34`。
- **情境**：UTF-8 位元組上限只在 DB 字元集為 AL32UTF8 時才與欄寬相符。若 DB 是舊的 `UTF8`（CESU-8），補充平面字元（如 CJK 擴充 B、部分台灣人名用字）要 6 bytes，512-byte 的 key 可能寫不進去。若是非 Unicode 字元集（台灣廠常見的 ZHT16MSWIN950），無法表示的字元會被替換成 `?`，不同 logical key 可能撞成同一個 PK，造成 identity 混淆。另外 `ix_obligation_target_state_next`（31）與 `ix_obligation_target_completed_seq`（34）在 Oracle < 12.2 或 `COMPATIBLE < 12.2` 時會觸發 ORA-00972。H2 測試（`SchemaTest.identity_column_widths_match_core_segment_limits`）只用 ASCII，驗不到上述任何一項。
- **修法**：在 README 或設計文件寫明「Oracle ≥ 12.2（COMPATIBLE ≥ 12.2）且 `NLS_CHARACTERSET = AL32UTF8`」，或把索引名縮到 30 以內。

### 6. [#5] 測試品質
- `application-test.yml:3` 所有 `SyncTestSupport` 子類別共用 `jdbc:h2:mem:gigaxfer;DB_CLOSE_DELAY=-1`，這個 DB 在同一個 surefire JVM 內跨 context 存活。`SchemaTest.migration_runs_in_background_and_creates_all_tables` 看到的可能是別的測試類別先 migrate 好的 schema，P02-07「空 DB 可建成」並沒有被那個測試本身保證。建議改用每個 context 唯一的 DB 名（例如 `${random.uuid}`）。
- `NfsTimeoutHealthTest.java:29-39` 以 stub 直接丟 `NfsTimeoutException`，驗不到 P02-09「逾時不釋放仍在執行的底層操作」。應改用真的 `BoundedNfsExecutor` 加上卡住的 root（或以 latch 卡住的 body），並斷言 `inUse()` 仍被占用。
- bootstrap 完成後 DB 斷線 → DOWN → 復原 → UP（`SELECT 1 FROM DUAL` 路徑）沒有測試；`activation_failure_count = 1` 的接線沒有測試（validation 文件自己也承認）；`/file`、`/report` 的 403 沒有測試（只有 `/pending`）。
- `SchemaTest` 沒有斷言索引與大部分約束（validation 文件 Parked 已列）。

### 7. [#5] README 的認證檢查範例在實際 jar 上無法重現
- **位置**：`gigaxfer-sync-service/README.md:96-105`。
- 正式 jar 沒有 `/pending` handler（只有測試用的 `EchoCallerController`）。帶有效 token 會得到 **404**，不是 README 寫的 200；`?target=P3` 也是 404，不是 403。只有「無 token → 401」這一條能重現。應改寫範例，或明寫 200／403 只能在測試（`NodeAuthFilterTest`）中觀察到。

### 8. [#5] core README 規則 8 沒有寫新的位元組上限；實際可用的 key 長度受 NAME_MAX 限制
- **位置**：`gigaxfer-core/README.md:32`；`PathLayout.java:59, 63`。
- #5 新增 `MAX_*_BYTES` 並在 `beginWrite` 丟 `IllegalArgumentException`，但 library 契約的規則 8 沒有更新。另外 NAS 常見的 NAME_MAX 是 255：`<key>.<uuid>.writing` 讓 key 最多 210 bytes，`<key>.manifest.<uuid>.tmp` 讓 key 最多 205 bytes。206–210 bytes 的 key 能通過 `beginWrite`，卻在 `write-manifest-tmp` 必定得到 `ENAMETOOLONG` → `Failure(IO)`，而且 #6 之後這是終態，換新 handle 用同一個 key 也一樣失敗。這不會造成資料錯誤，但應在規則 8 寫明實際上限（或把 `MAX_LOGICAL_KEY_BYTES` 降到 205）。

## 設計文件本身的問題

1. **D17 仍寫「熱載入 operational policy」**（`docs/design/design-decisions.md:29`），D45（:70）與 §4.2 已改為不熱載入、只在重啟時啟用，但 D17 沒有「修」列，也沒有標註已被取代。ticket 明說不得沿用熱載入，設計表本身卻仍自相矛盾。
2. **§3 `obligation` 索引「(state, source_ready_at) 供 age」**（`system-design.md:216`）指向 `obligation` 沒有的欄位（`source_ready_at` 在 `file_identity`）。設計需決定：加反正規化欄位、改索引 `file_identity`，或改以 join 計算。
3. **§3 `received` 欄位沒有 `content_path`、`data_class`**（`system-design.md:217`），但 ticket P02-07 要求 `received` 保存 Source 選定的 `content_path`。「P02 偏差 ⑥」（`design-decisions.md:129`）把 `received.data_class` 稱為「實作新增的代理主鍵」，這不正確：它不是鍵。`content_path` 的新增也沒有記錄在任何偏差列。
4. **遲到發布停跑容忍 19 天的歸屬互相矛盾**：D56 定案 ④（`design-decisions.md:99`）寫「清道夫停跑…合計 < 19 天（operational policy，上限 19.75）」，D30 修 5（:121）與 §8（`system-design.md:355`）則把它列為 v1 固定常數（實作 `FixedConstants.V1.latePublishToleranceDays = 19`，不可覆寫）。D56 定案沒有被標註修訂。
5. **ESTALE 的結果分類不一致**：`traceability.md:15` 寫「ESTALE → PENDING_CONFIRMATION（§5）」，但 §5 沒有這條規則。core README 規則 3 把寫入時的 ESTALE 視為中毒 → Failure，link 步驟的 ESTALE 在程式裡是 `Failure(IO)`。#6 把 Failure 改成終態後，同一 handle 不會再經 rediscovery 翻成 Success，這個分類因此變得有實際影響，需要擇一定案。
6. **Discard 的適用狀態不一致**：CONTEXT.md:68「只允許對 Writing 狀態」、P01 偏差 3 ⑨（`design-decisions.md:128`）「Finalize 已開始（含 PENDING）後 discard() 拒絕」；core README 規則 5 與 #6 的實作則允許對「Finalize 已回 Failure」的 handle discard。「Finalize 失敗」這個狀態在 CONTEXT.md 仍未定義（⑨ 自己也寫「待補術語」）。
7. **缺少會影響 schema 正確性的前提**：沒有任何設計文件定義 DB 時間欄位的時區語意、Oracle 字元集，以及 identity 片段允許的字元集與最低 Oracle 版本（見建議 4、5）。V1 一旦上線，這些都得靠 migration 才能改。

## 驗收核對

**P02 ticket AC**

- P02-01 獨立啟動：✅ `StartupRefusalTest` 兩案確實斷言 context 啟動失敗；`PolicyEndpointTest`／`HealthEndpointTest` 證明有效設定下可以取得 `/policy` 與 health；啟動序列沒有掃描或 rebuild。
- P02-02 Policy 登錄契約：✅ `ConfigCodecTest` 以 `targetsFor` 分辨 `Optional.empty()` 與 `Optional.of(Set.of())`，`allowsWrite` 分辨未登錄的 namespace／class；`/policy` 輸出 `namespaces` 與空的 `targets`。
- P02-03 設定驗證：✅ 未知欄位、schema_version、節點與 targets 關係、token 雜湊唯一、版本非遞增、Policy 或 namespace 改動、fixed 段不等於 V1 都有測試，並確實斷言拒絕。
- P02-04 安全啟用：⚠️ 單元層級（`ConfigStoreTest`）通過，但沒有 Spring 層級的測試證明「帶 candidate 啟動後 `/policy` 與 gauge 皆為新版」；而且必修 3 顯示磁碟上的 active 可能不是已驗證的那一版。
- P02-05 失敗與回退：❌ active／candidate 讀不到時拒絕啟動，不回退也不視為拒絕（必修 2，已重現）；`activation_failure_count = 1` 的接線沒有測試。
- P02-06 中斷恢復：⚠️ `crash_between_renames_recovers_on_next_start` 通過；但驗證與 rename 之間換檔，會讓下次啟動採用未驗證、改過 Policy 的版本（必修 3，已重現），「只能採用一個完整有效版本」不成立。
- P02-07 完整 schema 與冪等 bootstrap：⚠️ 十張表、`received.content_path`、單列 `node_meta`、計數器保留都有。但少了設計列出的 `(state, source_ready_at)` 索引（設計文件問題 2）；索引與多數約束沒有斷言；「空 DB」受共用 in-mem DB 影響，不保證被那個測試本身驗到；Oracle 實機沒有跑（validation 文件已標）。
- P02-08 DB 故障恢復：⚠️ `DbOutageRecoveryTest` 只模擬「一開始就連不上」且斷言確實；migration 進行中斷線會永久卡住（建議 2，已重現），與「DB 恢復後自動就緒」的宣稱不符。
- P02-09 health 語意：⚠️ readiness 的 DB／NFS 分開呈現、NFS 目錄消失時 DOWN→UP、liveness 獨立都有測試；但「逾時不釋放底層操作」只以 stub 測試（驗不到）；bootstrap 後 DB 斷線沒有測試；gauge 在卡住時無法被 scrape（建議 1）。
- P02-10 Node 認證：✅ 缺少、非 Bearer、未知 token → 401；`/%70ending`、`/pending;x=1`、dot-segment 走 fail-closed；雜湊唯一由 codec 保證；`/policy` 不含 operational 或 token（有斷言）。
- P02-11 角色身分：⚠️ 只有 `/pending` 的 403 與 `/received` 不套用規則有測試，而且用的是測試 controller；`/file`、`/report` 沒有測試。「只列 caller 為 Source 的資料」已明確交給 P04（validation 文件有寫）。
- P02-12 可交接與可重現：⚠️ README 涵蓋所需主題，但認證範例在 jar 上得到 404（建議 7）；「actuator 只綁內部介面」在單一 port 下做不到（建議 3）；測試數自相矛盾（`P02-validation.md:43` 寫 177，`:62` 寫 175），合併後實際為 181（core 125）；「執行版本」只寫到 `1448a2d`，必修修正 commit `a9c309d` 在它之後。

**P01 驗收紀錄（#6 所改部分）**

- 狀態列「PR #3 已合併到 main `4cddff5`、head `ad57714` CI 成功」：✅ `4cddff5` 在 `origin/main` 上，`gh` 顯示 PR #3 head `ad57714` 的 `test` 為 SUCCESS。
- 「合併後稽核的 lifecycle 修正見 `WriteHandleLifecycleTest`」：✅ 4 個測試都會在行為被移除時失敗（sweep 兩個觸發點分別證明；Failure 重放以 `isSameAs` 斷言）。
- 結果列「99 tests」＋被測版本「本文件所在 commit」：❌ PR #6 head 的 core 實際跑 103 個測試，合併樹 125 個；文件宣稱的版本與數字已不相符。另外「（本分支）」在合併後失去意義。
- core README 規則 2（Failure 終態、原樣重放，包含 discard 之後）：✅ `failure_is_terminal_and_discardable` 有斷言。
- core README 規則 5（discard 刪除失敗 → 中毒、不發布；刪除成功才 DISCARDED）：⚠️ 只對 `NfsException` 成立；一般 `IOException` 會導致發布（必修 1，已重現）。
- 「`LocalStore` 回收已結束的 link future」：✅ `linkSent` 與 `beginWrite` 兩個觸發點各有一個會失敗的測試；語意上只移除 `isDone()` 的 future，不改變 in-flight 判定。
- 「Java 21 runtime 由 GitHub CI 跑過」：✅ 限於 `ad57714`；PR #6 head `fe582d9` 的 CI 也是 SUCCESS，但文件沒有引用它。


---

## 第 2 輪 scoped re-review（同一 opus reviewer，對 #6 `00b3be2`、#5 `c44b762` 與合併樹）

# Scoped re-review：第 2 輪三項必修的修正

範圍：只看 `00b3be2`（PR #6，discard）與 `c44b762`（PR #5，ConfigStore），審查對象是合併試跑樹 `.worktrees/review-merged` HEAD `1e1049c`。本機 `mvn -q test` 全綠：core 126、sync-service 61，共 **187**（coordinator 說 188，我數到的是 187，差 1，可能只是計數口徑不同）。上一輪的重現測試照新簽章改寫成「斷言修好後的行為」，另外新寫一個 crash 邊界測試。改寫後的測試放在 `scratchpad/review2-tmp/rr/`；複製進 worktree 跑完就刪了，連同 `target/` 內的 class 與 surefire 報告，最後 `git status --short` 為空。

## 必修逐項

- **必修 1（#6，discard 遇一般 IOException 仍可發布）**：✅ 已修。`ReviewDiscardReproTest` 做法同上一輪：寫 64 KiB，把暫存所在目錄 chmod 成唯讀，呼叫 `discard()` 得到 `AccessDeniedException`。之後 `finalizeWrite()` 現在回 `Failure[IO, "discard delete unresolved at discard-writing: java.nio.file.AccessDeniedException: …"]`，正式路徑不存在。目錄恢復可寫後再呼叫一次 `discard()`，暫存就被刪掉，`finalizeWrite()` 仍回同一個 `Failure`（以 `isSameAs` 斷言）。**不再重現。**新增的 `WriteHandleLifecycleTest.discard_hard_io_failure_poisons_handle_and_never_publishes`（`failBefore`）會在舊碼上失敗，能保護這次修正。
- **必修 2（#5，IOException 讓 process 拒絕啟動）**：✅ 已修。`load()` 不再丟 `IOException`。`unreadable_active_with_valid_lkg_falls_back`：active 為 0000 時退回 lkg v2（`Source.LKG`），並帶失敗信號。`unreadable_candidate_with_valid_active_is_rejected`：candidate 為 0000 時用 active v3，candidate 留在原地，並帶失敗信號。**兩者都不再重現。**新測試 `activation_io_failure_keeps_running_config_and_candidate` 覆蓋安裝途中 `active→lkg` 失敗的情況。
- **必修 3（#5，CD 在驗證與 rename 之間換檔，未驗證版本成為 active）**：✅ 已修。我保留上一輪的真實執行緒時序迴圈（不用新的 hook），跑 3000 次，另一執行緒在 0–300 µs 內以 ATOMIC_MOVE 換上改了 Policy 的 v6。結果是「磁碟上 active 與執行中版本不一致，或 active 成為 v6」**0 次**；CD 換上的 candidate 3000 次都被保留下來，留給下次啟動重驗。**不再重現。**修正者用 hook 寫的測試 `candidate_replaced_after_validation_is_not_activated` 也驗證了下次啟動會以「policy」理由拒絕那份 candidate。

## crash 邊界（`ReviewConfigCrashBoundaryTest`，6 案全過）

每個案例都先做出 crash 在該邊界時會留下的磁碟狀態，再啟動一次檢查結果。起點一律是 active v3、lkg v2、candidate v4：

| crash 位置 | 磁碟狀態 | 下次啟動結果 |
| --- | --- | --- |
| tmp 寫到一半 | active v3、lkg v2、candidate v4、tmp 半截 | 截斷並重寫 tmp，完成啟用：跑 v4；active v4、lkg v3；candidate 已刪；無失敗信號 |
| tmp 已 fsync，active→lkg 之前 | 同上，但 tmp 是完整的 v4 | 同上，收斂到 v4 |
| active→lkg 之後，tmp→active 之前 | 沒有 active、lkg v3、candidate v4、tmp v4 | 以 lkg v3 為基準重新驗證 candidate，安裝後跑 v4；active v4、lkg v3。會帶「active.json missing」失敗信號，這個行為和修正前相同 |
| 同上，但 CD 期間把 candidate 換成改了 Policy 的 v6 | 沒有 active、lkg v3、candidate v6（壞）、tmp v4 | 舊的 tmp v4 **不會被採信**；candidate 被拒，跑 lkg v3，`Source.LKG`，candidate 留在原地 |
| tmp→active 之後，刪 candidate 之前 | active v4、lkg v3、candidate v4 | 內容逐位元組相同，補刪 candidate，無失敗信號；lkg 仍是 v3，沒被同版覆蓋 |
| active 損毀時的 tmp 已寫、tmp→active 後兩個邊界 | active 損毀、lkg v2 | 兩次都收斂到 v4，lkg 始終是 v2：損毀的 active 從未被輪替成 lkg |

每個邊界都只會採用一個完整、驗證過的版本，沒有混合設定。tmp 在 rename 前已 fsync；目錄本身沒有 fsync，斷電時 rename 可能回捲，但回捲後的狀態仍是上表其中一列，已驗證可收斂。

## 設計選擇判斷

1. **不搬 candidate，改把驗證過的 bytes 寫成 `active.json.tmp`（fsync），再 active→lkg、tmp→active，candidate 內容未變才刪**：正確。這從根本上消除了「磁碟上的 active 不是被驗證的那份」：成功路徑下，執行中的 `accepted` 就是 `active.json` 的內容。每個 crash 邊界都已驗證可收斂（見上表）。stale tmp 永遠不會被直接採用，只會在下次安裝時被截斷重寫。
2. **candidate 與 active 逐位元組相同時，視為已生效，刪除且不計失敗**：合理，**沒有削弱 P02-03**。這種 candidate 不會改變任何生效內容；P02-03 要防的「非遞增版本」是回捲或替換，這裡兩者都不發生。反過來，如果照一般啟用走 active→lkg，會把 lkg 蓋成同版，真正的上一版就丟了。所以 no-op 比「拒絕並計失敗」更安全，也讓 CD 重送同一版保持冪等。改寫後的測試（同版本、不同內容）仍測到 `version <= baseline` 的拒絕分支。可選補強：加一個「版本比 active 低」的回捲案例（程式碼同一分支，不是缺陷）。
3. **讀取、驗證、安裝途中任何 IOException 都算「candidate 被拒」，用磁碟上仍生效的那份啟動；以 package-private hook 取代時序迴圈**：正確。`rejected()` 選的 source 是對的：若 active→lkg 已成功、tmp→active 才失敗，這時 active 已不存在，回報 `Source.LKG`，內容就是 baseline，與磁碟上的 lkg 一致。代價是暫時性錯誤要等下次重啟才會重試，符合 D45「只在重啟啟用」。另一個伴隨的語意：active 暫時讀不到時，candidate 會以 lkg 為基準驗證並直接覆蓋 active，這讓 lkg 保持較舊的版本，但這正是「讀不到＝缺」的要求，與損毀 active 的既有路徑一致，不算缺陷。hook 是 no-op 預設、只給測試用的最小介面，能決定性地重現時序；我的獨立時序迴圈（0/3000）也得到相同結論。
4. **`discard()` catch `NfsException | IOException | RuntimeException`，記下終態 Failure 後重拋原型別**：正確，重拋型別也保留得對（`NfsException` 包成 `NfsUnavailableException`，其餘原樣）。原本的 `discard_retries_delete_after_nfs_failure` 斷言的 `startsWith("discard delete unresolved at discard-writing")` 仍然成立。剩下的只有 `Error` 還會讓 handle 停在 WRITING（見新問題 2）。

## 新問題

以下都是**非阻擋**的小項，不影響判定：

1. `ConfigStore.java:78-81` 逐位元組相同的分支直接 `Files.deleteIfExists(candidate)`，刪之前沒有像 `removeCandidateIfUnchanged` 那樣再比對一次。在「讀 candidate」與「刪除」之間，若 CD 換上新 candidate，那份新 candidate 會被刪掉：一次發布遺失，也不留失敗信號。後果只是遺失，不會讓未驗證的版本生效，而且只在 crash 復原或 CD 重送同版時才會走到這條分支，窗口很小。與 `:130` 的 ponytail 註解是同一類殘留，但這條分支沒有註明。建議改呼叫 `removeCandidateIfUnchanged(candidate, bytes)`，只要一行。
2. `WriteHandle.discard()` 沒有 catch `Error`（例如 driver 或 JVM 丟出的 `Error`）。這時 handle 仍停在 WRITING，之後 `finalizeWrite()` 可能發布。極端情況，可改用 `try { …; done = true; } finally { if (!done) poison(); }` 一次涵蓋所有 Throwable。
3. 文件殘留：`WriteHandle.java:108-112` 的 javadoc 仍寫「池滿／timeout 時 handle 中毒」；`docs/design/file-inventory.md:27-28` 仍寫「驗證後 rename 為 active」「由 candidate rename」，也沒有列出 `active.json.tmp`。P02 偏差 3 ⑩ 雖已聲明取代這些句子，但 inventory 表本身還是舊的。

CLEAN


> 處理：新問題 1（already-active 分支直接刪 candidate）改走 `removeCandidateIfUnchanged`；新問題 2（`discard()` 未涵蓋 `Error`）已補；新問題 3 的 `discard()` javadoc 已更新，`docs/design/file-inventory.md` 的舊敘述留待設計文件裁定（已記於 `P02 偏差 3` ⑩）。

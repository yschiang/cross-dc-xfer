# P02 — sync-service 骨架驗收紀錄

Ticket: [P02：Node 本地同步服務基礎 #1](https://github.com/yschiang/cross-dc-xfer/issues/1)

## 基準

- Branch: `p02-sync-service-skeleton`
- Rebase：2026-09-23 PR #3 合併後，分支以 `git rebase --onto origin/main 86360ac` 重放到 main `4cddff5`（core 為 PR #3 最終版：`Sha256.ofFile(Path)` 已移除、執行器為 Semaphore 版），core 測試數由 85 變 120（含 P01 PR 的 99 + P02 的 config／hex 測試）。衝突只在 `Sha256.java`（保留 `hex`）、P02 plan、design-decisions 列順序。
- HEAD（程式碼與文件）: `baaa40a`（rebase 前 hash；rebase 後對應 `1448a2d`）（final-review fix batch：`6413b34` code + `baaa40a` docs）；本驗收紀錄的收尾 commit 緊接其後，見 `git log -1`
- Base（本輪計畫修訂起點）: `72ac972`（docs: P02 plan revision）
- 上游 P01: `86360ac`
- Commit 序列：`650e49a` Task1、`6f935a7`+`1617096` Task2、`0f3f916` Task3、`eab8df6`/`83e4ff0`/`7191331` 1R–3R、`62bab04`+`070c2c8` Task4、`0fbe020`+`9c87006` Task5、`99521aa` Task6、`947d789` Task7、`75d9cd2` Task6 fix round 1（審查 H-1：`getRequestURI` 未解碼／含 `;` 可繞過 filter → 改 `UrlPathHelper` 解碼路徑 + 豁免清單預設全保護）、`a53c4f5` 文件同步 fail-closed 認證行為、`e4bb292` re-review nits（dot-segment fail-closed、RFC 7235 行為測試）、`6413b34` final-review fix batch（health gauge supplier 化、readiness DOWN/UP 斷言、config 缺 active 的 activationFailure、readiness group 加 readinessState、DB query timeout、/policy 不洩漏 operational）+ 本批 docs 更新。

## PR #5 獨立 review 後修正（2026-09-23）

review 全文 `docs/reports/pr5-review.md`。必修三項已修：① 片段 UTF-8 位元組上限對齊 V1 欄寬（`FileIdentityTest.segment_limits_are_utf8_bytes_matching_schema_widths`、`SchemaTest.identity_column_widths_match_core_segment_limits`）；② `ConfigStore` 不再重讀 candidate；③ active 損毀信號保留（`corrupt_active_does_not_overwrite_good_lkg_when_candidate_activates` 加斷言）。建議 9 項與設計文件 4 項見 follow-up issue。P02-11 驗到的是「契約工具可用」（測試用 `EchoCallerController`），端點遵守契約留 P04。

## 第 2 輪獨立 review 後修正（2026-09-24）

第 2 輪 review（fresh opus，對 `442c418` 與 main+#6+#5 合併樹）判 P02-05 不通過，兩項必修皆已重現：`ConfigStore` 遇到 `IOException` 拒絕啟動（不退回 lkg、不把 candidate 當被拒），以及第 1 輪修正後 CD 換檔仍可讓未驗證版本成為 active。已修：讀不到視同缺、candidate 各種失敗皆為被拒、安裝寫入驗證過的 bytes 而非搬 candidate 檔（design-decisions `P02 偏差 3` ⑩ 已改寫）。新增 `ConfigStoreTest`：`unreadable_active_falls_back_to_lkg`、`unreadable_candidate_is_rejected_and_active_kept`、`activation_io_failure_keeps_running_config_and_candidate`、`candidate_replaced_after_validation_is_not_activated`、`candidate_identical_to_active_is_cleared_without_failure`；關鍵兩處以 mutation 反證。`rejects_candidate_with_non_increasing_version_and_keeps_it` 的 candidate 改為同版本但內容不同（同 bytes 現在視為已生效）。review 全文 `docs/reports/pr5-review.md` 第 2 輪段。

## Senior review 第 1 輪後修正（2026-09-24）

Senior review（Codex，對 `3b758b3`）判 P02-03、P02-05、P02-08、P02-11 未通過，四項阻擋皆已修：① `ConfigCodec` 對 JSON `null`（整份或清單元素）丟 `InvalidConfigException` 而非 NPE，讓 `ConfigStore` 回退；② 嚴格解析：浮點不截成整數、字串不轉數字、數字不轉字串、JSON 後不得接任何 token；③ V1 改為逐句冪等的 Java migration（SQL 移到 `db/schema/V1.sql`），`DbBootstrap` 發現失敗紀錄時先 `repair` 再 migrate，首次 migration 在 DDL 中途斷線後自動接續、不刪既存物件；④ `/file`、`/report` 補 Target 角色契約測試（測試 controller，正式端點仍留 P04）。③ 的 repair 與逐句跳過、① 的 null 處理皆以 mutation 反證。非阻擋項（README 認證範例、SchemaTest 冪等覆蓋）開 follow-up issue。

## 環境

- 硬體/OS：arm64 macOS，Darwin 27.0.0
- JDK：Homebrew OpenJDK 27，以 `--release 21` 編譯（POM `maven.compiler.release=21`）；`/usr/local` 工具鏈為 x86_64、不可用
- Maven：3.9（系統安裝）
- DB：H2 2.3，`MODE=Oracle`（測試 in-memory；非 Oracle 實機）
- NAS：本機檔案系統（`@TempDir`）代替 NFS `hard` mount；未對真實 NFS client 語意（ESTALE、lock、failover）驗證
- 每個 shell 先執行：
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
  ```

## 命令與結果

```bash
mvn test
```

結果：`BUILD SUCCESS`。

| 模組 | 測試數 | Failures | Errors |
| --- | --- | --- | --- |
| gigaxfer-core | 123 | 0 | 0 |
| gigaxfer-sync-service | 68 | 0 | 0 |
| 合計 | 191 | 0 | 0 |

（測試類別清單：核對用 `grep -rn "void " gigaxfer-*/src/test` 取實際方法名，下表逐 AC 列出對應項。）

## 逐 AC 驗收證據

| AC | 對應 task | 測試證據 | 結果 | 備註 |
| --- | --- | --- | --- | --- |
| P02-01 獨立啟動 | Task 3、5 | `StartupRefusalTest.node_not_in_policy_refuses_to_start`、`StartupRefusalTest.missing_active_and_lkg_refuses_to_start`、`ConfigStoreTest.refuses_when_neither_active_nor_lkg_exists` | 通過 | context 啟動失敗（非靜默降級）；P02 不掃描（掃描器 P03 才加入） |
| P02-02 Policy 登錄契約 | Task 1R、3R | `PolicyEndpointTest.policy_endpoint_returns_version_and_normalised_policy_without_auth`、`ConfigCodecTest.registry_rejects_unregistered_namespace_but_allows_local_only_class`、`ConfigCodecTest.namespace_registry_is_validated_and_part_of_policy_identity`、`ConfigCodecTest.rejects_target_not_in_nodes`、`ConfigCodecTest.rejects_source_as_its_own_target`、`ConfigCodecTest.rejects_duplicate_source_class_pair` | 通過 | Namespace 未登錄不可 write；空 targets 表示已登錄但僅本地 |
| P02-03 設定驗證 | Task 1、1R、2 | `ConfigCodecTest`（20 個測試：`rejects_unknown_field`、`rejects_wrong_schema_version`、`fixed_segment_may_be_omitted_and_then_defaults_to_v1`、`rejects_fixed_segment_that_differs_from_v1`、`rejects_empty_nodes`、`rejects_more_than_ten_nodes`、`rejects_bad_node_name_segment`、`rejects_peer_token_for_unknown_node_and_bad_hex`、`rejects_duplicate_peer_token_hash`、`rejects_capacity_reject_not_below_alert`、`rejects_non_positive_operational_value` 等）、`ConfigStoreTest.rejects_candidate_with_non_increasing_version_and_keeps_it`、`ConfigStoreTest.rejects_candidate_whose_policy_differs`、`ConfigStoreTest.rejects_candidate_that_changes_registered_namespaces`、`ConfigCodecTest.rejects_implicit_coercion_and_trailing_tokens`、`ConfigStoreTest.null_coerced_or_trailing_garbage_candidates_are_rejected_and_active_kept`（senior review 第 1 輪） | 通過 | schema 合法性、version 嚴格遞增、policy 段相等、fixed 段等於 v1 常數、token 雜湊唯一皆有覆蓋 |
| P02-04 安全啟用 | Task 2、3、3R | `ConfigStoreTest.activates_valid_candidate_and_rotates_active_to_lkg`、`ConfigStoreTest.accepts_candidate_that_only_changes_operational_and_node_order`、`PolicyEndpointTest.config_is_immutable_while_process_runs` | 通過 | 驗證通過才 rename；運行期改 `active.json` 檔案內容不影響記憶體中設定 |
| P02-05 失敗與回退 | Task 2、2R、3 | `ConfigStoreTest.rejects_malformed_candidate_and_keeps_active`、`falls_back_to_lkg_when_active_missing`、`falls_back_to_lkg_when_active_is_corrupt`、`corrupt_active_does_not_overwrite_good_lkg_when_candidate_activates`、`corrupt_active_without_lkg_refuses_even_with_good_candidate`、`candidate_alone_does_not_bypass_manual_initial_active_setup`、`candidate_is_validated_against_lkg_when_active_missing`、`unreadable_active_falls_back_to_lkg`、`unreadable_candidate_is_rejected_and_active_kept`、`activation_io_failure_keeps_running_config_and_candidate`、`candidate_replaced_after_validation_is_not_activated`、`PolicyEndpointTest.config_metrics_are_registered_with_node_tag`（`activation_failure_count` 基準值 0）、`ConfigCodecTest.rejects_json_null_root_and_null_elements_as_invalid`、`ConfigStoreTest.null_active_falls_back_to_lkg`（senior review 第 1 輪） | 通過（有缺口） | 拒絕留 candidate 原地；壞 active 不覆蓋好的 lkg 且 `activationFailure` 保留損毀信號（PR #5 review 必修 3）；candidate-only 初始化拒絕。缺口：`activation_failure_count = 1` 的 gauge 接線沒有測試（review 建議 3，follow-up） |
| P02-06 中斷恢復 | Task 2 | `ConfigStoreTest.crash_between_renames_recovers_on_next_start`、`ConfigStoreTest.ignores_candidate_tmp_still_being_written_by_cd` | 通過 | 設定啟用不觸碰 DB；義務與控制狀態由 schema 持有（Task 4） |
| P02-07 schema 與冪等 bootstrap | Task 4 | `SchemaTest.migration_runs_in_background_and_creates_all_tables`、`SchemaTest.node_meta_has_one_incarnation_and_seq_counters_start_at_zero`、`SchemaTest.bootstrap_is_idempotent_across_restarts`、`SchemaTest.obligation_state_check_constraint_rejects_unknown_state`、`SchemaTest.remote_received_keeps_source_selected_path_without_local_source_row` | 通過 | 含 `received.content_path`、非零計數器保留；欄位改名見 design-decisions「P02 偏差」⑥ |
| P02-08 DB 故障恢復 | Task 4、5 | `DbOutageRecoveryTest.db_becomes_ready_without_restart_after_outage`、`MigrationResumeTest.ddl_failure_mid_v1_resumes_on_next_attempt_and_keeps_existing_rows`、`MigrationResumeTest.v1_rerun_without_history_record_skips_existing_objects`（首次 V1 在 DDL 中途斷線後自動接續，senior review 第 1 輪） | 通過 | DB 不可用時 process 不退出、`/policy` 仍回應；DB 恢復後不重啟即轉 ready（`gigaxfer.db-retry-millis` 測試縮短為 200ms） |
| P02-09 health 語意 | Task 5 | `HealthEndpointTest.readiness_is_up_when_db_migrated_and_nfs_root_reachable`、`HealthEndpointTest.nfs_component_is_down_when_root_disappears_and_recovers`、`HealthEndpointTest.liveness_does_not_depend_on_db_or_nfs`、`NfsTimeoutHealthTest.nfs_component_is_down_when_probe_times_out`、`HealthGaugeTest.gauges_reflect_state_without_prior_health_request`（不經 health 請求，`db_health`/`storage_health` 於 scrape 時即時探測） | 通過 | 含 NFS timeout；liveness 只看 process 存活，不受 DB/NFS 影響 |
| P02-10 Node 認證 | Task 6 | `NodeAuthFilterUnitTest`（`protected_paths_require_auth_even_when_obfuscated` 對 `/pending;x=1`、`/%70ending` 等參數化、`exempt_paths_need_no_token`）、`Sha256Test.hex_of_p1_secret_matches_fixture`；`NodeAuthFilterTest`（10 個測試：`own_token_is_read_from_secret_file_and_trimmed`、`missing_authorization_is_401`、`unknown_token_is_401`、`non_bearer_scheme_is_401`、`known_token_resolves_caller_node`、`target_param_equal_to_caller_is_allowed`、`target_param_different_from_caller_is_403`、`node_internal_endpoints_need_no_token`、`protected_prefixes_cover_file_subpaths`、`received_does_not_apply_target_equals_caller_rule`） | 通過 | 401 = 無/未知 token；403 = target 與 caller 不同（D14 修 2） |
| P02-11 角色身分（有缺口） | Task 6 | `NodeAuthFilterTest.known_token_resolves_caller_node`、`NodeAuthFilterTest.target_param_different_from_caller_is_403`、`NodeAuthFilterTest.received_does_not_apply_target_equals_caller_rule`、`NodeAuthFilterTest.target_endpoints_take_target_from_caller_identity`（`/pending`、`/file/**`、`/report` 三個 Target 端點：不帶或相同 target → 200、不同 → 403；senior review 第 1 輪） | 通過 | `/received` 不套用 target==caller 規則；「只列 caller 為 Source 的列」的實作留給 P04 |
| P02-12 可交接可重現 | Task 7 | `gigaxfer-sync-service/README.md`、本檔（`docs/validation/P02-validation.md`）、`mvn test` 175/175 全綠（Task 7 當時 139） | 通過 | 含首次初始化、設定更新/回退操作、DB 斷線觀察、認證 curl 範例、health/metrics 範例、HTTPS 部署要求 |

## 尚未驗證

以下項目在本環境無法驗收，需在對應的實機/部署環境另行驗證：

- **Oracle 實機**：schema／DDL 只在 H2 2.3 `MODE=Oracle` 驗證過；未對真實 Oracle（保留字、型別轉換、鎖行為、Flyway `flyway-database-oracle` 方言）跑過。
- **真實 NAS**：NFS mount 以本機檔案系統模擬；ESTALE、lock 語意、實際逾時／busy 行為、failover 時的穩定寫入（D12 修 2）未驗證。
- **HTTPS**：`server.ssl.*` 部署設定未套用；所有測試與本地執行走明文 HTTP。
- **跨 Node（多主機）**：`NodeAuthFilterTest` 等認證測試在單一 process 內以多組 token 模擬多個呼叫者身分，未在實際跨主機部署下驗證多個 sync-service process 互相呼叫。
- 真實檔案大小分佈下的壓測（吞吐、延遲、rebuild 時間等）——沿用「驗收審查 5」既有結論，本輪未新增量測。

## Parked（已知、非本輪修正範圍）

- `ConfigActivation.activationFailure` 為單一 `Optional<String>` 原因欄位：一次 candidate 驗證若同時觸發多條失敗規則，只會保留其中一則訊息，其餘原因不會並列呈現。
- `DbBootstrap.stop()`（`SmartLifecycle`）只對背景重試執行緒呼叫 `interrupt()`，未 `join()` 等待其真正結束；正常關閉流程下屬良性競態，但測試或工具化關閉時無法保證該執行緒已完全停止。
- `SchemaTest` 驗證表存在、欄位、CHECK 約束與冪等 bootstrap，但未斷言索引（`ix_obligation_target_state_next` 等）確實建立；索引目前僅來自遷移腳本本身。
- 設計文件 `docs/design/system-design.md` §14 obligation 表義務欄位列出索引「(target_node, state, next_attempt_at)；(state, source_ready_at) 供 age；(target_node, completed_seq)」，但 `V1__schema.sql` 的 `obligation` 表沒有 `source_ready_at` 欄位（該欄位屬於 `file_identity`），也未建立 `(state, source_ready_at)` 索引；此落差未在本輪核准修正，留待設計決策裁定索引欄位或改用 `file_identity` join。
- `FinalizeUnderPressureTest`（P01 既有，非本輪異動）以背景執行緒占用唯一 NFS 執行器槽位、100ms timeout 斷言 `UNAVAILABLE`，為時序敏感測試；本輪執行未見失敗，但排程延遲仍可能造成偶發不穩定，未額外加固。

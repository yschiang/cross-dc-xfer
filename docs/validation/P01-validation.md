# P01 發布能力驗收紀錄

**Ticket：** [P01 #2](https://github.com/yschiang/cross-dc-xfer/issues/2)

**狀態：** 本機自動化測試通過，提交人工 PR review；未合併，不代表完整 M1 或真實 NAS 已驗收。

## 版本與執行環境

| 項目 | 證據 |
| --- | --- |
| 原實作 | `p01-core-finalize`，`86360ac615cddba46c98ce797931bfb755254bf9`（11 個 task；whole-branch review → 修正 → scoped re-review CLEAN，記於本地 P01 SDD ledger） |
| 提交分支 | `p01-core-finalize-pr`，基於 `c891fc655bcc70c736f659d11f4d1a34099da825`；首個 commit `f17cc4c` 移入原實作，之後的修正 commit 回應 PR #3 review 與 CI（見「審查修正」） |
| 被測版本 | 本文件所在 commit（提交前與提交後均以相同 worktree 內容重跑） |
| 本次執行 | 2026-09-23，macOS arm64，本機暫存檔案系統；JDK 27、Maven 3.9.16，`maven.compiler.release=21` |
| 結果 | **98 tests，0 failures、0 errors、0 skipped**；Surefire XML 彙總 |
| CI | GitHub Actions `CI`（`.github/workflows/ci.yml`）以 Temurin **JDK 21** 執行 `mvn -B -ntp verify`；本地修正尚未 push，沒有對應 remote check |

舊 P01 分支與現行 main 無共同祖先，因此另建提交分支，未改寫原 P01／P02 分支。本紀錄不是 GitHub Reviewer 已批准。

## 重現命令

在包含此紀錄的 PR 版本執行：

```sh
java -version
mvn -version
mvn -q -pl gigaxfer-core test
```

本次以 `mvn -q -pl gigaxfer-core test` 執行。Surefire 結果位於 `gigaxfer-core/target/surefire-reports/`；Reviewer 應在 Java 21 runtime 再執行，不能把 release 21 編譯目標等同 Java 21 執行測試。

## Ticket 驗收對照

| AC | 本次證據 | 範圍 |
| --- | --- | --- |
| P01-01 | core POM、SmokeTest、core README 的 Success／Pending／Failure 範例 | core 無 Spring／DB；業務交易提交由 App 負責 |
| P01-02 | FileIdentityTest、BeginWriteTest | 非法命名、保留字與 gate 拒寫 |
| P01-03 | ManifestCodecTest：固定 wire line、十欄逐欄缺漏、null primitive、unknown field、型別、digest、截斷與 16 KiB 上限；FinalizeRecoveryTest：其他 identity 與錯 `source_ready_at` 小時的 content_path 都拒絕 | content_path 由 identity／data class／source_ready_at／logical key 與 PathLayout 時區精確推導 |
| P01-04 | FinalizeHappyPathTest、FinalizeRecoveryTest；FinalizeRetryTest `writes_after_success_are_rejected_even_if_close_failed`、`writes_are_rejected_after_finalize_starts_even_before_digest_is_fixed` | 正常發布、空檔、大檔、宣告路徑、暫存清理；Finalize 一開始即拒寫，close 失敗也不能改正式 inode；不證明真實 NAS 持久化 |
| P01-05 | 同 handle 重試、隔日新 handle、F5、D44 的測試 | 相同內容成功；衝突不覆寫已發布檔 |
| P01-06 | FinalizeRecoveryTest 的 F1b、F2、F2b、F3、F4、F5b、scenario_11；`LinkOwnershipWithBoundedExecutorTest` 以 production executor 真正 blocking link 驗證 ownership、過期與完成後 rediscovery | 模擬操作前失敗／操作後回覆遺失，並真正在 executor worker 卡住 link；in-flight 紀錄以單一 LocalStore 實例為範圍；沒有殺 process 或 NAS failover |
| P01-07 | BoundedNfsExecutorTest（timeout 交出仍執行的 future、op 返回即釋放槽位）、LinkOwnershipWithBoundedExecutorTest、FinalizeUnderPressureTest、chunked digest；Sha256 檔案入口只接受 NFS executor；manifest 在 executor 內有界讀取 | timeout 槽保留、池滿行為、completion handle、digest 分塊；未做真實 NAS hang 壓測 |
| P01-08 | WriteHandleUnavailableTest、FinalizeRetryTest、stat-key error 測試。新契約：finalize 第①步的 flush 屬於寫入，失敗或 timeout → handle 中毒、該次即回 FAILURE(IO)（`flush_timeout_in_finalize_is_immediate_failure_not_pending`、`flush_io_error_in_finalize_is_immediate_failure`）；自 ① fsync 起 timeout／池滿 → PENDING_CONFIRMATION，重呼收斂（`fsync_timeout_is_pending_then_retry_publishes`、`fsync_timeout_then_close_then_retry_still_publishes`、`finalize_when_pool_full_is_pending_confirmation_and_retry_succeeds`） | write IOException 中毒、非 ENOENT 不誤判缺檔 |
| P01-09 | 本檔、PR、ticket、plan 與 core README | 人工審查及 ticket 最終勾選尚待完成 |

## 審查修正

### 原 ledger 的修正（已包含在 `86360ac`）

- write 的一般 IOException 也會使 handle 中毒，避免 digest／size 與內容不一致仍發布。
- data class 與其他路徑片段同樣驗證，避免越出目錄。
- 既有內容的 digest 以分塊 NFS 操作讀取；stat 只將 ENOENT 視為不存在。
- fsync 結果未知後可重試；通道已 close 時可重開暫存檔再確認。
- 新宣告不套用既有宣告年齡檢查；已有正式內容時先 rediscovery。

### PR #3 review 的修正

| # | 問題 | 修法 | 測試 |
| --- | --- | --- | --- |
| 1 | manifest 缺 size／schema_version、size 為 null 或非整數時仍被接受（`ffc9361`） | codec 對必要原始欄位缺漏或型別錯誤一律 malformed | `missing_size_is_malformed`、`null_size_is_malformed`、`missing_schema_version_is_malformed`、`non_integer_size_is_malformed` |
| 2 | manifest 的 content_path 可指向其他 identity，重試會把別人的內容當已發布（`080f4eb`） | content_path 必須由 identity 與 data class 推導，否則 malformed | `content_path_not_derived_from_identity_and_class_is_malformed`、`declared_content_path_of_another_identity_is_not_published` |
| 3 | 讀既有 manifest 整檔配置，無上限（`11b6560`） | 設大小上限並以有界串流讀取 | `oversized_manifest_is_malformed_even_if_parseable`、`read_from_stream_round_trips`、`oversized_existing_manifest_is_not_a_valid_declaration` |
| 4 | `Sha256.ofFile` 有繞過 NFS 執行器的 overload（`d9613e1`） | 移除；檔案 digest 只能經執行器 | `every_public_file_digest_entry_requires_the_nfs_executor` |
| 5 | SUCCESS（且 close 失敗）後仍可經緩衝寫進已發布 inode（`32d8c7d`） | Application 持有的外層 stream 以 lifecycle 拒寫，連小寫入也不進 buffer；拒寫不毒化 handle | `writes_after_success_are_rejected_even_if_close_failed` |
| 6 | finalize 第①步 flush 失敗或 timeout 先回 PENDING，重呼才 FAILURE（`4c21884`） | flush 屬於寫入：毒化 handle，該次即 FAILURE(IO) | `flush_timeout_in_finalize_is_immediate_failure_not_pending`、`flush_io_error_in_finalize_is_immediate_failure` |
| 7 | link-key timeout 後舊 link 仍在執行，重試卻判 DECLARATION_EXPIRED 或暫存不在並刪暫存，舊 link 之後落地（`fb128ce`） | timeout 交出 in-flight future；該 identity 的 link 結束前 finalize 一律 PENDING | `timeout_hands_back_the_still_running_operation`、`F1b_link_in_flight_then_declaration_expires_is_pending_until_link_settles`、`F1b_writing_removed_while_link_in_flight_is_pending_until_link_settles` |
| 8 | 同 identity 兩個 handle 的 link 都在飛時只記得後一個；後一個結束即重跑序列、可能回 FAILURE 並刪暫存（`0f7b293`） | 每個 identity 保存一組 future，於 compute 內原子增刪；任一未結束即 PENDING | `F1b_two_links_in_flight_stays_pending_until_every_link_settles` |
| 9 | CI（JDK 21）揭露：槽位以 worker 是否閒置計算，`future.get()` 返回時 worker 尚未回池，緊接的下一個 op 被誤判池滿（`87f58f7`） | 槽位改為 Semaphore permit，於 body 返回的 finally 釋放；拿不到 permit 立即 NfsBusy | `sequential_calls_on_a_single_slot_are_never_busy` |
| 10 | Finalize 已開始但 fsync 尚未確定時仍可再寫，外層 buffer 會接受小寫入 | 明確 `WRITING → FINALIZING → FINALIZED` lifecycle；第一次 Finalize 即在外層 stream 凍結內容 | `writes_are_rejected_after_finalize_starts_even_before_digest_is_fixed`、`writes_after_success_are_rejected_even_if_close_failed` |
| 11 | content_path 的 identity/class 正確但日／時不符 source_ready_at 時仍會依錯路徑發布 | 既有 manifest 讀取後以本 store 的 PathLayout 時區計算完整 expected path，要求字串完全相等 | `declared_content_path_at_wrong_ready_hour_is_not_published` |
| 12 | 固定 manifest schema 仍忽略 unknown fields；link ownership 只用合成 Future 測試 | unknown field malformed；增加 production BoundedNfsExecutor 的 deterministic blocking-link 測試 | `unknown_field_is_malformed`、`retry_keeps_real_timed_out_link_ownership_until_worker_finishes` |

另 `583129f` 同步 system-design HTML 兩版的 finalize 圖說（flush 失敗 → FAILURE；自 fsync 起 timeout → PENDING）。

宣告年齡維持 D53 修／D56 定案的 manifest mtime；本票沒有採用舊報告提出的 source_ready_at 替代建議。已發布內容與宣告不符維持 CONFLICT，library 不刪除正式檔；P03／P06／P07 的 ingest 與事故處理由各自契約驗收。

## 尚未驗收與下游工作

- **執行環境：** Java 21 runtime 尚未重跑；本次是 JDK 27 編譯至 release 21。CI workflow 已存在，但本地修正尚未 push，沒有對應 remote run。
- **P13：** 真實 OS／NFSv3 client／NAS 的 fsync 穩定儲存、hard link、failover、長時間掛起與操作所有權語意。
- **P10：** Policy／容量 WriteGate、Consumer API、指標與 library 打包；完整 F18 整合不在這 98 個測試內。
- **整個 M1** 尚未驗收；本票只涵蓋 core library。
- **P09／P14：** 孤兒暫存檔清理、跨 Node E2E、容量與長時間壓測。
- **既存低優先項：** 大於等於 64 KB 的單次 write 仍是一個 NFS operation；部分 open／close timeout 的 handle 回收依賴 Cleaner；pool 指標由後續整合。這些不因本票開 PR 而視為已解決。
- **已知限制：** link in-flight 紀錄以單一 LocalStore 實例為範圍（同 process 多個 LocalStore 彼此看不到）。

合併前由 Reviewer 核對上述適用邊界，依 ticket 的 AC 確認完成度；不得以測試總數代替需求驗收。

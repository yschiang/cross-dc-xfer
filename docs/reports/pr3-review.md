# PR #3 review（P01 core，head `ec4e8e3`）— 由 opus reviewer 產出，尚未貼到 GitHub

## Review：Approve

前輪 5 個 P1 與 P2 逐點複驗完成。在獨立暫存 checkout 用 `main@<fix>^ + test@<fix>` 重跑每個反例，確認**先紅後綠**（未修改 PR worktree）：

| 前輪 # | 狀態 | 修法 | 實測的紅燈 |
|---|---|---|---|
| 1 SUCCESS 後改寫 | addressed | `WriteHandle.java:57-69` 外層串流 guard + `:77-81 requireWritable()` | `FinalizeRetryTest#writes_after_success_are_rejected_even_if_close_failed` |
| 2 content_path 未綁 identity | addressed | `ManifestCodec.java:90-104` | `FinalizeRecoveryTest#declared_content_path_of_another_identity_is_not_published` |
| 3 link-key ownership | addressed | `NfsTimeoutException.java:22`、`LocalStore.java:43-68`、`WriteHandle.java:131,236-239` | `F1b_two_links_in_flight_stays_pending_until_every_link_settles`；停掉 `WriteHandle.java:131` 後兩題 F1b 同時紅 |
| 4 缺 `size` | partially | `ManifestCodec.java:35-38` | `missing_size` / `null_size` / `non_integer_size` |
| 5 flush 契約 | addressed | `WriteHandle.java:140-144` + design/README 同步 | `flush_timeout_in_finalize_is_immediate_failure_not_pending` |
| 6 NFS 隔離 | partially | `Sha256.java:47` 單一入口、`ManifestCodec.java:24,51` | `every_public_file_digest_entry_requires_the_nfs_executor`、`oversized_existing_manifest_is_not_a_valid_declaration` |

`mvn -o -q -pl gigaxfer-core test` → 82 tests / 0 failures。

**D51 合規確認**：Semaphore 改版沒有放寬所有權。permit 在 worker lambda 的 `finally` 釋放（`BoundedNfsExecutor.java:49-51`），`future.get()` timeout 不 cancel、不釋放，`BoundedNfsExecutorTest:37` 斷言 timeout 後 `inUse()==1`。不變式「body 執行中 ≤ 持有 permit ≤ slots」成立，故排入 pool queue 時必有一條 thread 已離開 body，佇列長度 ≤ slots 且僅為 worker 交接。`87f58f7` 修的是真 bug（在 `87f58f7^` 重現了偽 `NfsBusy`）。

### 非阻擋 follow-up

- **F1（P2）`ManifestCodec.java:79` malformed manifest 回 `FailureReason.IO`。** 損壞/偽造宣告是永久狀態，`IO` 語意是可重試，Application 會對同一 key 無限重試。建議改 `CONFLICT` 或新增 reason。
- **F2（P2）`LocalStore.java:43` `linksInFlight` 無上限、無 eviction。** 只在 `linkInFlight(id)` 被呼叫時清；Application 棄用 handle 後 entry 永久殘留。建議 TTL 或 `Future.isDone()` 後回收。
- **F3（P2）契約文字漂移：`NfsExecutor.java:7`、D51 修、`system-design.md:296` 寫「無佇列」，`BoundedNfsExecutor.java:31` 用了 `LinkedBlockingQueue`。** 語意保住，文字未同步；建議改「無等待佇列；permit 即槽位；pool queue 僅為 worker 交接、長度 ≤ slots」。
- **F4（P2）佈局知識重複。** `ManifestCodec.requireDerivedContentPath()` 硬寫六段路徑，與 `PathLayout.contentDir()` 兩份真相；建議搬進 `PathLayout`，讓 `fromContentPath()` 的 escape check 不再是死碼。
- **F5（P2）`README.md:27` 自相矛盾**：寫 flush 失敗後「不再碰 NFS」，但 `poisoned()` → `cleanupTemps()` 會發兩個 unlink op。改「不再嘗試發布；暫存 best-effort 清除」。
- **F6（P2）未知欄位仍接受（`ManifestCodec.java:32`）**：前輪第 4 點唯一沒做的部分；前向相容機制已是 `schema_version`，wire format 已釘 10 欄。一行 `FAIL_ON_UNKNOWN_PROPERTIES`；若保留，補 design-decisions 一列。
- **F7（P3）`P01-validation.md:76`「PR 尚無 CI」與 `:16`（Actions JDK 21）矛盾**，`583129f` 時代殘留。
- **F8（P3）F18 對照低報**：README 窗口表寫「F18 core 不涵蓋」，`P01-validation.md:42` 又引 `BoundedNfsExecutorTest` + `FinalizeUnderPressureTest` 作證據——那正是 F18 機制。統一為「F18 core 半邊已涵蓋；真實 NAS hang 與 systemd/health 待 P10/P13」。README 窗口表缺 F1 列（crash 場景，歸 P13/P14）。
- **F9（P2）`BoundedNfsExecutor.close()` 用 `shutdownNow()`（`:82`）丟棄佇列任務**，其 `finally` 不執行 → permit 洩漏、`Future` 永不 done → 若是已登記的 link-key，該 identity 永久 PENDING。建議對 `shutdownNow()` 回傳的 Runnable 逐一 cancel。

### P02 交接

rebase 只有 2 檔衝突：`Sha256.java`（P02 的 `hex(byte[])` 緊貼本 PR 刪掉的 `ofFile(Path)`，保留前者、丟棄後者）與 `Sha256Test.java`（檔尾機械式合併）。`NfsExecutor` 介面 zero diff、`NfsTimeoutException(String)` 保留、`BoundedNfsExecutor` 建構子不變，`NfsConfig`、`NfsHealthIndicator`、`NfsTimeoutHealthTest` 不用改。`config` package 無交集。`87f58f7` 順便修掉會讓 `NfsHealthIndicator` 偽報 DOWN 的 worker-idle race。

（本機 `p01-core-finalize-pr` 在 `a94afc2`，落後 origin 的 `ec4e8e3`；本 review 審的是 origin。）

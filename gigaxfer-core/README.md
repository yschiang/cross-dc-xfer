# File sync core

[P01 ticket #2](https://github.com/yschiang/cross-dc-xfer/issues/2) · [驗收紀錄](../docs/validation/P01-validation.md)

Application 端 Storage Access contract 的核心（無 Spring、無 DB）。Spring Boot starter 見 `gigaxfer-library`（P10）。

## 契約（SR-01、D8、D23、§5）

```java
LocalStore store = new LocalStore("P3", new PathLayout(mountRoot, ZoneId.systemDefault()),
    new BoundedNfsExecutor("app", 16, Duration.ofSeconds(30)), gate, Clock.systemUTC());

WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2");   // 可丟 WriteRejectedException / IllegalArgumentException
h.stream().write(bytes);                                          // 可丟 IOException（NFS 池滿或寫入失敗）→ 視為本次交易失敗
FinalizeResult r = h.finalizeWrite();
switch (r) {
    case FinalizeResult.Success s -> commitBusinessTransaction(s.identity());   // 只有這裡可以 commit
    case FinalizeResult.PendingConfirmation p -> retryLater(h);                // 不 commit；重呼 h.finalizeWrite() 直到確定
    case FinalizeResult.Failure f -> failTransaction(f.reason(), f.detail());   // CONFLICT / DECLARATION_EXPIRED → 換 Logical key
}
```

規則：

1. Logical key 對不同內容唯一（含 run id / timestamp）；不得以 `.writing`、`.tmp` 結尾或含 `.manifest`，否則 `beginWrite()` 在碰 NFS 前就丟 `IllegalArgumentException`。
2. `finalizeWrite()` 回 `Success` 才 commit 業務交易；`PendingConfirmation` 重呼同一 handle 的 `finalizeWrite()` 直到確定；`Failure` 視為交易失敗。
3. **Handle 中毒（poisoned）**：`stream().write(...)` 只要失敗，handle 就從此中毒——池滿／timeout 丟 `NfsUnavailableException`（`IOException` 子類），其餘寫入失敗（ENOSPC、EDQUOT、EIO、ESTALE…）原樣丟出該 `IOException`。`finalizeWrite()` 內部第①步的 `stream.flush()` 一樣可能踩到同樣狀況——那一次 `finalizeWrite()` 呼叫仍會回 `PendingConfirmation`（結果未知，不算失敗，SR-04），但 handle 已中毒；**之後每次**再呼叫 `finalizeWrite()` 都會立刻回 `Failure(IO, "stream failed at " + <op>)`，不會再嘗試連 NFS。Application 必須把這視為交易失敗、拋棄此 handle、用新的 `beginWrite()` 重來——這是安全的，因為 commit point（③ link-key）從未被踩到。若 fsync/manifest/link 等純 NFS 操作（不經 `stream()`）本身池滿或 timeout，handle 不會中毒，重呼 `finalizeWrite()` 會照原序列重試（見 Task 10 `FinalizeUnderPressureTest` 第三個案例）。
4. 交易重跑直接 `beginWrite` + `finalizeWrite`，冪等保證不重複；不需先查。
5. `discard()` 只允許在 digest 尚未固定前呼叫，也就是 `finalizeWrite()` 第①步 fsync 尚未成功完成之前（之後丟 `IllegalStateException`）；write 失敗（poisoned）或第①步 fsync 因池滿/timeout 回 `PendingConfirmation`（digest 仍未固定）時仍允許 discard。
6. `close()` 只關通道；即使在 `PendingConfirmation` 後 `close()`，重呼 `finalizeWrite()` 仍會收斂（library 會重新開啟暫存檔完成 fsync）。try-with-resources 可用，但 `PendingConfirmation` 的重試必須用同一個 handle。`close()` 經 NFS 執行器，可能丟 `NfsUnavailableException`；用 try-with-resources 時要注意雙重故障（double fault）：若 `stream().write` 先丟出例外、`close()` 又因池滿/timeout 再丟一次，Java 標準語意（JLS 14.20.3）是把 try 區塊（write）的例外當主要例外拋出，`close()` 的例外被鏈到 `getSuppressed()`——呼叫端若只看主例外型別，可能忽略 close() 那一份診斷資訊（例如 close 當下 NFS 也在池滿），必要時檢查 suppressed exceptions。
7. **`WriteHandle` 單執行緒使用，不可跨執行緒共用**（內部 digest／size／poisoned 旗標都沒有同步）；`PendingConfirmation` 的重試也要在同一執行緒上用同一個 handle。
8. `beginWrite()` 的 `namespace`、`dataClass`、`logicalKey` 都直接成為路徑片段：空字串、以 `.` 開頭（含 `..`）、含 `/` 或 `\0` 一律在碰 NFS 前丟 `IllegalArgumentException`。
9. 暫存寫入超過 24 h 未 Finalize 可能被清道夫中止；宣告後超過 7 天未發布的 key 不可再發布（`DECLARATION_EXPIRED`，`LocalStore.DECLARATION_MAX_AGE`）。

型別參考：

- `FinalizeResult`：`Success(identity, contentPath)` / `Failure(reason, detail)` / `PendingConfirmation(op, detail)`。
- `FailureReason`：`CONFLICT`、`DECLARATION_EXPIRED`、`IO`。
- `WriteRejectedException.Reason`：`REJECTED`、`UNAVAILABLE`、`IO`。

## NFS 佈局

```
<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>              正式內容（存在 = Ready）
<root>/<source>/<ns>/<class>/<yyyy-MM-dd>/<HH>/<key>.<uuid>.writing 暫存
<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest             Source Ready 權威紀錄
<root>/<source>/<ns>/.manifest/<bucket>/<key>.manifest.<uuid>.tmp  暫存
```

`.manifest/` 前綴避免與剛好 3 個 hex 字元的 Data class 撞名；`bucket` 只由 `logicalKey` 的 SHA-256 前 3 hex 字元決定（`PathLayout.bucket`）。目錄日期／小時用 `PathLayout` 建構時指定的時區（預設應為 Node 本地時區；參與同步的所有 Node 須同一時區設定）。

## 測試

`mvn -q -pl gigaxfer-core test`。故障窗口對照 `docs/design/system-design.md` §6（測試方法名以窗口編號開頭）：

| 窗口 | 測試 |
| --- | --- |
| F1b | `FinalizeRecoveryTest.F1b_writing_file_removed_before_link_is_failure_not_pending` |
| F2 | `FinalizeRecoveryTest.F2_link_not_sent_then_retry_publishes` |
| F2b | `FinalizeRecoveryTest.F2b_retry_after_declaration_older_than_7_days_is_expired` |
| F3 | `FinalizeRecoveryTest.F3_link_done_but_reply_lost_then_retry_is_success_without_duplicate` |
| F4 | `FinalizeRecoveryTest.F4_half_written_tmp_from_previous_attempt_is_replaced_not_linked` |
| F5 | `FinalizeRecoveryTest.F5_same_identity_different_content_is_conflict_and_leaves_original` |
| F5b | `FinalizeRecoveryTest.F5b_manifest_link_reply_lost_then_retry_publishes_at_declared_content_path`（`<key>` 位置由 manifest.content_path 決定，宣告不會被換掉） |
| F18 | library 側（P10），core 不涵蓋 |

`D44_published_file_corrupted_then_same_content_retry_is_conflict` 測的是 D44 的「重試路徑重讀 `<key>` 比對 digest」，不對應任何 F 窗口。其餘：`FinalizeRetryTest`（fsync 結果未知後的收斂）、`FinalizeUnderPressureTest`（池滿）、`WriteHandleUnavailableTest`（handle 中毒）、`BeginWriteTest`（命名與 gate 把關）。

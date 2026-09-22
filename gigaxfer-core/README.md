# gigaxfer-core

Application 端 Storage Access contract 的核心（無 Spring、無 DB）。Spring Boot starter 見 `gigaxfer-library`（P10）。

## 契約（SR-01、D8、D23、§5）

```java
LocalStore store = new LocalStore("P3", new PathLayout(mountRoot, ZoneId.systemDefault()),
    new BoundedNfsExecutor("app", 16, Duration.ofSeconds(30)), gate, Clock.systemUTC());

try (WriteHandle h = store.beginWrite("mes", "metrology", "L123-R2")) {  // 可丟 WriteRejectedException / IllegalArgumentException
    h.stream().write(bytes);                                            // 可丟 IOException（NFS 池滿或寫入失敗）→ 視為本次交易失敗
    FinalizeResult r = h.finalizeWrite();
    switch (r) {
        case FinalizeResult.Success s -> commitBusinessTransaction(s.identity());   // 只有這裡可以 commit
        case FinalizeResult.PendingConfirmation p -> retryLater(h);                 // 不 commit；重呼 h.finalizeWrite() 直到確定
        case FinalizeResult.Failure f -> failTransaction(f.reason(), f.detail());   // CONFLICT / DECLARATION_EXPIRED / IO → 換 Logical key 或重來
    }
} // close() 經執行器，可丟 NfsUnavailableException（IOException 子類）
```

規則：

1. Logical key 對不同內容唯一（含 run id / timestamp）；不得以 `.writing`、`.tmp` 結尾或含 `.manifest`，否則 `beginWrite()` 在碰 NFS 前就丟 `IllegalArgumentException`。
2. `finalizeWrite()` 回 `Success` 才 commit 業務交易；`PendingConfirmation` 重呼同一 handle 的 `finalizeWrite()` 直到確定；`Failure` 視為交易失敗。
3. **Handle 中毒（poisoned）**：`stream().write(...)` 若踩到 NFS 池滿或 timeout，該次呼叫直接丟 `NfsUnavailableException`（`IOException` 子類），handle 從此中毒。`finalizeWrite()` 內部第①步的 `stream.flush()` 一樣可能踩到同樣狀況——那一次 `finalizeWrite()` 呼叫仍會回 `PendingConfirmation`（結果未知，不算失敗，SR-04），但 handle 已中毒；**之後每次**再呼叫 `finalizeWrite()` 都會立刻回 `Failure(IO, "stream failed at " + <op>)`，不會再嘗試連 NFS。Application 必須把這視為交易失敗、拋棄此 handle、用新的 `beginWrite()` 重來——這是安全的，因為 commit point（③ link-key）從未被踩到。若 fsync/manifest/link 等純 NFS 操作（不經 `stream()`）本身池滿或 timeout，handle 不會中毒，重呼 `finalizeWrite()` 會照原序列重試（見 Task 10 `FinalizeUnderPressureTest` 第三個案例）。
4. 交易重跑直接 `beginWrite` + `finalizeWrite`，冪等保證不重複；不需先查。
5. `discard()` 只允許在 digest 尚未固定前呼叫，也就是 `finalizeWrite()` 第①步 fsync 尚未成功完成之前（之後丟 `IllegalStateException`）；write 失敗（poisoned）或第①步 fsync 因池滿/timeout 回 `PendingConfirmation`（digest 仍未固定）時仍允許 discard。
6. `close()` 只關通道、不刪檔，且經 NFS 執行器，可能丟 `NfsUnavailableException`；`PendingConfirmation` 後不要 `close()` 再重試。用 try-with-resources 時要注意雙重故障（double fault）：若 `stream().write` 先丟出例外、`close()` 又因池滿/timeout 再丟一次，Java 標準語意（JLS 14.20.3）是把 try 區塊（write）的例外當主要例外拋出，`close()` 的例外被鏈到 `getSuppressed()`——呼叫端若只看主例外型別，可能忽略 close() 那一份診斷資訊（例如 close 當下 NFS 也在池滿），必要時檢查 suppressed exceptions。
7. 暫存寫入超過 24 h 未 Finalize 可能被清道夫中止；宣告後超過 7 天未發布的 key 不可再發布（`DECLARATION_EXPIRED`，`LocalStore.DECLARATION_MAX_AGE`）。

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

`.manifest/` 前綴避免與剛好 3 個 hex 字元的 Data class 撞名；`bucket` 只由 `logicalKey` 的 SHA-256 前 3 hex 字元決定（`PathLayout.bucket`）。目錄日期／小時用 `PathLayout` 建構時指定的時區（預設應為 Node 本地時區；Fab 內所有 Node 須同一時區設定）。

## 測試

`mvn -q -pl gigaxfer-core test`。故障窗口對照 `docs/design/system-design.md` §6：F1b、F2、F2b、F3、F4、F5、F5b、F18（library 側），分別覆蓋於 `FinalizeRecoveryTest`、`FinalizeRetryTest`、`FinalizeUnderPressureTest`、`WriteHandleUnavailableTest`。

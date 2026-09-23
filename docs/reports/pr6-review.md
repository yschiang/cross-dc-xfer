# PR #6 review — WriteHandle lifecycle 修正

> 獨立 review（fresh opus，只讀 PR diff 與 worktree 原始碼），對 HEAD `113f067`。依 goal.md「每個 feature 的固定流程」第 4 步。

本機執行 `mvn -q -pl gigaxfer-core test`（JDK 27 → release 21）：**102 tests，0 failures / 0 errors / 0 skipped**。

## 結論

**Approve with follow-ups** — 三個稽核發現的核心危害都堵住了（失敗的 discard 不再假裝成功、Failure 不再卡在 FINALIZING、link future 會被回收），沒有任何新路徑能讓 handle 發布它不該發布的內容；剩下的是 README 規則 2 在 `discard()` 之後仍會被打破、以及失敗原因字串誤報成 stream 中毒這類收尾問題。

附帶一提，`verifyPublished` 把 `cleanupTemps()` 移到 digest 檢查之後是**淨改善**：`digest-key` 池滿／timeout 時現在回 `PendingConfirmation` 而暫存還在，重呼才有東西可以 link；改動前是先刪暫存再讀 digest，一旦 digest 那步 timeout 就留下一個「Pending 但來源已被自己刪掉」的 handle。

## 必修（merge 前）

（無）

## 建議（follow-up 可）

1. **`discard()` 會抹掉已存起來的終態 Failure，讓「重試不會改變已回報的結論」再次不成立**
   - `gigaxfer-core/src/main/java/com/gigaxfer/core/store/WriteHandle.java:130`（無條件 `lifecycle = DISCARDED`）＋ `:149-151`（DISCARDED 分支回一個新的 Failure）。
   - 失敗場景：`finalizeWrite()` 回 `Failure(CONFLICT, ...)` → 依 README 規則 5 合法地呼叫 `discard()`（新測試 `failure_is_terminal_and_discardable` 正好做了這件事）→ 再呼一次 `finalizeWrite()`，拿到的是 `Failure(IO, "handle discarded")`。reason 從 `CONFLICT` 變成 `IO`，正是本 PR 宣稱修掉的 (b)「retry 會改變已回報結論」症狀，也直接牴觸新加的 `gigaxfer-core/README.md:26` 規則 2「同一 handle 之後每次 `finalizeWrite()` 都原樣回第一次的 `Failure`」。做決策用 reason 分流（CONFLICT → 換 key）的呼叫端會被誤導。
   - 建議修法：在 `finalizeWrite()` 的檢查最前面加一行 `if (failure != null) return failure;`（放在 FAILED／DISCARDED 兩個分支之前），或在 `discard()` 成功後只有 `failure == null` 才寫入 `DISCARDED`。順手在 `WriteHandleLifecycleTest.java:75` 的 `b.discard()` 之後補一句 `assertThat(b.finalizeWrite()).isSameAs(first)`。

2. **discard 失敗與 write 失敗共用 FAILED＋`failedOp`，導致失敗原因被誤報成 stream 中毒**
   - `WriteHandle.java:126-127`（discard 的 catch 只設 `lifecycle`／`failedOp`，不設 `failure`）＋ `:278-280`（`poisoned()` 硬編 `"stream failed at " + failedOp`）。
   - 失敗場景：`discard()` 因池滿 timeout → 下一次 `finalizeWrite()` 回 `Failure(IO, "stream failed at discard-writing")`，`requireWritable()` 也丟 `"handle failed: stream failed at discard-writing"`。但 `README.md:27` 規則 3 把 `"stream failed at <op>"` 明確定義為 `stream().write(...)` 造成的中毒，而這個 handle 的 stream 從頭到尾沒失敗過；靠這個字串做告警／分類的運維會拿到假陽性。另外若 handle 先前已因真正的 write 失敗中毒（`failedOp = "write"`），discard 失敗會把它覆寫掉，原始中毒原因永久遺失。
   - 建議修法：在 `discard()` 的 catch 裡直接寫入 `failure = new FinalizeResult.Failure(FailureReason.IO, "discard delete unresolved at " + e.op())` 再設 `lifecycle = FAILED`；`failedOp` 只保留給 stream 路徑。

3. **`discard()` 在刪除完成前不再標記任何狀態，NFS op 全程沒有守衛**
   - `WriteHandle.java:113-131`。改動前 `DISCARDED` 在同步區內立刻寫入；現在整段 `store.nfs.run("discard-writing", ...)`（最長可達執行器 timeout，正式設定 30 s）期間 `lifecycle` 仍是 `WRITING`。
   - 失敗場景：只有跨執行緒才會踩到——同時間另一個執行緒呼 `finalizeWrite()` 會正常走完並發布呼叫端已要求放棄的內容，或 `stream().write` 仍被接受。`README.md:31` 規則 7 已禁止跨執行緒共用，所以這在契約內不可達；但「檢查完就放掉鎖、再做長時間 IO」把原本零長度的窗口拉成數十秒，值得留一手。
   - 建議修法：在同步區內設一個獨立的 `discarding` 旗標（不動 `Lifecycle` enum），`requireWritable()` 與 `finalizeWrite()` 看到它就拒絕；或加一個 `DISCARDING` 狀態，刪除完成後再轉 `DISCARDED` / `FAILED`。

4. **`discard_retries_delete_after_nfs_failure` 沒有真的驗到「discard 會再刪一次」**
   - `gigaxfer-core/src/test/java/com/gigaxfer/core/store/WriteHandleLifecycleTest.java:41-53`，關鍵在第 48 行。
   - 原因：第 48 行的 `h.finalizeWrite()` 自己就會走 `poisoned()` → `fail()` → `cleanupTemps()` → `unlink-writing`（`dropBefore` 是一次性的，此時已被第一次 discard 消耗掉），暫存在這裡就被刪光了。因此第 51 行的 `doesNotExist()` 在第 50 行的 `discard()` 完全不刪任何東西的實作下也會通過。這個測試在 pre-fix 上確實會失敗（pre-fix 的 `finalizeWrite()` 對 DISCARDED handle 直接 return，不做 cleanup，所以檔案還在、第 51 行紅燈），但它驗到的是「finalizeWrite 順手清掉」而不是註解宣稱的「重呼 discard 再刪一次」。
   - 建議修法：拿掉第 48 行的 `finalizeWrite()`，或另開一個案例：失敗的 `discard()` → 直接 `discard()` → 斷言檔案消失；`finalizeWrite()` 不發布的斷言留在現有案例裡。

5. **`linkSent()` 的全掃描只在「下一次 link-key timeout」才觸發**
   - `gigaxfer-core/src/main/java/com/gigaxfer/core/store/LocalStore.java:57`。
   - 並發與成本都沒問題：掃描走的是 `linkInFlight()` 的 `computeIfPresent`，清空集合與移除 key 仍是同一個原子動作，沒有破壞 `LocalStore.java:38` 的不變式；`keySet()` 是弱一致迭代，`compute` 不在 mapping function 內巢狀呼叫（CHM 禁止的用法），也不會死鎖。因為每次掃描都會把所有已結束的 future 清光，map 大小穩定在「真正還在飛的 link 數」量級，O(N²) 不會發生。
   - 失敗場景：NAS 恢復正常、不再有任何 timeout 之後，最後一批被放棄的 identity 會一直留在 map 裡（每筆只是一個 `FileIdentity` ＋ 已完成的 `FutureTask`，callable 已被釋放，量很小）。也就是「永久洩漏」只對「還會再 timeout」的 store 算修掉。
   - 建議修法：若要收乾淨，`beginWrite()` 成功後順便掃一次即可（同樣罕見路徑、同樣成本）；否則把這個前提寫進 `LocalStore.java:55` 的註解。

6. **README 規則 5 少講一件會讓讀者意外的事**
   - `gigaxfer-core/README.md:29` 說 discard 刪除失敗後「暫存可能仍在……重呼 `discard()` 會再刪一次」，但沒說在這個狀態下呼叫 `finalizeWrite()` 也會經 `fail()` → `cleanupTemps()` 發出 `unlink-writing`，等於由 `finalizeWrite()` 把 discard 做完。建議補一句，否則照字面讀會以為只有 `discard()` 會動暫存。
   - 同時 `README.md:31` 規則 7 的「lifecycle 狀態有同步」現在只涵蓋 `finalizeWrite()` 的 check-then-set：`discard()`（`WriteHandle.java:126`、`:130`）、`success()`（`:322`）與 `ChannelStream`（`:369`、`:375`）都在同步區外寫 `lifecycle`。靠 `volatile` 在單執行緒契約下仍安全，但措辭可以收斂成「狀態轉換的判定有同步」。

## 三項修正核對

- **(a) discard 提前標記 DISCARDED** — ✅ fixed。`WriteHandle.java:130` 把 `DISCARDED` 移到刪除成功之後，失敗轉 `FAILED`，`finalizeWrite()` 在該狀態只回 Failure、絕不發布，`discard()` 可重呼；in-flight 的刪除 task 即使稍後才完成也踩不到 commit point。新測試在 pre-fix 上會於 `WriteHandleLifecycleTest.java:51` 失敗（pre-fix 第二次 `discard()` 是 no-op，`.writing` 仍在）。扣分只在失敗字串誤報（建議 2）與測試驗錯機制（建議 4）。
- **(b) Failure 停在 FINALIZING** — ⚠️ partially。所有 Failure 路徑改走 `fail()`，狀態進 `FAILED`、結果存下重播、`discard()` 不再丟 `IllegalStateException`（pre-fix 會在 `WriteHandleLifecycleTest.java:73` 的 `isSameAs` 與 `:75` 的 `b.discard()` 兩處失敗，符合宣稱）；但 `discard()` 之後 `lifecycle` 被改寫成 `DISCARDED`，下一次 `finalizeWrite()` 又回一個不同的 `Failure(IO, "handle discarded")`，README 規則 2 的「原樣回第一次的 Failure」仍可被合法操作打破（建議 1）。
- **(c) linksInFlight 只在同 identity 重呼時才剪枝** — ✅ fixed（有前提）。`linkSent()` 的全掃描會回收所有已結束的 future，放棄的 Pending handle 不再永久佔位；`inFlightIdentities()` 只是測試用 package-private 讀取器，不影響正式路徑。第三個測試在 pre-fix 上是「編不過」（新增的 accessor），真正的行為斷言是 `WriteHandleLifecycleTest.java:93` 的 1 vs 2，沒有掃描時會是 2。前提是後續還會發生 link-key timeout，否則靜默期最後一批仍留著（建議 5）。


---

## Scoped re-review（sonnet，對 fix commit `e98d951`）

# PR #6 fix commit — 再審（scoped re-review，僅核對 follow-up 1/2/4/5/6）

1. ✅ `finalizeWrite()`（WriteHandle.java:154）最前面加 `if (failure != null) return failure;`，位置在 `lifecycle == FAILED`（:155）與 `lifecycle == DISCARDED`（:156-158）兩個分支之前。測試 `failure_is_terminal_and_discardable` 在 `b.discard()`（:73）之後補上 `assertThat(b.finalizeWrite()).isSameAs(first)`（:75），成功驗到 discard 之後 stored Failure 物件不變、不被 replay 成新的。

2. ✅ discard 的 catch（WriteHandle.java:126-133）只在 `failure == null` 時才寫入：`failedOp != null`（先前已被 stream 中毒）沿用 `"stream failed at " + failedOp`（與 `poisoned()` 同一字串，不誤報成 discard 原因）；否則才寫 discard 專屬的 `"discard delete unresolved at " + e.op()`。已存在的 Failure（不論來自 `fail()` 或先前的 discard 失敗）都不會被覆蓋，因為外層有 `if (failure == null)` 守衛。對「stream-poisoned handle（lifecycle FAILED, failure 仍是 null）」而言，第一次 `finalizeWrite()` 行為沒變：`failure != null` 檢查不會命中，照舊落到 `lifecycle == FAILED` 分支呼叫 `poisoned()`，與修正前的 `failure != null ? failure : poisoned()` 邏輯等價，只是拆成兩行。

4. ✅ `discard_retries_delete_after_nfs_failure`（WriteHandleLifecycleTest.java:41-56）在第一次失敗 discard 後呼叫 `finalizeWrite()`，明確斷言 `h.writingPath()).exists()`（:50），證明 `finalizeWrite()` 沒有代為清暫存；接著才呼叫第二次 `discard()` 並斷言檔案消失（:52-53）。`FaultInjectingNfs.dropBefore` 是一次性注入（`before.remove(op)` 用過即刪，見 FaultInjectingNfs.java:40-42、77），第二次 `discard-writing` 呼叫會真的落到底層執行刪除，不會再被注入失敗。因果鏈完整，測試確實驗到「第二次 discard() 才是真正刪檔的人」。

5. ⚠️ `sweepFinishedLinks()`（LocalStore.java:59-61）沿用與原本 `linkSent()` 相同的 `linkInFlight()` / `computeIfPresent` 機制逐 key 操作，並發與成本跟修正前一致；`beginWrite()`（:90）在建立新 handle 前呼叫它，程式碼本身沒問題。但新增的測試片段（WriteHandleLifecycleTest.java:102-103，`store.beginWrite("L3").discard()` 後斷言 `inFlightIdentities()` 為 0）**沒有實際證明「不需要進一步 timeout 也能回收」**：追蹤整個測試時序會發現，在跑到這兩行之前，L2 的 map entry 已經在 `other.finalizeWrite()` 第二次呼叫（:101）內，經由 `finalizeWrite()` 本來就有、對自身 identity 的 `store.linkInFlight(id)` 自檢（PR #6 之前就存在的機制，非本次新增）清空了；同理 L1 的 entry 在此之前也已經被 `linkSent()` 原本就有的全掃描（呼叫 `other.finalizeWrite()` 第一次觸發 link-key timeout 時）清掉。也就是說，即使把 `beginWrite()` 裡新加的 `sweepFinishedLinks()` 呼叫拿掉，這段測試依然會通過（到 :103 之前 map 早就是空的），測試沒有區分「有無此修正」。修法本身可信，但測試證據不足以支撐 item 5 的 claim。

6. ✅ README 規則 5（README.md:29）補充「`finalizeWrite()` 不會代為清暫存，只回 `Failure(IO, "discard delete unresolved at …")`」與「已回 `Failure` 的 handle 在 `discard()` 之後重呼 `finalizeWrite()` 仍回原本那個 `Failure`」，兩句都與程式碼行為一致（分別對應 WriteHandle.java:154 的提前回傳、discard() 的 catch 不代清暫存）。規則 7（README.md:31）措辭從「lifecycle 狀態有同步」改成「狀態轉換的判定有同步」，符合現況：`discard()` 的 `lifecycle = Lifecycle.DISCARDED`（WriteHandle.java:136）、`success()`（:329）等寫入確實都在 `synchronized(stream)` 區外，只有轉換的判定（check）在區內。

## 新問題

（無：沒有發現會讓 handle 誤發布，或影響正常 Success 路徑的新迴歸。討論過的唯一疑點是 item 5 的測試沒有實際區分修正前後，已計入該項的 ⚠️，不算獨立新缺陷。）

## 結論

NOT CLEAN — 5 個 follow-up 中有 4 個（1/2/4/6）確認修好且測試到位；item 5 的程式碼修正本身正確、未發現新缺陷，但新增的測試斷言在此情境下即使拿掉 `beginWrite()` 的 sweep 呼叫也會通過，並未真正證明「不需進一步 timeout 也能回收」，建議補一個不依賴 L2 自身 retry 的獨立測試（例如：讓最後一個 identity 的 future 完成後，不再對它呼叫 `finalizeWrite()`，直接靠下一次 `beginWrite()` 觀察 map 縮小）。


> 處理：唯一 finding（beginWrite 回收斷言為空）已在下一 commit 改成獨立測試 `completed_link_futures_are_reclaimed_on_begin_write`，並以移除回收呼叫的 mutation 確認會失敗。

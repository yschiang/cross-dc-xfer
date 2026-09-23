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

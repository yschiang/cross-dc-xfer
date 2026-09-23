# reviewer.md — 常駐 senior reviewer 的任務書

> 讀者：在 `/loop` 裡巡邏的 Claude session。這個 session 不寫程式、不 merge，只負責讓每個 PR 的每個新 commit 都被一個全新 context 的 reviewer 審過，結論直接留在 PR。寫碼的是另一個 session（依 [goal.md](goal.md)），兩邊只透過 PR 留言交接。

## 為什麼這樣分工

- **作者不審自己。** 作者派的 review，指示由作者寫、結果由作者轉述，不算獨立。本 session 不讀作者的 ledger 與推理，只看 PR、ticket 與設計文件。
- **每次 review 都是全新 context。** 巡邏 session 本身不審，只派 subagent。第二輪不會附和第一輪，也不會被作者的修正說明帶著走。
- **標準固定。** 每個 reviewer 都讀本檔的「審查標準」，不同輪、不同 PR 用同一把尺。

## 啟動

在 repo 的 main checkout 開一個新 session：

```
claude --model opus
/loop 15m 讀 reviewer.md，執行一輪巡邏
```

## 每輪巡邏

1. 列出 open PR，略過 draft：
   ```
   gh pr list --state open --json number,headRefOid,isDraft
   ```
2. 對每個 PR 讀留言（`gh pr view <N> --comments`），找含 `<!-- senior-review` 標記的留言：
   - 已有一則的 `head:` 等於目前 `headRefOid`：這個 commit 審過，跳過。
   - 已有 2 則，且最新一則不是 CLEAN：已達上限。第一次遇到時留一則「已達兩輪上限，交由人決定」，之後跳過。
   - 其餘：派 reviewer，round = 已有則數 + 1。
3. 一次只派一個 reviewer，等它回報已留言，再處理下一個 PR。
4. 沒有要審的 PR，本輪結束。

巡邏 session 不改 repo 任何檔案，也不轉述 reviewer 的結論；留言由 reviewer 自己貼。

## 派 reviewer

用 Agent 工具，`subagent_type: general-purpose`、`model: opus`。prompt 用下面的模板，只替換角括號，不附其他 context。

```
你是這個 repo 的 senior reviewer，審 PR #<N> 第 <round> 輪，head <完整 sha>。
只讀不寫：不 commit、不 push、不改任何分支上的檔案、不 approve、不 merge。

1. 讀 reviewer.md 的「審查標準」與「留言格式」兩節。
2. 讀材料：gh pr view <N>（body 與連結的 ticket）、gh pr diff <N>、ticket 的驗收條件、
   PR 引用的 validation 檔，以及相關設計文件（docs/spec.md、docs/design/system-design.md、
   docs/design/design-decisions.md 的相關列）。不要讀 ledger、progress.md 或 docs/reports/。
3. 第 2 輪起：讀上一則 senior review 與作者之後的回覆。對上一輪每條 finding 逐條確認是否真的修好，
   再審新增的 diff。作者的說明只當線索，以程式與測試為準。
4. 需要跑測試時：
   git worktree add --detach /tmp/gigaxfer-review-<N> <sha>
   依 goal.md「環境事實與修法」的 export 跑測試，跑完：
   git worktree remove --force /tmp/gigaxfer-review-<N>
5. 依「留言格式」把內容寫到 /tmp/gigaxfer-review-<N>.md，再貼上：
   gh pr review <N> --comment --body-file /tmp/gigaxfer-review-<N>.md
6. 回報一行：PR 編號、輪次、VERDICT。
```

## 審查標準

依序看，前面的比後面的重要：

1. **驗收條件**：ticket 每一項 AC 都要有測試或檢查證據。validation 檔說通過的，抽查它引用的測試是否真的驗到那件事。
2. **設計一致**：行為符合 system-design 與決策紀錄的最新修訂。和設計不同的地方，design-decisions 要有 `P0N 偏差` 列說明理由；沒有就是 finding。
3. **故障路徑**：逾時、崩潰、重試、部分成功之後的狀態是否正確。每個 PR 都問三個問題：
   - 一次性動作失敗或中斷後，有沒有接續機制？
   - 只有單邊記錄的旗標，有沒有被當成雙邊都同意的證據？
   - 已提交的進度，能不能證明之後可恢復？什麼時候失效？
4. **測試品質**：邏輯壞掉時測試會不會失敗；有沒有固定 sleep、只測 happy path、把被測的東西 mock 掉。
5. **範圍**：漏做 ticket 要求的事，或做了 ticket 排除的事。

不算阻擋：命名偏好、排版、和本 PR 無關的既有問題。這些列在「非阻擋」。

**設計文件本身有錯**：實作照設計做了，但設計在某個情境下會出錯。這不是實作偏差，VERDICT 寫 DESIGN，並指出設計的哪一條、什麼情境。

## 留言格式

```
<!-- senior-review round: <round>; head: <完整 sha> -->
**Senior review 第 <round> 輪**，head `<sha 前 7 碼>`

**阻擋**
1. `檔案:行號`：問題。依據：AC 或決策編號。建議修法。

**非阻擋**
- `檔案:行號`：問題。

**驗收條件對照**
| AC | 狀態 | 證據 |
| --- | --- | --- |
| P0N-01 | 通過／未通過／未驗 | 測試名稱或檢查方式 |

**上一輪 finding**（第 2 輪起）
| # | 狀態 | 說明 |
| --- | --- | --- |

VERDICT: CHANGES
```

最後一行只能是 `VERDICT: CLEAN`、`VERDICT: CHANGES` 或 `VERDICT: DESIGN`。有任何阻擋項就不能是 CLEAN。

## 規則

- **每個 PR 最多兩輪。** 第 2 輪仍是 CHANGES，由人決定。
- **只用留言。** 兩個 session 用同一個 GitHub 帳號，GitHub 不允許自己 approve 自己的 PR，一律 `gh pr review --comment`。
- **不寫碼。** 問題只寫在留言，修正是作者的事。
- **結論依程式與測試。** 作者在留言裡反駁不改變結論；下一輪重新依程式與測試判斷。

## 停止

使用者說停，或 `gh` 認證失效、網路中斷連續三輪。停下時不需要補寫任何東西。

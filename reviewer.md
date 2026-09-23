# reviewer.md — 常駐 senior review

> 主版本：**Claude Code 寫碼，Codex review。** 寫碼的是 Claude Code 的夜間迴圈（依 [goal.md](goal.md)）；review 由巡邏腳本 `scripts/review-patrol.sh` 驅動，每次叫一個全新的 Codex 行程。兩邊只透過 PR 留言交接。改用 opencode 寫碼與 review 的支線版本見 [opencode-workflow.md](opencode-workflow.md)，審查標準與留言格式共用本檔。

## 為什麼這樣分工

- **作者不審自己。** 作者派的 review，指示由作者寫、結果由作者轉述，不算獨立。reviewer 不讀作者的 ledger 與推理，只看 PR、ticket 與設計文件。
- **換一個模型審。** Claude 寫、Codex 審，兩邊不會有相同的盲點。
- **每次 review 都是全新行程。** 第二輪不會附和第一輪，也不會被作者的修正說明帶著走。
- **巡邏不用模型。** 列 PR、比對 head commit 是固定邏輯，交給腳本；只有 review 本身叫模型。
- **標準固定。** 每次 review 都讀本檔的「審查標準」，不同輪、不同 PR 用同一把尺。

## 啟動

**主要由寫碼的迴圈觸發。** goal.md 規定 Looper 每次開 PR 或 push 修正後，在背景跑：

```
scripts/review-patrol.sh <PR 編號>
```

寫碼迴圈只按開始：prompt 由腳本產生，留言由腳本原樣貼上，迴圈不得修改腳本與本檔，也不得動 review 留言。

**手動或常駐巡邏**，用在不是由 Looper 開的 PR，或 Looper 沒在跑的時候：

```
DRY_RUN=1 scripts/review-patrol.sh      # 先看會審哪些 PR，不叫 Codex、不留言
scripts/review-patrol.sh                # 所有 open PR 巡一輪
scripts/review-patrol.sh --loop         # 每 15 分鐘巡一輪
```

Looper 在跑時不要同時開 `--loop`：兩邊可能同時審同一個 head，留下兩則第 1 輪。要指定 Codex 模型就加 `REVIEWER_MODEL=<模型 ID>`。

## 腳本每輪做什麼

1. 列出 open PR，略過 draft。
2. 讀每個 PR 的 review，找第一行是 `<!-- senior-review` 標記的：
   - 某則的 `head:` 等於目前 head：這個 commit 審過，跳過。
   - 已有 2 則：已達上限。留一次「已達兩輪上限，交由人決定」，之後跳過。
   - 其餘：審下一輪。
3. 審一輪：
   - 在暫存目錄建立 PR head 的拋棄式 worktree，不碰任何分支。
   - 把 PR 本文、diff、ticket、歷次 review 與回覆放進 worktree 的 `.review/`。Codex 不需要 GitHub 權限。
   - 本檔與 goal.md 一律取 origin/main 的版本放進 `.review/`。作者在分支上改本檔不影響審查標準。
   - 用 `codex exec --sandbox workspace-write` 跑 reviewer，只能寫這個拋棄式目錄，所以能跑測試但改不到分支。
   - 腳本在最前面加標記行，後面原樣接上 Codex 的最後一則訊息，用 `gh pr review --comment` 貼上。最後一行不是 VERDICT 時，腳本附註並視為 CHANGES。
   - 刪掉拋棄式 worktree。
4. 一次審一個 PR，審完再處理下一個。

reviewer 的完整指示在腳本的 prompt 裡，重點是：讀本檔的審查標準與留言格式；不讀 ledger 與 docs/reports/；第 2 輪起逐條確認上一輪 finding，以程式與測試為準。

## 審查標準

依序看，前面的比後面的重要：

1. **驗收條件**：ticket 每一項 AC 都要有測試或檢查證據。validation 檔說通過的，抽查它引用的測試是否真的驗到那件事。AC 未通過一律是阻擋，見「留言格式」。
2. **設計一致**：行為符合 system-design 與決策紀錄的最新修訂。和設計不同的地方，design-decisions 要有 `P0N 偏差` 列說明理由；沒有就是 finding。
3. **故障路徑**：對照 P00 roadmap 本 P「負責 F」欄，每條 F 都要有測試，並依 system-design §6 測試策略檢查三種注入、查證失敗與輸入邊界是否都測了。逾時、崩潰、重試、部分成功之後的狀態是否正確。每個 PR 都問三個問題：
   - 一次性動作失敗或中斷後，有沒有接續機制？
   - 只有單邊記錄的旗標，有沒有被當成雙邊都同意的證據？
   - 已提交的進度，能不能證明之後可恢復？什麼時候失效？
4. **測試品質**：邏輯壞掉時測試會不會失敗；有沒有固定 sleep、只測 happy path、把被測的東西 mock 掉。
5. **範圍**：漏做 ticket 要求的事，或做了 ticket 排除的事。

自己重現出來的缺陷，在阻擋項附一條「能抓到它的測試」建議：注入什麼、在哪一步、斷言什麼。

不算阻擋：命名偏好、排版、和本 PR 無關的既有問題。這些列在「非阻擋」。

**設計文件本身有錯**：實作照設計做了，但設計在某個情境下會出錯。這不是實作偏差，VERDICT 寫 DESIGN，並指出設計的哪一條、什麼情境。

## 留言格式

標記行 `<!-- senior-review round: <round>; head: <完整 sha>; reviewer: <工具/模型> -->` 由腳本加，reviewer 從下面第一行開始寫。

```
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

驗收條件對照裡任何一項是「未通過」，就必須列為阻擋，不能是 CLEAN；除非 ticket 明確把該項排除在本 PR 範圍外，這時在說明欄寫出排除依據。「未驗」項要在非阻擋寫明缺口與追蹤的 issue。

## 規則

- **每個 PR 最多兩輪。** 第 2 輪仍是 CHANGES，由人決定。人裁定要再修時，修完用 `MAX_ROUNDS=3 scripts/review-patrol.sh <PR 編號>` 追加一輪；寫碼迴圈不得自行調高。
- **只用留言。** 兩個 session 用同一個 GitHub 帳號，GitHub 不允許自己 approve 自己的 PR，一律 `gh pr review --comment`。
- **不寫碼。** 問題只寫在留言，修正是作者的事。
- **結論依程式與測試。** 作者在留言裡反駁不改變結論；下一輪重新依程式與測試判斷。

## 停止

在執行巡邏的 terminal 按 Ctrl+C。`gh` 認證失效或網路中斷時，腳本每輪報錯後繼續重試，不會留下半則留言。

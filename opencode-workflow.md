# opencode-workflow.md — 支線版本：opencode 寫碼與 review

> 主版本是 Claude Code 寫碼、Codex review（[goal.md](goal.md)、[reviewer.md](reviewer.md)）。本檔是支線：**opencode 上用 terra 5.6 寫碼，用 sol review。** 任務內容、固定流程、審查標準與留言格式都沿用主版本，本檔只寫要替換的部分。

## 先確認的事

- **本機還沒安裝 opencode。** 下面的 `opencode run -m` 用法照 opencode 的文件寫，沒在這台機器驗證過。第一次先手動跑一次，確認兩件事：
  - `-m` 要填的完整模型 ID。terra 5.6 與 sol 的 ID 依你的 opencode provider 設定，填進下面兩個變數。
  - `opencode run` 的 stdout 只有最後一則回覆。巡邏腳本會把 stdout 原樣貼成 PR 留言，如果混了工具紀錄，要改用只輸出最後訊息的方式。
- **權限**：opencode 沒有 Codex 那種唯讀沙箱，靠它的權限設定。reviewer 只允許讀檔、`git` 讀取類指令與 `mvn`，禁止 `git push` 與 `gh`。作者需要 `git`、`gh`、`mvn`。
- **兩個版本不要同時跑在同一批分支上。** 兩邊都會寫 feature 分支、都會審 open PR，會互相覆蓋。一晚擇一，或讓兩邊各做不同的 P0N。

```bash
AUTHOR_MODEL='<terra 5.6 的模型 ID>'
REVIEWER_MODEL='<sol 的模型 ID>'
```

## Review：sol

巡邏腳本和主版本共用，只換 reviewer：

```bash
REVIEWER=opencode REVIEWER_MODEL="$REVIEWER_MODEL" DRY_RUN=1 scripts/review-patrol.sh   # 先看會審哪些 PR
REVIEWER=opencode REVIEWER_MODEL="$REVIEWER_MODEL" scripts/review-patrol.sh --loop
```

每次 review 仍是全新的 opencode 行程，跑在拋棄式 worktree 裡，留言標記會記 `reviewer: opencode/<模型 ID>`。

## 寫碼：terra 5.6

opencode 沒有 `/loop`，由外層 shell 迴圈每輪叫一次全新的 `opencode run`，靠 ledger 接續：

```bash
while true; do
  opencode run -m "$AUTHOR_MODEL" \
    "讀 goal.md，並依 opencode-workflow.md「goal.md 在 opencode 上的替換」一節替換後，執行一輪。"
  tail -1 docs/reports/opencode-ledger.md 2>/dev/null | grep -qx 'LOOP: STOP' && break
  sleep 1200
done
```

## goal.md 在 opencode 上的替換

goal.md 其餘內容照做：目標與優先序、每個 feature 的固定流程、每輪先處理 review 留言、停止條件、早晨報告。

| goal.md 寫的 | opencode 上改成 |
| --- | --- |
| `/loop`、`ScheduleWakeup` | 外層 shell 迴圈，每輪一次 `opencode run`，間隔 20 分鐘。一輪做完一個 task，或處理完 review 留言，就結束本輪 |
| `superpowers:*` skill、`scripts/sdd-workspace`、`EnterWorktree` | 不可用。自己逐 task 做：讀 plan 的下一個 task → 寫測試 → 實作 → 跑測試 → commit。worktree 用 `git worktree add .worktrees/<branch> -b <branch>` |
| ledger `<workspace>/progress.md` | `docs/reports/opencode-ledger.md`。每完成一個 task 記一行 `Task N: complete <commit>`，裁定記 `Ruling:` 行。每輪開始先讀它與 `git log` |
| 停止 loop | 停止條件成立時，寫好早晨報告，在 ledger 最後一行寫 `LOOP: STOP`，外層迴圈就會結束 |
| 模型分配表（sonnet／opus subagent） | 全部用 terra 5.6，不派 subagent |
| whole-branch review（opus） | 作者自查：開 PR 前重讀整個 diff，逐條對照 ticket 的驗收條件。正式 review 仍由 sol 負責 |
| Commit 結尾的 `Co-Authored-By: Claude …` | 不加 |

# goal.md — 夜間自主迴圈的任務書

> 讀者：在 `/loop` 裡接手的 Claude。使用者已就寢，**不會回答任何問題**；本檔已預先裁定所有需要人決定的事。遇到本檔未涵蓋的抉擇：依 `docs/spec.md` → `docs/design/system-design.md` → 計畫檔的順序裁定，寫進 ledger 的 `Ruling:` 行，繼續做。停止條件見下文。

## 今晚交付目標

完成 **P01 → P02 → P03**，驗證 M1 的 Source 路徑：App Finalize 發布 → sync service 掃描 → identity 與全部 Required target 義務可靠入庫。每個 P0N 交付為一個 **stacked PR**（見「每個 feature 的固定流程」），供早晨 review 與 merge。

本夜不宣稱完成整個 M1：跨 Node 交付仍需 P04／P05，library 整合需 P10，生命週期與完整驗收另含 P09／P13／P14。P13 腳本列為餘裕項，不排在 P03 前面。

## 目標（依優先序，做完一項才做下一項）

1. **確認並完成 P01**：已完成。PR #3 已 merge 到 main（`4cddff5`），follow-up 在 issue #4。不再動 P01。
2. **確認並完成 P02**：優先續用 `p02-sync-service-skeleton` 分支上的 `docs/superpowers/plans/P02-sync-service-skeleton.md` 與進度；缺 plan 才用 `superpowers:writing-plans` 補齊。範圍依 P00 的 P02 列，依據 D17、D45、D30 修 5/6、D24 修、D14 修 2、D34、D34 修。Config 資料模型放在 core（ticket #1、P00 模組表）。依 ticket https://github.com/yschiang/cross-dc-xfer/issues/1 的 12 項驗收條件與 plan 的「修正 tasks」節續行（1R → 2R → 3R → 4 → 5 → 6 → 7）；完成實作、測試、整分支審查與 `docs/validation/P02-validation.md`，AC 逐項有證據才算 P02 通過；通過後走「每個 feature 的固定流程」第 3 步（PR base = main）。
3. **（P02 PR 開好後才開始）寫 P03 plan 並完成實作**：先依固定流程第 1 步開 P03 ticket（AC 清單自 P00 的 P03 列與下列決策導出）。在 `p03-ingest` branch／worktree 工作，基於 P02 PR 的 HEAD；已有該分支則先查狀態並續行。計畫存 `docs/superpowers/plans/P03-ingest.md`，完整範圍依 P00 的 P03 列與 D6、D10 修、D36、D50、D53、D56 ③。完成掃描、原子 ingest、全量對帳與補缺列、整分支審查、`docs/validation/P03-validation.md`，再走固定流程第 3 步（PR base = `p02-sync-service-skeleton`；P02 merge 後用 `gh pr edit <P03 PR> --base main` 改指 main，分支保留供人學習）。
4. **驗證 P01–P03 整合路徑**：以本機測試環境串起真實 Finalize 產物、掃描與測試 DB。驗證 identity 與全部義務同交易、commit 後才更新快取；失敗回滾後重掃可補回、重掃不重複建列、既有 identity 缺義務能補齊。所用 DB／檔案系統與尚未做的 Oracle／NAS 驗收須列在報告。
5. **有餘裕才寫 P13 任務書／驗收腳本**：不得延誤 P03 與整合測試；**不執行**任何真實 NAS 操作。

每完成一項，更新 `docs/reports/overnight-report.md`（見「早晨報告」）。

開始前讀 `git worktree list`、各分支的 `git status`／`git log` 與 ledger；plan 可能只存在工作分支，不能因 main 沒有檔案就重建。已有成果不覆蓋、不重做。測試模組名稱依各分支 `pom.xml`，不因文件名稱調整而改專案命名。

## 每個 feature 的固定流程

每個 P0N 都走這四步，全部自動化；**唯一不自動化的是 merge**。review 由本迴圈觸發、但不由本迴圈執行：每次 push 後跑 `scripts/review-patrol.sh <PR 編號>`，腳本產生 prompt、叫全新的 Codex 行程審、把結論原樣貼成 PR 留言（見 [reviewer.md](reviewer.md)）。本迴圈只按開始，不寫 review 指示、不轉述結果。

1. **Ticket**：GitHub issue 一個 feature 一張，內容是 AC 清單（範本：issue #1），AC 引用 P00 roadmap 該 P「負責 F」欄的故障列。已有 ticket 就沿用。
2. **實作**：`superpowers:subagent-driven-development`，每 task 審查、整分支 whole-branch review（opus）＋ fix loop、寫 `docs/validation/P0N-validation.md`（AC 逐項證據）。plan 依 system-design §6 測試策略，為負責的每條 F 列出測試矩陣（D57）。這些是作者自查，不取代 senior review。
3. **PR**：push feature 分支、開 PR。base 依 stacked 順序（上一個 P0N 未 merge 就以它的分支為 base）。body 必含：ticket 連結（`Closes #N`）、AC 對照、validation doc 路徑、測試結果、已知 parked 項。CI 綠後，在背景跑 `scripts/review-patrol.sh <PR 編號>` 觸發 senior review，即完成本步；不等它跑完。
4. **回應 review**：見下節「每輪先處理 review 留言」。本迴圈不自己派 reviewer、不寫 `prN-review.md`。

**不等 review 與 approval。** 第 3 步完成即開始下一個 P0N（stacked）。例外：whole-branch review 判定設計文件本身有錯，或 senior review 回 `VERDICT: DESIGN`，寫進報告 §4 後停在該 PR，不開下一個。

## 每輪先處理 review 留言

每輪 loop 開始、接續 feature 之前，先查本迴圈開的每個 open PR，找最新一則含 `<!-- senior-review` 的留言：

- **第 1 輪 `VERDICT: CHANGES`，且留言的 `head:` 等於 PR 目前的 head**：逐條處理阻擋項：先補能重現缺陷的測試並確認它在修正前失敗，再修程式；或說明不修的理由；跑測試後 commit、push 同一分支；在 PR 留一則回覆，逐條列 finding、處理方式與 commit；再在背景跑 `scripts/review-patrol.sh <PR 編號>` 觸發第 2 輪。
- **第 2 輪仍是 CHANGES**：不再修，列進早晨報告「需要你決定」。只有使用者明確裁定要修時才修，修完用 `MAX_ROUNDS=3 scripts/review-patrol.sh <PR 編號>` 追加一輪；不得自行調高 `MAX_ROUNDS`。
- **`VERDICT: DESIGN`**：不修實作，寫進報告 §4；不開下一個 PR（停止條件 6），已開的下游 PR 保留。
- **`CLEAN`，或還沒有 review**：不動，繼續 feature。

非阻擋項開 follow-up issue，不在本 PR 修。

**review 的獨立性由本迴圈遵守：** 不改 `scripts/review-patrol.sh`、`reviewer.md`；不刪除、不編輯 senior review 留言；不自己寫 `<!-- senior-review` 標記。腳本本身出錯時記進早晨報告，不自行修改。下游 PR 的 base 分支因修正而前進時，rebase 下游並 push。

## 環境事實與修法（第一輪先做）

- 機器是 **arm64 Mac，沒有 Rosetta**。`/usr/local`（Intel brew）下的 `openjdk@17`、`maven`、`git` 全是 x86_64，執行會 `Bad CPU type in executable`。
- `/opt/homebrew`（arm brew）存在。**允許**執行：
  ```
  /opt/homebrew/bin/brew install openjdk maven git
  ```
  不需要 sudo；若已安裝就跳過。不得改系統 PATH 或 shell rc 檔。
- 之後每個 Bash 呼叫前置：
  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
  export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
  ```
  驗證：`java -version` 至少 21（本機目前是 27）且 `file $(which java)` 為 arm64；pom 的編譯目標固定 release 21，Java 21 runtime 由 CI（Temurin 21）驗證，本機不另裝 21；`mvn -version` 正常；`which git` 為 `/opt/homebrew/bin/git` 或 `/usr/bin/git`。
- superpowers 的 `scripts/sdd-workspace`、`task-brief`、`review-package` 在 PATH 修好後才能跑（它們之前因 `/usr/local/bin/git` 失敗）。派出的 subagent 也要在 prompt 裡帶上同一段 export。

## 已裁定的事（不要再問）

| 事項 | 裁定 |
| --- | --- |
| Worktree | 同意建立。用 `EnterWorktree`（原生工具）或 `git worktree add .worktrees/<branch> -b <branch>`；`.worktrees/` 須在 `.gitignore`。main 上不寫 code |
| Build | Maven 多模組、Java 21。pom 內的版本號若 Maven Central 抓不到，換成可用的最近版並記 ledger |
| 模型分配 | 計畫已附完整程式碼的 task（1–7、10、11）：implementer 用 `sonnet`；Task 8、9：`opus`；每個 task reviewer 用 `sonnet`（Task 8、9 用 `opus`）；final whole-branch review 用 `opus`。每次派 subagent 都明確指定 model |
| 測試被證明錯誤 | 若 Task 9 / 10 的測試揭露 `WriteHandle` 真實缺陷，修 production code，不改測試預期；若測試本身寫錯（例如 API 名稱不一致），修測試並記 ledger |
| macOS 上 `Files.createLink` | APFS 支援 hard link；`@TempDir` 在同一檔案系統即可。若 CI 環境不支援，測試標 `@EnabledOnOs` 並記 ledger，不刪測試 |
| 設計偏差 | 任何與 system-design.md 的不一致：計畫優先於便利、設計優先於計畫；改動記在 `docs/design/design-decisions.md` 末尾新增一列 `P0N 偏差` |
| Commit | 每個 task 一個以上 commit，訊息依計畫；結尾加 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` |
| OpenSpec | 本夜不動 `openspec/`；不呼叫 `/opsx:*` |
| 文件 | 不改 `docs/spec.md`、`CONTEXT.md`；可追加 `design-decisions.md`、可新增 plans |

## 流程要點

- 先跑 `scripts/sdd-workspace <plan>` 取得工作區，ledger 在 `<workspace>/progress.md`；每輪 loop 開始**先讀 ledger 與 `git log`**，從第一個沒有 `Task N: complete` 的 task 接續，**不要重做已完成的 task**。
- 一次只派一個 implementer；subagent 的 prompt 只給 brief 路徑、介面、Global Constraints 與環境 export，不貼歷史。
- 等 subagent 時用 `ScheduleWakeup` 排 20–30 分鐘的 fallback；有結果就繼續，不要短間隔輪詢。所有 feature 都做完、只剩等 senior review 時，同樣排 20–30 分鐘醒來處理留言。
- 每個 task 審查後才算完成；fix loop 最多 5 輪，到頂就裁定並記 ledger。

## 停止條件（停下時寫早晨報告，再停止本任務的 loop）

1. 需要 sudo、需要碰真實 NAS、或需要網路以外的外部資源。
2. 任何 merge 到 main、push 到 main 或非本迴圈建立的分支、刪除非本迴圈建立的檔案。（push 本迴圈的 feature 分支與開 PR 是固定流程，不是停止條件。）
3. 安全敏感的動作（憑證、token、系統設定）。
4. 計畫壞到每條路都是猜——先試著用設計文件裁定，真的不行才停。
5. P01–P03 與整合驗證已完成、PR 都已開好，且每個 PR 的最新 senior review 為 CLEAN、DESIGN 或已達兩輪上限；餘裕項已處理或明確列為 deferred。寫好交接後停止，不自動擴展到 P04 或整個 M1。
6. whole-branch review 或 senior review 判定設計文件本身有錯（`VERDICT: DESIGN`，見固定流程例外）。

不是停止條件的事：測試失敗（修）、brew 安裝慢（等）、subagent 回報 BLOCKED（換更強模型或拆小重派）、Maven 下載慢（等）。

## 早晨報告：`docs/reports/overnight-report.md`

每完成一個目標項或停下時覆寫，內容固定四段：
1. **做到哪**：分別列出 P01、P02、P03 與整合驗證狀態、branch／base／最後 commit、測試命令與結果；列出供人 review 的差異基準。明寫整個 M1 尚餘哪些工作。
2. **Rulings I made**：ledger 裡每一條 `Ruling:`，附「若錯了代價是什麼」。
3. **Parked / deferred**：審查未修的 minor 與 parked 項。
4. **需要你決定**：明早依 P01 → P02 → P03 順序 review 與 merge；列出每個 PR 連結、senior review 最新結論（附留言連結）、各分支的待決項、尚未執行的實機驗收。

## 一句話版

讀現有 worktree／ledger → 每輪先處理 PR 上的 senior review 留言 → P02 開 PR → P03 ticket、plan、實作、PR（stacked） → Source 路徑整合驗證 → 早晨報告。不問、不 merge、不自己 review、不等 approval；P13 腳本只在有餘裕時處理。

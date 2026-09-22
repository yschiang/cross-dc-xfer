# goal.md — 夜間自主迴圈的任務書

> 讀者：在 `/loop` 裡接手的 Claude。使用者已就寢，**不會回答任何問題**；本檔已預先裁定所有需要人決定的事。遇到本檔未涵蓋的抉擇：依 `docs/spec.md` → `docs/design/system-design.md` → 計畫檔的順序裁定，寫進 ledger 的 `Ruling:` 行，繼續做。停止條件見下文。

## 今晚交付目標

完成 **P01 → P02 → P03**，驗證 M1 的 Source 路徑：App Finalize 發布 → sync service 掃描 → identity 與全部 Required target 義務可靠入庫。交付留在工作分支，供早晨 review 與驗收。

本夜不宣稱完成整個 M1：跨 Node 交付仍需 P04／P05，library 整合需 P10，生命週期與完整驗收另含 P09／P13／P14。P13 腳本列為餘裕項，不排在 P03 前面。

## 目標（依優先序，做完一項才做下一項）

1. **確認並完成 P01**：讀 `p01-core-finalize` 的計畫、ledger、測試與 whole-branch review 結果；已完成且證據對應目前 commit 的部分直接沿用，只補剩餘工作。需要實作時用 `superpowers:subagent-driven-development`。成果留在原 worktree，**不 merge、不 push**。
2. **確認並完成 P02**：優先續用 `p02-sync-service-skeleton` 分支上的 `docs/superpowers/plans/P02-sync-service-skeleton.md` 與進度；缺 plan 才用 `superpowers:writing-plans` 補齊。範圍依 P00 的 P02 列，依據 D17、D45、D30 修 5/6、D24 修、D14 修 2、D34、D34 修。Config 資料模型放在 core（ticket #1、P00 模組表）。依 ticket https://github.com/yschiang/cross-dc-xfer/issues/1 的 12 項驗收條件與 plan 的「修正 tasks」節續行（1R → 2R → 3R → 4 → 5 → 6 → 7）；完成實作、測試、整分支審查與 `docs/validation/P02-validation.md`，AC 逐項有證據才算 P02 通過。
3. **（P02 驗收通過後才開始）寫 P03 plan 並完成實作**：在 `p03-ingest` branch／worktree 工作，基於已通過檢查的 P02 commit；已有該分支則先查狀態並續行。計畫存 `docs/superpowers/plans/P03-ingest.md`，完整範圍依 P00 的 P03 列與 D6、D10 修、D36、D50、D53、D56 ③。完成掃描、原子 ingest、全量對帳與補缺列，再做整分支審查。
4. **驗證 P01–P03 整合路徑**：以本機測試環境串起真實 Finalize 產物、掃描與測試 DB。驗證 identity 與全部義務同交易、commit 後才更新快取；失敗回滾後重掃可補回、重掃不重複建列、既有 identity 缺義務能補齊。所用 DB／檔案系統與尚未做的 Oracle／NAS 驗收須列在報告。
5. **有餘裕才寫 P13 任務書／驗收腳本**：不得延誤 P03 與整合測試；**不執行**任何真實 NAS 操作。

每完成一項，更新 `docs/reports/overnight-report.md`（見「早晨報告」）。

開始前讀 `git worktree list`、各分支的 `git status`／`git log` 與 ledger；plan 可能只存在工作分支，不能因 main 沒有檔案就重建。已有成果不覆蓋、不重做。測試模組名稱依各分支 `pom.xml`，不因文件名稱調整而改專案命名。

## 環境事實與修法（第一輪先做）

- 機器是 **arm64 Mac，沒有 Rosetta**。`/usr/local`（Intel brew）下的 `openjdk@17`、`maven`、`git` 全是 x86_64，執行會 `Bad CPU type in executable`。
- `/opt/homebrew`（arm brew）存在。**允許**執行：
  ```
  /opt/homebrew/bin/brew install openjdk@21 maven git
  ```
  不需要 sudo；若已安裝就跳過。不得改系統 PATH 或 shell rc 檔。
- 之後每個 Bash 呼叫前置：
  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
  ```
  驗證：`java -version` 顯示 21 且 `file $(which java)` 為 arm64；`mvn -version` 正常；`which git` 為 `/opt/homebrew/bin/git` 或 `/usr/bin/git`。
- superpowers 的 `scripts/sdd-workspace`、`task-brief`、`review-package` 在 PATH 修好後才能跑（它們之前因 `/usr/local/bin/git` 失敗）。派出的 subagent 也要在 prompt 裡帶上同一段 export。

## 已裁定的事（不要再問）

| 事項 | 裁定 |
| --- | --- |
| Worktree | 同意建立。用 `EnterWorktree`（原生工具）或 `git worktree add .worktrees/p01-core-finalize -b p01-core-finalize`；`.worktrees/` 須在 `.gitignore`。main 上不寫 code |
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
- 等 subagent 時用 `ScheduleWakeup` 排 20–30 分鐘的 fallback；有結果就繼續，不要短間隔輪詢。
- 每個 task 審查後才算完成；fix loop 最多 5 輪，到頂就裁定並記 ledger。

## 停止條件（停下時寫早晨報告，再停止本任務的 loop）

1. 需要 sudo、需要碰真實 NAS、或需要網路以外的外部資源。
2. 任何 push、merge 到 main、刪除非本迴圈建立的檔案。
3. 安全敏感的動作（憑證、token、系統設定）。
4. 計畫壞到每條路都是猜——先試著用設計文件裁定，真的不行才停。
5. P01–P03 與整合驗證已完成，餘裕項已處理或明確列為 deferred；寫好 review 交接後停止，不自動擴展到 P04 或整個 M1。

不是停止條件的事：測試失敗（修）、brew 安裝慢（等）、subagent 回報 BLOCKED（換更強模型或拆小重派）、Maven 下載慢（等）。

## 早晨報告：`docs/reports/overnight-report.md`

每完成一個目標項或停下時覆寫，內容固定四段：
1. **做到哪**：分別列出 P01、P02、P03 與整合驗證狀態、branch／base／最後 commit、測試命令與結果；列出供人 review 的差異基準。明寫整個 M1 尚餘哪些工作。
2. **Rulings I made**：ledger 裡每一條 `Ruling:`，附「若錯了代價是什麼」。
3. **Parked / deferred**：審查未修的 minor 與 parked 項。
4. **需要你決定**：明早依 P01 → P02 → P03 順序 review；各分支的待決項、是否可整合、尚未執行的實機驗收。

## 一句話版

讀現有 worktree／ledger → 完成 P01 → 完成 P02 → P03 plan 與實作 → Source 路徑整合驗證 → 早晨報告。不問、不 push、不 merge；P13 腳本只在有餘裕時處理。

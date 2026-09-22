# goal.md — 夜間自主迴圈的任務書

> 讀者：在 `/loop` 裡接手的 Claude。使用者已就寢，**不會回答任何問題**；本檔已預先裁定所有需要人決定的事。遇到本檔未涵蓋的抉擇：依 `docs/spec.md` → `docs/design/system-design.md` → 計畫檔的順序裁定，寫進 ledger 的 `Ruling:` 行，繼續做。只有四種情況停下（見「停止條件」）。

## 目標（依優先序，做完一項才做下一項）

1. **完成 P01**：依 `docs/superpowers/plans/P01-core-finalize.md` 的 11 個 task，用 `superpowers:subagent-driven-development` 逐 task 派 subagent 實作 + 審查，全部 `mvn -q -pl gigaxfer-core test` 綠燈，最後做 whole-branch review。成果留在 branch `p01-core-finalize`（worktree），**不 merge、不 push**。
2. **寫 P02 任務書**：`docs/superpowers/plans/P02-sync-service-skeleton.md`，用 `superpowers:writing-plans` 的格式（每步附完整程式碼與測試），範圍見 `docs/superpowers/plans/P00-roadmap.md` 的 P02 列；依據 D17、D45、D30 修 5/6、D24 修、D14 修 2、D34、D34 修。Config 資料模型放在 sync-service 模組內。
3. **實作 P02**：同第 1 項流程，branch `p02-sync-service-skeleton`，從 `p01-core-finalize` 分出。
4. **寫 P13 任務書**：`P13-nfs-acceptance.md`，純 shell 腳本（不需要 NAS 才能寫，但**不要執行**在任何真實 NAS 上）。
5. 時間還有就寫 P03 任務書。

每完成一項，更新 `docs/superpowers/plans/overnight-report.md`（見「早晨報告」）。

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

## 停止條件（只有這四種才停，停了就寫早晨報告然後 `ScheduleWakeup stop`）

1. 需要 sudo、需要碰真實 NAS、或需要網路以外的外部資源。
2. 任何 push、merge 到 main、刪除非本迴圈建立的檔案。
3. 安全敏感的動作（憑證、token、系統設定）。
4. 計畫壞到每條路都是猜——先試著用設計文件裁定，真的不行才停。

不是停止條件的事：測試失敗（修）、brew 安裝慢（等）、subagent 回報 BLOCKED（換更強模型或拆小重派）、Maven 下載慢（等）。

## 早晨報告：`docs/superpowers/plans/overnight-report.md`

每完成一個目標項或停下時覆寫，內容固定四段：
1. **做到哪**：各目標項狀態、branch 名、最後 commit hash、測試數與結果。
2. **Rulings I made**：ledger 裡每一條 `Ruling:`，附「若錯了代價是什麼」。
3. **Parked / deferred**：審查未修的 minor 與 parked 項。
4. **需要你決定**：明早第一件要看的事（例如是否 merge `p01-core-finalize`）。

## 一句話版

修好 arm64 Java → worktree → 按 P01 計畫派 subagent 逐 task 實作審查 → 全綠後 final review → 寫 P02 任務書 → 實作 P02 → 早晨報告。不問、不 push、不 merge。

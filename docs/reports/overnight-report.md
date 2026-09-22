# 早晨報告（2026-09-23 夜間自主迴圈）

## 1. 做到哪

| 目標 | 狀態 |
| --- | --- |
| P01 core Finalize 實作 | **11/11 task 完成**，每 task 經審查；整分支 final review（opus）結論「With fixes」→ 一次 fix batch（C1 + I2 + I3 + I4 + 10 minor）→ scoped re-review **CLEAN**。branch `p01-core-finalize`（worktree `.worktrees/p01-core-finalize`），base `9a47591`，HEAD `86360ac`，22 commits。`mvn -q -pl gigaxfer-core test` → **64/64**。未 merge、未 push |
| P02 任務書 | 已寫（7 tasks，完整程式碼）：`docs/superpowers/plans/P02-sync-service-skeleton.md`（branch `p02-sync-service-skeleton` 自 `86360ac` 分出，plan commit `2162ae5`） |
| P02 實作 | **完成並通過驗收**：依 ticket #1 修訂 plan（`72ac972`），Task 1–7 + 修正 1R/2R/3R，逐 task 審查；整分支 final review（opus）→ With fixes → 一次 fix batch → re-review CLEAN。HEAD 見 `git log`（`ba5aba3` 或其後的 amend），`mvn -q test` **140/140**（core 85 + sync 55）。12 項 AC 全 Pass / Pass-with-gaps，證據在 `docs/validation/P02-validation.md`；README `gigaxfer-sync-service/README.md`。未 merge、未 push |
| P13 / P03 任務書 | 見本檔末尾狀態行 |

環境：這台 Mac 是 arm64 無 Rosetta，`/usr/local` 的 JDK17/maven/git 全是 x86；已用 `/opt/homebrew/bin/brew install openjdk@21 maven git`（openjdk@21 沒裝成，改用 brew 帶進的 arm64 JDK 27，`--release 21` 編譯）。每個 shell 需：
```
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
```

## 2. Rulings I made（依時間序；P01 ledger 已封存為 `docs/reports/P01-core-finalize-ledger.md`，未 commit）

| # | 裁定 | 若錯了代價 |
| --- | --- | --- |
| 1 | T10 測試改先寫 128 KB 再占滿 pool；write/discard/close 遇 pool 滿丟 `NfsUnavailableException`（帶 op），finalizeWrite 遇它回 PendingConfirmation | 多一個例外類別 |
| 2 | EnterWorktree 因 x86 git 失敗 → 手動 `git worktree add .worktrees/p01-core-finalize`；`.worktrees/`、`.superpowers/` 進 .gitignore（main 上 commit `9a47591`） | 無 |
| 3 | 工具鏈改用 /opt/homebrew | 無 |
| 4 | JDK 27 當 runtime，pom 維持 release 21 | 無 |
| 5 | Task 2–5 合併一次派工/審查 | 審查面較大 |
| 6 | 審查者質疑 Co-Authored-By 來源——來自我的 dispatch（goal.md），非缺陷 | 無 |
| 7 | Task 6+7 合併派工 | 同 5 |
| 8 | **`WriteHandle.finalize()` 與 `Object.finalize()` 衝突無法編譯 → 改名 `finalizeWrite()`**，計畫/brief/README 同步；domain 詞 Finalize 不變 | 命名 |
| 9 | opus subagent 的 commit trailer 是 Claude Opus 5 而非 Fable 5.1，保留（反映實際作者） | 署名 |
| 10 | **step ① 拆成 force → 固定 digest → best-effort close**（fsync timeout 後重呼才能收斂）；**DECLARATION_EXPIRED 只在既有宣告（EEXIST）路徑檢查**（避免時鐘偏差誤判新宣告） | 無資料風險 |
| 11 | Task 10+11 合併派工；README 依實作現況寫 | 文件 |
| 12 | implementer 糾正我對 try-with-resources 的說法（JLS：body 例外為主、close 為 suppressed），採納 | — |
| 13 | **step ① 通道已關則重開暫存檔再 fsync**（close() 先於重呼也能收斂）；README 範例不再用 try-with-resources 包重試 | 多一次 open |
| 14 | Final review 的 1 Critical + 4 Important + 10 minor 一次 fix batch（opus）：**C1 write 路徑任何例外都 poison handle**（原本只有 NfsException；ENOSPC/EIO 可讓 finalizeWrite 對 bytes≠manifest 回 Success）；**I2 dataClass 走 FileIdentity 同級 segment 驗證**（`../` 可逃出 namespace 樹）；**I3 `digest-key` 改每 64 KB 一次執行器 op**（原本整檔一次 op，大檔永遠 Pending）；**I4 `stat-key` 用 readAttributes、只把 ENOENT 當不存在**（原 `Files.exists` 把 EIO 當不存在 → 可能誤回 EXPIRED） | 多幾次 call 開銷 |
| 15 | **DECLARATION_EXPIRED 維持用 manifest mtime（D53 修）不改碼**；與 D56 ②「mtime 只作粗篩」的張力記入 design-decisions.md `P01 偏差 2 ⑦`，交你決定（§4） | 多一次 stat-manifest op |
| C | goal.md 寫 config 模型放 sync-service，但 P00 模組表與 ticket #1 都說放 core；維持 core | 搬 package |
| D | 壞的 active.json 不得覆蓋好的 lkg（review 抓到的 plan 缺陷，`1617096`） | 無 |
| E | 一行修法直接以 diff 驗證取代 scoped re-review | 無 |
| F | commit 署名記錄實際執行模型（修訂 plan 的 Global Constraints） | 署名 |
| G | `fab` 改名 `deployment`，fixture namespaces = [transactions, analytics] | 無 |
| H | candidate 單獨存在不得完成初始化（ADR-0003/D17），原 Task 2 測試反向 | 無 |
| I | 2R+3R 合併派工；Task 3 與 1R–3R 合併一次審查 | 審查面較大 |
| J | StartupRefusalTest 不啟用 test profile（profile 固定 node=P1 會蓋掉 defaultProperties 造成假綠） | 無 |
| K | `file_identity.size`/`received.size` 改名 `size_bytes`（Oracle 保留字，H2 抓不到；V1 未部署直接改） | 無 |
| L | `SyncTestSupport` 加 `@DirtiesContext(AFTER_CLASS)`（static TempDir 與 context 快取衝突） | 測試稍慢 |
| M | Task 5 review 與 Task 6 implementer 並行（reviewer 唯讀、檔案不重疊） | 無 |
| N | 測試用 echo controller 去多餘 @Bean、@RequestParam 加 name | 無 |
| **O** | **認證 filter 改豁免清單（`/policy`、`/locate/**`、`/actuator/**`、`/error`）預設全保護，路徑用 UrlPathHelper 解碼**——review 抓到 `getRequestURI` 未解碼／含 `;` 可繞過（P04 `/file` 上線會變成無認證取檔） | 多保護幾條路徑 |
| Q | health gauges 改 supplier-backed（scrape 即探測，經執行器），不加排程器 | scrape 多一次 stat / SELECT |
| R | config 欄位 `deployment` 保留，偏差 ⑧ 記錄對應 domain 詞 Fab；CONTEXT.md 是否加同義詞交你 | 命名 |
| P | dot-segment 路徑落回需認證；RFC 7235 行為以 MockMvc 釘住（控制器直接修的三個 nit） | 無 |
| 16 | 延後：memoise declared、`nfs_pool_exhausted_count`、Jackson shading（皆 P10）、真執行緒競態測試、PathLayout path→identity（P03）；「發布內容≠宣告 → CONFLICT」終態補記 `P01 偏差 2 ⑧` 指向 F21 / Deep check | P10/P03 再補 |

## 3. Parked / deferred

Final review triage 後仍留著的（皆「可等」）：
- beginWrite timeout 留孤兒 `.writing`（P09 清道夫涵蓋）；BufferedOutputStream 對 ≥64 KB 單次 write 不分塊（P10 壓測）
- timeout 用 toMillis；interrupt 報為 timeout（語意上「結果未知」正確）；`inUse()` 短暫低報（已文件化）
- cleanupTemps 重算 tmp 路徑；close() busy 路徑無直接測試（同包裝路徑已測）
- 重試 link-key 逾時後會重寫 manifest tmp（正確但浪費，memoise declared 可省；P10）
- `nfs_pool_exhausted_count`（D51 要求曝出）core 未提供計數，P10 以裝飾 NfsExecutor 取得；fix 後 `digest-key` 的 op 數與檔案大小成正比，監控首版要註明它會是計數大戶
- `jackson-databind` 在 library 為 compile scope，P10 打包時 shade 或註明
- I4 的回歸測試用自指 symlink 製造 ELOOP（非 POSIX 檔案系統會 error），未加 Assumptions
- re-review 留的 Low/Info：`Sha256.ofFile(nfs)` 的 open/close op 逾時時 fd 靠 Cleaner 回收（與 `WriteHandle.close()` 同天花板，P10 監控）；`Sha256.ofFile(Path)` 已無 production 呼叫者但仍公開（會繞過執行器）
- P03 提醒：PathLayout 只有 identity→path，掃描器需要 path→identity，屆時加在 core

## 4. 需要你決定

0. **PR #3（P01）**：我的 reviewer 對 origin `ec4e8e3` 給 Approve（`docs/reports/pr3-review.md`）；本機 `p01-core-finalize-pr` 已到 `a94afc2` 未 push，該增量另審（見 `docs/reports/pr3-a94afc2-review.md`）。P02 rebase 到 PR 合併後只撞 `Sha256.java`/`Sha256Test.java`。
1. **merge `p01-core-finalize` 到 main？** final review 與 re-review 皆 clean，64/64。我沒 merge、沒 push。注意 P02 分支從它分出，merge 順序 P01 → P02。
2. **宣告年齡（N = 7 天）用 manifest mtime 還是 `source_ready_at`？** 目前碼依 D53 修用 mtime。改用 manifest 內的 `source_ready_at`（Source NTP 時鐘，D26）可省一次 stat 並免 NAS 時鐘誤差，且與 D56 ②「mtime 只作粗篩」一致。我的建議：改用 `source_ready_at`；一行碼 + 一列 design-decisions。
3. **「已發布 `<key>` digest ≠ 宣告 → CONFLICT」終態**：library 依 D46 不刪已發布檔，Application 被告知換 key，壞檔仍 Ready 會被 ingest 與傳輸，只能靠 F21 / Deep check 抓。§6 / D53 未命名此終態，已記 `P01 偏差 2 ⑧`；要不要在 P03 ingest 加「digest 與 manifest 不符不 ingest」的檢查？我的建議：要，成本一次 digest。
4. **P02 任務書我定的格式**：config JSON 結構（§設定檔格式，已依你的修訂稿加 `deployment` 與 `namespaces`）與「各 Node token 雜湊放 config 的 operational 段」。若你要雜湊改放本機檔而非 config，Task 6 改讀檔即可。
5. **P02 parked**：`DbBootstrap.stop()` 只 interrupt 不 join；`SchemaTest` 未斷言索引；設計文件 §3 obligation 的 `(state, source_ready_at)` 索引欄位不在 obligation 表（需設計裁定：join 或冗餘欄位）；`EXEMPT` 寫死 `/actuator` base-path（改 base-path 會變成需 token，fail-closed）；`ConfigActivation.activationFailure` 只帶一種原因（active 壞 + candidate 被拒時只留後者，另一原因在 log）；`FinalizeUnderPressureTest` 仍偶發一次 NfsBusyException（重跑綠）。

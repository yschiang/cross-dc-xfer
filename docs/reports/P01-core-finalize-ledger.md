# SDD ledger — plan: docs/superpowers/plans/P01-core-finalize.md
Spec: docs/design/system-design.md §2.2/§5/§6 + design-decisions.md（reachable）。Worktree: .worktrees/p01-core-finalize, branch p01-core-finalize, base 9a47591。
Env: arm64, no Rosetta; /usr/local toolchain is x86_64 → arm brew installing openjdk@21 maven git (bg task b6egtcld9). PATH/JAVA_HOME export required in every shell.

## Pre-flight scan
| 對象 | 產出 vs 消費 | 結果 |
| --- | --- | --- |
| T2 Sha256 ↔ T4 PathLayout.bucket | newDigest()/HexFormat | 一致 |
| T3 FileIdentity ↔ T5 Manifest.identity()/sameDeclaration | record 三段 | 一致 |
| T4 PathLayout ↔ T7/T8 | contentDir/writingPath/manifestPath/manifestTmpPath/toContentPath/fromContentPath | 一致 |
| T5 ManifestCodec ↔ T8 finalize | encode/decode, MalformedManifestException extends IOException → 由 IOException 分支處理 | 一致 |
| T6 NfsExecutor ↔ T7/T8/T9 FaultInjectingNfs | call/run/close；IOException 子類（FileAlreadyExists/NoSuchFile）原樣重丟 → T8 的 catch 可用 | 一致 |
| T7 WriteHandle ↔ T8 finalize | 私有欄位同類別；uuid() package-private 供測試；LocalStore.codec package-private 供 T9 測試 | 一致 |
| T7 ↔ T10 `finalize_when_pool_full` | **衝突**：測試寫 1 byte 後占滿 pool 再 finalize；finalize 先 `stream.flush()` → ChannelStream.write → NfsBusy → 包成 IOException → 落入 `Failure(IO)`，但測試預期 `PendingConfirmation("fsync-writing")` | 見 Ruling 1 |
| T8 自洽 | 重呼 finalize：digest 固定後跳過 flush/fsync；write-manifest-tmp 先 deleteIfExists 再 CREATE_NEW；EEXIST 分支 → 四項比對 → stat-key → 年齡 → link | 一致 |
| T9 各情境 vs T8 程式碼 | F2/F3/F2b/F5/F5b/F1b/F4/scenario 11 逐一走過 T8 分支 | 一致 |
| T1 pom | jackson-bom/junit-bom import、surefire 3.5.2 自動偵測 JUnit5 | 一致 |

Ruling 1: T10 `finalize_when_pool_full` 改為先寫 128 KB（≥ buffer，直接落盤、buffer 為空），再占滿 pool，使 flush 為 no-op、第一個 NFS 操作為 fsync-writing；並在 T7 讓 ChannelStream 丟 `NfsUnavailableException extends IOException`（帶 op），T8 finalize 額外 catch 它 → `PendingConfirmation(op)`，讓「flush 時 pool 滿」也回 PENDING 而非 Failure(IO) — 理由：SR-04 結果未知不得當失敗；若錯：多一個例外類別，無資料風險。
Ruling 2: EnterWorktree 因 x86 git 失敗 → 手動 `git worktree add .worktrees/p01-core-finalize`，`.worktrees/`、`.superpowers/` 加入 .gitignore（commit 9a47591 on main）— 若錯：無。
Ruling 3: 工具鏈改用 /opt/homebrew（goal.md 已授權）— 若錯：無。
Ruling 4: brew 未裝出 openjdk@21，改用 /opt/homebrew/opt/openjdk（JDK 27 arm64）當 runtime，pom 維持 maven.compiler.release=21 — 若錯：只是 runtime 版本差，語言層級仍 21。
Env OK: JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home, Maven 3.9.16, git 2.55 (/opt/homebrew/bin).
Task 1: dispatched (base 9a47591)
Task 1: complete (commits 9a47591..606974b, review clean)
Ruling 5: Task 2–5 皆為附完整程式碼的獨立小類別（Sha256、FileIdentity、PathLayout、Manifest），合併為一次 implementer dispatch（四個 commit）、一次審查 — 理由：減少 turn 數；若錯：審查面較大，仍 < 700 行。
Task 2-5: dispatched (base 606974b)
Task 2-5: review → 2 Important. Ruling 6: Important #1（attribution 聲稱無來源）— 非缺陷：Co-Authored-By 指示來自控制器 dispatch prompt（goal.md 規定），brief 本無此文；已查 4 個 commit 皆帶 trailer。若錯：無。
Task 2-5: fix round 1/5 開始 — 修 Important #2（decode 加 digest 格式 / size ≥ 0 / identity 段驗證 → Malformed）與 Minor #1（read 迴圈 != -1）；Minor #2/#3 deferred。
Task 2-5: fix round 1/5 (2 addressed per implementer, re-review pending; commits e60e870..03a04c8)
Task 2-5: minor (deferred): PathLayout 建構子不 null-check root/zone；dataClass 未驗證為安全路徑段（留給 P10 Policy 登錄檢查）
Task 2-5: fix round 1/5 (2 addressed, 0 open; commits e60e870..03a04c8)
Task 2: complete / Task 3: complete / Task 4: complete / Task 5: complete (commits 606974b..03a04c8, review clean after 1 fix round)
Ruling 7: Task 6+7 合併一次 dispatch（皆附完整程式碼；Task 7 套用 Ruling 1：ChannelStream 丟 NfsUnavailableException extends IOException 帶 op）— 若錯：審查面 ~700 行。
Task 6-7: dispatched (base 03a04c8)
Ruling 8: `WriteHandle.finalize(): FinalizeResult` 與 `Object.finalize()` 衝突無法編譯（implementer 發現）→ 方法改名 `finalizeWrite()`，計畫 Task 8–11 文字與 brief 全部同步改；domain 詞 Finalize 不變 — 若錯：只是命名。
Task 6-7: DONE_WITH_CONCERNS（concern = 上述改名，已裁定）；review dispatched (base 03a04c8, head 493db7b)
Task 6-7: review (opus) → 4 Important（皆 plan-mandated，裁定為真缺陷進 fix loop）：close() 繞過執行器、write timeout 後 digest/size 可能與檔案分歧無 poison flag、NfsUnavailableException 無測試、Busy/Timeout 無共同父類導致 op 重複。Minor #13（javadoc 殘留 finalize()）併入本輪。
Task 6-7: minor (deferred): #5 WriteRejectedException 無 cause 建構子；#6 beginWrite timeout 留下孤兒 .writing（清道夫 P09 涵蓋）；#7 close() 不 flush buffer（Task 8 finalizeWrite 先 flush；加 javadoc）；#8 BufferedOutputStream 對 ≥64KB 單次 write 不分塊，單一 slot 可能長占（留 P10/壓測）；#9 timeout 用 toMillis；#10 interrupt 報為 timeout；#11 timing test 有 handoff 視窗可能偶發 Busy；#12 inUse 短暫低報；#14 style；#15 建構子 null-check。
Task 6-7: fix round 1/5 開始
Task 6-7: fix round 1/5 (5 addressed, 0 open; commits 493db7b..4825bbe)
Task 6: complete / Task 7: complete (commits 03a04c8..4825bbe, review clean after 1 fix round)
Task 6-7: minor (deferred): LocalStore.beginWrite 仍 catch Busy|Timeout 而非 NfsException；close() 走執行器後 try-with-resources 雙重失敗時主例外可能是 close 的（README 應提醒）；close() 的 busy 路徑無直接測試。
Task 8: dispatched (base 4825bbe, opus)
Task 8: DONE (2244ace)。Ruling 9: opus subagent 的 commit trailer 為 Claude Opus 5，非 goal.md 的 Fable 5.1 — 保留，反映實際作者模型；若錯：純署名。Concerns 記入 deferred：flush busy → 第一次 Pending、第二次 Failure(IO)（README 要寫）；close() 先於 ① 會讓 finalizeWrite 回 Failure(IO)；步② 多餘 createDirectories 可能留空小時目錄。
Task 8: review dispatched (base 4825bbe, opus)
Task 8: review (opus) → 2 Important：fsync timeout 後重呼遇 ClosedChannelException → 永久 Failure（不收斂）；DECLARATION_EXPIRED 年齡檢查也套在本次剛建的 manifest（clock 與檔案系統時鐘偏差即誤判）。Ruling 10: ① 改為 force 與 close 分開——`run("fsync-writing", force)` → 固定 digest → best-effort close；年齡檢查只在 EEXIST（既有宣告）路徑執行（計畫文字修正）— 若錯：無資料風險。Minor 1/2/3/5 併入本輪；Minor 4/6 deferred。
Task 8: fix round 1/5 開始
Task 8: fix round 1/5 (6 addressed per implementer + 3650d test; re-review pending; commits 2244ace..f2cf09b)
Task 8: fix round 1/5 (6 addressed + 3650d, 0 open; commits 2244ace..f2cf09b)
Task 8: complete (commits 4825bbe..f2cf09b, review clean after 1 fix round)
Task 8: minor (deferred): cleanupTemps 重算 tmp 路徑與 bestEffort 全名；close() 先於 ① 後 finalizeWrite 回 Failure(IO) 無測試；正向 DECLARATION_EXPIRED 由 Task 9 F2b 覆蓋。
Task 9: dispatched (base f2cf09b, opus)
Task 9: DONE (2c4a7ab, 56/56, 10 新測試皆經 mutation 驗證)。deferred 設計觀察：DECLARATION_EXPIRED 用 manifest 檔案 mtime 而非 source_ready_at（D53 修 明定 mtime；D56 ② 說 mtime 只作粗篩）— 留給 final review / 設計決策回填。
Task 9: review dispatched (base f2cf09b)
Task 9: complete (commits f2cf09b..2c4a7ab, review clean)
Ruling 11: Task 10+11 合併一次 dispatch（sonnet）；Task 10 套用 Ruling 1（先寫 128 KB 再占滿 pool）；README 依實作現況更新（finalizeWrite、close() 走執行器、flush 池滿的兩段結果）— 若錯：文件而已。
Task 10-11: dispatched (base 2c4a7ab)
Task 10-11: DONE (876481c, bfb961a; 59/59)。Ruling 12: implementer 更正控制器對 try-with-resources 的說法（JLS：body 例外為主、close 例外為 suppressed），README 依驗證後事實撰寫 — 我先前的敘述錯誤，已採納。
Task 10-11: review dispatched (base 2c4a7ab)
Task 10-11: review → 1 Critical：README 範例用 try-with-resources，step ① PendingConfirmation 後自動 close() 使重呼遇 ClosedChannelException → 永久 Failure 並刪暫存。Ruling 13: 根因在 production——step ① 對已關閉通道不可重入；改為 `if (!channel.isOpen())` 時在執行器內重開 `writing`（WRITE）再 force（POSIX fsync 對同一 inode 有效），使 close() 先於重呼也能收斂（同時解 Task 8 deferred concern）；README 範例改為明確不在 Pending 時關閉 handle，並加上此保證說明 — 若錯：多一次 open。
Task 10-11: fix round 1/5 開始
Task 10-11: fix round 1/5 (implementer: fixed; re-review pending; commits bfb961a..41bebee)
Task 10-11: fix round 1/5 (1 addressed, 0 open; commits bfb961a..41bebee)
Task 10: complete / Task 11: complete (commits 2c4a7ab..41bebee, review clean after 1 fix round)
ALL TASKS COMPLETE — final whole-branch review dispatched (merge-base 9a47591..41bebee, opus)
FINAL REVIEW → With fixes：1 Critical（write 路徑一般 IOException 不 poison）、4 Important（dataClass 未驗、digest-key 整檔單 op、stat-key Files.exists 吞錯、mtime vs source_ready_at）、minors 若干。
Ruling 14: 一次 fix dispatch（opus）處理 C1 + I2 + I3 + I4 + 十項低風險 minor（brief: final-fix-brief.md）；I3 採 chunked read 經執行器，與 P01 偏差 ④ 對稱 — 若錯：多幾次 call 開銷。
Ruling 15: I5 不改碼——DECLARATION_EXPIRED 維持 D53 修的 manifest mtime（spec 為準）；design-decisions.md 記錄與 D56 ② 的張力並列入早晨報告「需要你決定」— 若錯：多一次 stat-manifest op，可日後改用 source_ready_at。
Ruling 16: 延後 memoise declared、pool_exhausted counter、Jackson shading（皆 P10）、真執行緒競態測試、path→identity（P03）；發布內容≠宣告 → CONFLICT 的終態補入 design-decisions.md 指向 F21 / Deep check — 若錯：P10/P03 再補。
FINAL FIX: dispatched (base 41bebee)
FINAL FIX: DONE_WITH_CONCERNS (6032834..86360ac; 64/64)。concerns：I3 簽名多一 op 參數（接受）；digest-key op 數與檔案大小成正比（P10 監控註記）；I4 測試用自指 symlink 製造 ELOOP（接受，目標平台 POSIX）；M9 改輪詢「提交是否被接受」（接受，理由：getActiveCount 歸零早於 worker 回 SynchronousQueue）；M2 後 fresh 宣告不 stat-key（brief 所要）。
FINAL FIX: re-review dispatched (6032834..86360ac, opus)
FINAL FIX: re-review CLEAN (C1/I2/I3/I4/M1-M10 addressed; 4 deviations accepted; 64/64)。Low/Info 留存：Sha256.ofFile(nfs) open/close op 逾時時 fd 靠 Cleaner 回收（P10 監控）；≥64 KB 單次 write 仍單 op（既有）；Sha256.ofFile(Path) 無 production 呼叫者。
Task/ALL: complete (9a47591..86360ac, 22 commits, final review + 1 fix round clean)

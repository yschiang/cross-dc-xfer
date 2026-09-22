# P01 發布能力驗收紀錄

**Ticket：** [P01 #2](https://github.com/yschiang/cross-dc-xfer/issues/2)

**狀態：** 本機自動化測試通過，提交人工 PR review；未合併，不代表完整 M1 或真實 NAS 已驗收。

## 版本與執行環境

| 項目 | 證據 |
| --- | --- |
| 原實作 | `p01-core-finalize`，`86360ac615cddba46c98ce797931bfb755254bf9` |
| 原工作紀錄 | 11 個 task 完成；whole-branch review → 修正 → scoped re-review CLEAN，記於本地 P01 SDD ledger |
| 提交分支 | `p01-core-finalize-pr`，基於 `c891fc655bcc70c736f659d11f4d1a34099da825`；被測版本由包含本檔的 PR commit 定位 |
| 移入範圍 | parent POM、core POM、全部 production Java 與 test Java 逐檔沿用 `86360ac`；只更新文件、plan 命名與 Git ignore |
| 本次執行 | 2026-09-23，macOS arm64，本機暫存檔案系統；JDK 27、Maven 3.9.16，`maven.compiler.release=21` |
| 結果 | **64 tests，0 failures、0 errors、0 skipped**；重新執行的 Surefire XML 彙總 |

舊 P01 分支與現行 main 無共同祖先，因此另建提交分支，未改寫原 P01／P02 分支。原 review 結論來自既存 ledger；本輪重新確認移入的程式一致性並執行測試，不冒充第二次完整 code review，也不是 GitHub Reviewer 已批准。

## 重現命令

在包含此紀錄的 PR 版本執行：

```sh
java -version
mvn -version
mvn -q -pl gigaxfer-core test
```

本次使用已快取依賴，以 `mvn -o -q -pl gigaxfer-core test` 執行。一般新環境省略 `-o`。Surefire 結果位於 `gigaxfer-core/target/surefire-reports/`；Reviewer 應在 Java 21 runtime 再執行，不能把 release 21 編譯目標等同 Java 21 執行測試。

## Ticket 驗收對照

| AC | 本次證據 | 範圍 |
| --- | --- | --- |
| P01-01 | core POM、SmokeTest、core README 的 Success／Pending／Failure 範例 | core 無 Spring／DB；業務交易提交由 App 負責 |
| P01-02 | FileIdentityTest、BeginWriteTest | 非法命名、保留字與 gate 拒寫 |
| P01-03 | ManifestCodecTest | 固定 wire line、round-trip、缺欄位／非法 digest／截斷內容 |
| P01-04 | FinalizeHappyPathTest、FinalizeRecoveryTest | 正常發布、空檔、大檔、宣告路徑、暫存清理；不證明真實 NAS 持久化 |
| P01-05 | 同 handle 重試、隔日新 handle、F5、D44 的測試 | 相同內容成功；衝突不覆寫已發布檔 |
| P01-06 | FinalizeRecoveryTest 的 F1b、F2、F2b、F3、F4、F5b、scenario_11 | 模擬操作前失敗／操作後回覆遺失；沒有殺 process 或 NAS failover |
| P01-07 | BoundedNfsExecutorTest、FinalizeUnderPressureTest、chunked digest 測試 | timeout 槽保留、池滿行為、digest 分塊；未做真實 NAS hang 壓測 |
| P01-08 | WriteHandleUnavailableTest、FinalizeRetryTest、stat-key error 測試 | write IOException 中毒、fsync／close 後續行、非 ENOENT 不誤判缺檔 |
| P01-09 | 本檔、PR、ticket、plan 與 core README | 人工審查及 ticket 最終勾選尚待完成 |

## 原審查修正

原 ledger 記錄的修正已包含在 `86360ac`：

- write 的一般 IOException 也會使 handle 中毒，避免 digest／size 與內容不一致仍發布。
- data class 與其他路徑片段同樣驗證，避免越出目錄。
- 既有內容的 digest 以分塊 NFS 操作讀取；stat 只將 ENOENT 視為不存在。
- fsync 結果未知後可重試；通道已 close 時可重開暫存檔再確認。
- 新宣告不套用既有宣告年齡檢查；已有正式內容時先 rediscovery。

宣告年齡維持 D53 修／D56 定案的 manifest mtime；本票沒有採用舊報告提出的 source_ready_at 替代建議。已發布內容與宣告不符維持 CONFLICT，library 不刪除正式檔；P03／P06／P07 的 ingest 與事故處理由各自契約驗收。

## 尚未驗收與下游工作

- **執行環境：** Java 21 runtime 尚未重跑；本次是 JDK 27 編譯至 release 21。
- **P13：** 真實 OS／NFS client／NAS 的 fsync 穩定儲存、hard link、failover、長時間掛起與操作所有權語意。
- **P10：** Policy／容量 WriteGate、Consumer API、指標與 library 打包；F18 不在這 64 個測試內。
- **P09／P14：** 孤兒暫存檔清理、跨 Node E2E、容量與長時間壓測。
- **既存低優先項：** 大於等於 64 KB 的單次 write 仍是一個 NFS operation；部分 open／close timeout 的 handle 回收依賴 Cleaner；pool 指標由後續整合。這些不因本票開 PR 而視為已解決。

合併前由 Reviewer 核對上述適用邊界，依 ticket 的 AC 確認完成度；不得以測試總數代替需求驗收。

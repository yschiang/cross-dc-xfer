# 檔案清單與生命週期（草稿，併入 system-design.md）

Framework 會建立、讀取或刪除的所有檔案。正式內容目錄 `<dir>` = `<source node>/<namespace>/<data class>/<yyyy-mm-dd>/<HH>/`（D2 修），小時取第②步宣告時刻，由第一個宣告 manifest 的人決定並記在 manifest 的 content_path（D48 修）；暫存目錄 `<wdir>` = beginWrite 當下小時目錄，可與 `<dir>` 不同；manifest 目錄 `<bucket>` = `<source node>/<namespace>/<hash(key) 前 3 hex>/`，只由 identity 決定（D48）。「刪」欄為空表示 Framework 永不刪（RT-01）。

## Source Node NAS

| 檔案 | 建 | 讀 | 刪 | 生命週期 | 決策 |
| --- | --- | --- | --- | --- | --- |
| `<wdir>/<key>.<uuid>.writing` | library `beginWrite` | library（fsync、link 來源） | library Finalize 第④步 unlink；超過 Abandoned TTL 由清道夫刪 | Writing → 跨目錄成為 `<dir>/<key>` 的 link 來源 → unlink；或 Abandoned；超 TTL 後已送出的 link 結果依 D51 修 2 查證 | D3, D11, D35, D48 修, D51 修 2 |
| `<bucket>/<key>.manifest.<uuid>.tmp` | library Finalize 第②步寫入 + fsync | library（link 來源） | link 成功後 unlink；殘留由清道夫刪 | 一次性 | D44, D35 |
| `<bucket>/<key>.manifest` | library Finalize 第②步 link(tmp) 原子宣告；含 content_path | sync service 掃描、rediscovery、`/locate` | 本版永不刪，不自動也不手動（D53） | 建立 → 與 `<key>` 一起構成 Source Ready → 永久保留；無內容者為「未發布或發布後遺失」候選；mtime 作桶枚舉粗篩與宣告年齡（N = 7 天，超過不可再次嘗試發布）依據 | D1, D3, D48, D53, D53 修, D56 |
| `<dir>/<key>` | library Finalize 第③步 link | sync service `/file` streaming、Consumer `read` | 永不刪，NAS 政策 30 天 | link 成功 = Source Ready = commit point | D3, RT-01 |

## Target Node NAS

| 檔案 | 建 | 讀 | 刪 | 生命週期 | 決策 |
| --- | --- | --- | --- | --- | --- |
| `<dir>/<key>.<uuid>.writing` | Target sync service 下載 | 自己（DigestInputStream 驗證、fsync） | 驗證失敗即刪；link / rename 逾時時不得先刪（所有權與 in-flight 排他保留至舊操作結束）；crash 或卡住殘留由清道夫依 mtime 刪 | 下載中 → 驗證 → 暫存 fsync 成功（穩定寫入、COMMIT 由 OS NFS client 完成，經 failover 驗收）→ link 或 rename 為 `<key>` → received 提交 → unlink；儲存類錯誤走 D43 閘門，不算單檔驗證失敗 | D12, D12 修, D12 修 2, D43, D51, D35 |
| `<dir>/<key>` | Target sync service link 發布；修復時 rename 原子覆蓋 | Consumer `read`、Deep check 重算 digest、DB 重建 | 永不刪，NAS 政策 | link 成功 = Target Ready | D12, D15, D16, D47 |

Target 端不寫 `.manifest` 與 `.evidence`；基準在 Source manifest 與 Target DB received 表（D12、D15）。

## sync 主機本機磁碟

| 檔案 | 建 | 讀 | 刪 | 生命週期 | 決策 |
| --- | --- | --- | --- | --- | --- |
| `candidate.json.tmp` → `candidate.json` | CD pipeline 寫 tmp 後 rename | sync service 驗證 | 驗證後 rename 為 active，或驗證失敗留原地並上報 | 一次性；sync service 只認 `candidate.json`，`.tmp` 一律忽略 | D17 |
| `active.json` | sync service 啟動時由 candidate rename | sync service 啟動讀一次、`/policy` | 下一版啟用時 rename 為 lkg | 當前生效版本 | D17, D18 |
| `lkg.json` | sync service 由 active rename | sync service 啟動時 active 缺失的備援 | 下一版啟用時被覆蓋 | 上一個成功版本 | D17 |
| Node token 秘密檔（路徑由部署決定） | ops 部署時放置，不進 git | sync service 啟動讀取，作為呼叫他 Node 時的憑證；驗證端只持有各 Node token 的雜湊、不持明文 | 永不刪；輪替為 ops 程序（operational policy 可調項），新值覆寫 | 每 Node 一份，輪替時覆寫 | D14 修, D14 修 2, D30 |

## Application 主機本機磁碟

| 檔案 | 建 | 讀 | 刪 | 生命週期 | 決策 |
| --- | --- | --- | --- | --- | --- |
| `<app data dir>/file-sync-policy.json`（D23 修） | library 每次成功 `GET /policy` 後覆寫 | library 啟動且 sync service 不可達時 | 永不刪 | 最近一次成功取得的 Policy 段 | D18 |

## 不存在的檔案（已否決）

`.evidence`、Abandoned 搬移目錄、Target `.manifest`、`.corrupt` 證據檔（見「簡化審查」列與 D47）。

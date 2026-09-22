# gigaxfer-sync-service

每 Node 一個 process 的同步服務。本模組目前為骨架（P02）：設定啟用、schema、認證、health、`/policy`。掃描、傳輸、對帳由 P03 起加入。

## 執行

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
export PATH=/opt/homebrew/bin:$JAVA_HOME/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH
mvn -q -pl gigaxfer-sync-service -am package -DskipTests
java -jar gigaxfer-sync-service/target/gigaxfer-sync-service-0.1.0-SNAPSHOT.jar \
  --gigaxfer.node=P1 \
  --gigaxfer.config-dir=/var/lib/gigaxfer/config \
  --gigaxfer.token-file=/etc/gigaxfer/node.token \
  --gigaxfer.nfs-root=/mnt/files \
  --spring.datasource.url=jdbc:oracle:thin:@//db:1521/FREEPDB1 \
  --spring.datasource.username="$P02_DB_USER" --spring.datasource.password="$P02_DB_PASSWORD"
```

本機參數（不是 config 版本的一部分）：

| 屬性 | 意義 | 預設 |
| --- | --- | --- |
| `gigaxfer.node` | 本 Node 名，必須在 `policy.nodes` 內 | 無，必填 |
| `gigaxfer.config-dir` | `candidate.json` / `active.json` / `lkg.json` 所在目錄 | 無，必填 |
| `gigaxfer.token-file` | 本 Node 明文 token 檔（不進 git、不進 config） | 無，必填 |
| `gigaxfer.nfs-root` | NAS mount root（`hard` mount） | 無，必填 |
| `gigaxfer.nfs-timeout` | 每個 NFS 操作的 timeout | 30s |
| `gigaxfer.nfs-slots` | 有界執行器槽數（D51） | 16 |
| `gigaxfer.db-retry-millis` | DB migration/bootstrap 背景重試間隔（D34 修）；僅測試用來縮短等待，正式部署留預設 | 5000 |

## 首次初始化（人工放 active.json）

本模組不提供線上遷移或自動初始化；第一次部署由 ops 人工放置 `active.json`：

```bash
mkdir -p /var/lib/gigaxfer/config
cp v1.json /var/lib/gigaxfer/config/active.json   # 內容 = 設定檔格式，schema_version=1, version=1
```

啟動時只有 `active.json`（無 `candidate.json`）不會做任何啟用動作，直接讀它。`active.json` 與 `lkg.json` 皆缺 → process 拒絕啟動（`ConfigUnavailableException`：「initialise active.json first」）。`gigaxfer.node` 不在 `policy.nodes` 內同樣拒絕啟動（「is not in policy.nodes」）——兩者都是 context 啟動失敗，不是靜默降級。

## 設定更新 / 失敗回退

CD pipeline 把新版本寫成 `candidate.json.tmp` 後 rename 為 `candidate.json`（一次性、不可觀察到半寫檔——sync service 只認 `candidate.json`，`.tmp` 一律忽略）。下一次啟動（`systemctl restart`）時：

1. 有 `candidate.json`：驗證 schema、`version` 嚴格遞增、`policy` 段與現行（active，缺則 lkg）完全相同、`fixed` 段若存在須等於 v1 固定常數。
   - 通過 → `active.json` rename 為 `lkg.json`、`candidate.json` rename 為 `active.json`，用新版本。
   - 不通過 → `candidate.json` 留在原地（供 ops 檢查修正）、`activation_failure_count` 記 1、process 用現行 `active.json`（或其缺失時的 `lkg.json`）繼續啟動。
2. 沒有 `candidate.json`：直接用 `active.json`；缺失時退回 `lkg.json`。

設定物件在 process 內不可變、不熱載入；改 operational policy 一律是「CD 放新 candidate → `systemctl restart`」，執行中改動 `active.json` 檔案內容不會影響記憶體中的設定（見 `PolicyEndpointTest.config_is_immutable_while_process_runs`）。

## 啟動序列（D34、D34 修、D45）

1. 讀 `config-dir`（見上節「設定更新 / 失敗回退」）。
2. 起 HTTP。`/policy`、`/actuator/**` 立即可用。
3. 背景執行 Flyway migration 與 bootstrap 列（`node_meta`、`seq_counter`），失敗每 5 s 重試（`gigaxfer.db-retry-millis` 可覆寫）；期間 `/actuator/health/readiness` 的 `db` 為 DOWN，process 不退出。

設定 process 內不可變。改 operational policy = CD 放新 candidate 後 `systemctl restart`。

**systemd 與 readiness 的關係（D34 修）**：systemd unit 只監看 process 是否存活（`Type=simple`／`Restart=on-failure` 針對 process 本身，不接 HTTP health check）；`/actuator/health/readiness` 回 DOWN **不會**觸發 systemd 重啟 process——DB 斷線時重啟只會讓新 process 再等一輪重試，沒有幫助。readiness DOWN 的用途是給外部負載平衡 / 監控排除流量或告警，不是重啟訊號。

## DB 斷線復原觀察方式

DB 不可達不阻擋啟動；process 存活、`/policy` 照常回應，`/actuator/health/readiness` 的 `db` component 為 DOWN，`db_health{node}` gauge 為 0。復原不需要重啟：

```bash
curl -s localhost:8080/actuator/health/readiness | jq .
# 斷線時：{"status":"DOWN","components":{"db":{"status":"DOWN","details":{"reason":"migration not complete","lastError":"..."}},"nfs":{"status":"UP",...}}}
# DB 恢復、下一輪重試（預設 5 s）跑完 migration 後自動轉 UP，不需重啟 process。
curl -s localhost:8080/actuator/prometheus | grep -E '^db_health'
# db_health{node="P1",} 1.0
```

## 設定檔格式

見 `docs/superpowers/plans/P02-sync-service-skeleton.md`「設定檔格式」。`peer_token_sha256` 放 operational 段：`printf '%s' "$TOKEN" | shasum -a 256`。設定檔範例的頂層鍵為 `policy.deployment`（此 deployment 的識別字串）與 `policy.namespaces`（登錄的 Namespace 清單）；未登錄的 Namespace 一律不允許 write。

## 端點

| 路徑 | 認證 | 說明 |
| --- | --- | --- |
| `GET /policy` | 無（Node 內） | `{"version": N, "policy": {...}}`，library 用 |
| `GET /actuator/health/readiness` | 無 | `db`、`nfs` 兩個 component |
| `GET /actuator/health/liveness` | 無 | process 存活 |
| `GET /actuator/prometheus` | 無 | 指標；`node` 標籤自動附加 |
| `/pending`、`/file/**`、`/report`、`/received` | `Authorization: Bearer <token>` | Node 間端點；本模組只提供 filter，端點由 P04 起實作 |

401 = 無 / 未知 token；403 = `target` 參數與呼叫者身分不同（D14 修 2）。`/received` 不套用 target==caller 規則（只列出 caller 為 Source 的資料列由 P04 實作）。Node 內端點（`/policy`、`/actuator/**`）不認證——同主機群、Node 內視為信任邊界內。TLS 由 `server.ssl.*` 部署設定提供（D14），本模組測試走明文；生產環境必須在部署設定啟用 HTTPS，本模組不強制、不驗證。

認證檢查範例：

```bash
# 無 token → 401
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/pending

# 已知 token → 200，回報解析出的呼叫者身分
curl -s -H "Authorization: Bearer $P2_TOKEN" localhost:8080/pending

# target 參數與呼叫者不同 → 403（僅 target==caller 規則適用的端點，/received 不適用）
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $P2_TOKEN" 'localhost:8080/pending?target=P3'
```

## 指標（P02）

`active_config_version{node}`（目前生效版本號）、`activation_failure_count{node}`（單次 process 的 0/1 gauge：本次啟動有無因 candidate 驗證失敗而留在原地，process 無狀態，D34；歷史用 `max_over_time` 看）、`db_health{node}`（1 = migration 完成且可連線）、`storage_health{node}`（1 = NFS root stat 成功）。

```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^(active_config_version|activation_failure_count|db_health|storage_health)'
# active_config_version{node="P1",} 3.0
# activation_failure_count{node="P1",} 0.0
# db_health{node="P1",} 1.0
# storage_health{node="P1",} 1.0
```

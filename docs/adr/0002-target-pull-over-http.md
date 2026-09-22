---
status: accepted
date: 2026-09-22
---

# 傳輸採 Target pull over HTTP，Target 端無持久狀態

Target Node 的 sync service 週期向每個 Source 拉取待辦（`GET /pending`）、streaming 下載（`GET /file`）、本地驗證後 link 發布、回報（`POST /report`）。義務、退避、狀態全在 Source；Target 只有 in-memory in-flight set 與 received 表。

理由：pull 讓流控成為內建行為——Target 只在自己 NAS 健康時拉、拉多快自己定，多個 Source 同時對一個剛復活的 Target catch-up 不會塞爆它；Source push 要另建 per-target rate limit、circuit breaker、Target 端 429 三層機制，是最難寫對也最難測（T16）的一塊。

## Considered Options

- **Source push（HTTP PUT）**：Source 立即知道失敗原因、lag 少一個輪詢間隔；但需三層流控，且多 Source 同時 catch-up 的合流問題要額外處理。否決。
- **NFS-to-NFS 直搬**：Source 主機 mount 所有 Target NAS；一顆遠端 NAS hang 會卡住 Source 主機的 kernel NFS client（`hard` mount 下無法 timeout），一個 Target 故障污染所有隔離，違反 SR-05 / DG-01。否決。
- **rsync**：批次搬小檔效率高，但 digest 驗證、received 索引、Deep check 仍需 Target 端服務，省不掉服務只多一個外部 process。否決。
- **NAS 原生複寫**：volume 級、不能依 Data class 選 Target、發布時機不受 Framework 控、vendor lock-in。否決。

## Consequences

- Lag 多一個輪詢間隔（1–5 s）；失敗原因要靠 Target `/report` 回報才可見。
- Target 停機 = 不來拉；Source 以 staleness 推導 UNREACHABLE，不需探測。
- 續傳、Data class 優先級、快慢通道皆為純加法，v1 不做。
- 若未來納入跨部署範圍的 WAN，pull 仍成立，但 timeout 與續傳需重新檢視。

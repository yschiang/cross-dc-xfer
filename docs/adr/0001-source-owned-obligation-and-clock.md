---
status: accepted
date: 2026-09-21
---

# Replication obligation 由 Source Node 擁有，lag / age 時戳一律以 Source Node 時鐘為準

義務（File identity × Target）的權威狀態、retry 計數、backlog 與 reconciliation 的「應有集合」都由 Source Node 持有並持久化；Target Node 只保存 Completion evidence 供 Source 在紀錄遺失時查證收斂。Replication Lag 與 Oldest unfinished age 的起點（Source Ready）與終點（Source 收到 Completion evidence）都用 Source Node 時鐘打，不做跨 Node 時鐘比對。「Source Node 時鐘」= 該 Node 內 NTP 同步的各主機（App 主機打 manifest 時戳、sync 主機打 DONE 時戳）。

理由：Source 是 File identity 的唯一權威、持有有效資料與 Source Ready 集合，RC-01 要求的「不靠 task 紀錄推導義務」只有在 Source 端才做得到；單一時鐘則讓 §18 不需要定義跨 Node 時鐘誤差與校時依賴。

## Considered Options

- **Target 擁有義務（pull 模型的自然形狀）**：Target 停機 24 小時期間義務無處保存，AC-NODE-03 只能靠 Source 另存一份，等於雙重權威。否決。
- **各 Node 本機時鐘 + NTP**：需在 SLO 中定義允許誤差並驗收校時，且 lag 為負值時無法區分是時鐘漂移還是資料錯誤。否決。
- **Control Plane 當時鐘權威**：違反 CP 不得為 Data Plane 即時依賴。否決。

## Consequences

- Lag 多算一段 Target → Source 回報延遲；同一部署範圍的 LAN 內可忽略，但若未來納入跨部署範圍的 WAN，此定義需重新檢視。
- Push / pull 是設計自由，但無論何者，Target 端不得成為義務狀態的唯一保存處。

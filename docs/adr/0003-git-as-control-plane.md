---
status: accepted
date: 2026-09-22
---

# 沒有 Control Plane process：git repo 為設定真相，CD 下發，CLI 扇出為全域視圖

Config（Policy + operational policy + version）是 git repo 中不可變的 `configs/v<N>.json`，發布 = merge 改 `latest` 的 PR；既有 CD pipeline 把檔送到各 sync 主機本機磁碟，Node 端自行驗證、啟用、保留 LKG。全域視圖由 CLI 依 Policy 的 Node 清單扇出各 Node 的 `GET /status`。

理由：spec §11 對 CP 的要求（版本化、稽核發布者與時間、各 Node 採用狀態、非 Data Plane 即時依賴）用 git commit + Node metric + CLI 扇出全部滿足；v1 Policy 不可變、operational policy 變動頻率極低，一個常駐服務的營運成本換不到任何功能。

## Considered Options

- **獨立 CP 服務，Node 輪詢**：自助發布、Node 上報集中顯示；但多一個要部署、監控、備份的 process，且它做的事 git + CD 已經在做。否決。
- **Node 直接輪詢 git raw 端點**：與 CD 下發等價，差別只在連線方向；因 CD 已能進 sync 主機，選 CD 免去每台主機的 git token。若 CD 不可用可隨時切換，Node 端序列不變。

## Consequences

- 「CP 壞掉」= git 或 CD 不可用 = 不能發新版；資料流、重啟恢復、本地查詢全部不受影響。
- 新 Node 首次初始化需人工放第一份 active.json，不在 AC-CP-02 保證內。
- v2 開放 Policy 變更時，Node 端「Policy 段須與 active 完全相同」的檢查是唯一要改的點；屆時可能需要真正的 CP 服務做跨 Node 的變更協調。

# 世界晨報 — 開發交接與待辦

## V2.0 基準（2026-11-22）

原始碼 commit `04d2f0bafb6c5f601b356634ef3808509fdf11c8` 的 GitHub Actions 建置 `44964253602` 成功。已有五頁導覽、SQLite 新聞資料庫、歷史搜尋與未讀、關鍵字追蹤、修改紀錄、Gemini 手動翻譯與分析問答、檔案選擇器備份還原、通知測試與早晚簡報。尚未在使用者 OPPO 真機上完整驗證。

## 下一階段：先驗證通知，不任意擴充 UI

1. 優先完成 Firebase 遠端發送端授權（main 分支已有 news-push 工作流程），用 GitHub Actions 加密 secret 設定最小權限服務帳戶，執行手動遠端測試。確認鎖屏、背景、App 未強制停止時的接收狀態；記錄系統通知權限、ColorOS 自動啟動/電池管理與 FCM 訂閱狀態。本機測試不等於遠端測試。
2. 驗證 V2.0 安裝、資料遷移、SQLite 離線/去重/還原、圖片快取限制及三種 Widget。
3. Gemini：讓使用者自行在手機輸入未啟用付費的專案 API Key，測試模型可用性、繁中輸出、錯誤與快取、15 次本機每日請求上限。現在的上限不代表供應商免費額度保證。
4. Google Drive：先測試 Android 檔案選擇器指定備份檔、持久 URI 授權及每日排程；目前不是直接 Drive API 跨裝置同步。若要升級帳號整合，另行取得 OAuth 授權並僅存取 App 建立的檔案。
5. 視覺：對照 `DESIGN.md` 及概念圖，逐頁檢查實機排版。加強知識庫自然語言搜尋、同事件合併、已讀與閱讀位置、資料管理、可設定晨晚報時間、版本更新通知。未實作的功能不得稱已完成。

## 開發入口

`MainActivity.kt`：Compose 介面與今日新聞同步；`LiveNewsStore.kt`：RSS；`NewsArchive.kt`：SQLite 與備份；`PersonalAi.kt`：Gemini；`PersonalPages.kt`：追蹤、知識庫、設定及診斷；`NewsNotifications.kt`：通知；`WorldBriefApplication.kt`：Firebase 客戶端匯入及圖片快取；`HeadlineWidget.kt`/`.java`：桌面小工具。

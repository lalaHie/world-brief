# 世界晨報 — 原生 Android 版 1.2

## 用 Android Studio 開啟
1. 解壓縮 ZIP。
2. 請解壓縮至全英文路徑，例如 C:\\AndroidProjects\\WorldBrief，選擇 Open，選取 android 資料夾（含 settings.gradle）。
3. Gradle JDK 選擇 21 或 17（不可用 JDK 25），安裝 Android SDK 35，等待 Gradle Sync。
4. 打開 app/src/main/java/tw/worldbrief/app/MainActivity.kt，找到 HomePreview，選擇 Split 或 Design 預覽。
5. 若要實際操作，選擇已建立的 Android 模擬器及 WorldBrief 執行配置，按 Run。若內嵌模擬器連線卡住，可沿用獨立視窗。更新時固定使用相同英文專案路徑並保留本機 IDE 設定。

預覽使用示範資料；實際執行會先讀取內建或已下載的新聞，再從 GitHub Pages 同步。背景更新由 Android WorkManager 排程，系統可能依省電設定延後執行。

## 架構
- V1.2 依報刊設計稿重做首頁：大型晨字標誌、報頭、單欄大圖與金色標題線、金色分類列、左圖右字新聞列，以及自訂金線導覽。頭條優先採用當日有合法來源圖片的新聞；沒有相關圖片時仍顯示文字，不使用無關配圖。
- Kotlin / Jetpack Compose / Material 3
- 新聞仍來自 https://lalahie.github.io/world-brief/news.json
- 本機快取、搜尋、動態分類、頭條卡片及獨立閱讀頁面
- 底部導覽：今日、即時、設定（已移除收藏及收藏按鈕）。
- 桌面小工具：長按桌面空白處 → 小工具 → 世界晨報。2×2 顯示頭條、拉寬顯示圖片與摘要、拉高顯示頭條與三則新聞。實際版型依啟動器提供的尺寸調整。小工具內可更新及點擊閱讀；設定頁可選擇今日、台灣或國際（全域設定）。
- 首頁下拉更新、頭條圖片、可選擇新聞通知與通知圖片。
- 報刊介面、金色旭日晨字品牌、圖文/精簡閱讀模式、字體大小、深色模式、今日五分鐘、事件相關報導、手機端語音朗讀。
- 新聞查證狀態僅顯示資料來源提供的明確查證與證據；未查證內容標記初步消息，文字相似度只做相關報導候選，不能據此確認事實。
- 正式國際新聞透過手機端 ML Kit 翻譯（英文至中文再轉繁體），首次 Wi-Fi 下載模型，標題自動翻譯、內文點選翻譯，快取避免重複翻譯。

## 台灣即時新聞
- 使用中央通訊社、自由時報、公視新聞、報導者及民報的公開 RSS，國際頁使用 BBC、The Guardian 及中央社和自由時報國際頻道。限個人非商業閱讀；媒體可能暫時無法連線或停止提供 RSS。
- 自訂新聞來源設定支援新增公開 HTTPS RSS（媒體名稱 | 台灣或國際 | 網址），自訂國際來源預設英文。
- 進入即時頁會同步，停留其間每五分鐘檢查，也可以下拉更新。
- 篩選台灣時間當天、與台灣相關且具公共利益的新聞，使用關鍵字規則排除低相關事件；不等同 AI 審核，可能有漏選或誤選。
- 按時間排序，依網址與標題去重；不同標題的同一事件仍可能重複。
- 更新失敗保留上次快取，部分來源失敗時顯示提示。
- 圖片顯示載入中提示，失敗時嘗試合法的 Wikimedia 原圖網址，仍失敗就收起圖片區域。沒有圖片的新聞不預留空白。
- 官方 RSS 說明：https://www.cna.com.tw/about/rss.aspx 、https://service.ltn.com.tw/RSS

## 新聞推播
未設定 Firebase 時，開啟設定內的「啟用新聞通知」，App 使用既有的每小時 Android 背景同步，在發現新新聞時顯示通知（系統可能延後）。第一次同步建立基準，不補發歷史新聞。

FCM 即時推播另需 Firebase 專案：
1. 在 Firebase Console 建立專案並新增 Android App，套件名稱為 tw.worldbrief.app。
2. 將下載的 google-services.json 放到 android/app/，重新同步 Gradle。
3. 打開 App 啟用通知；App 加入 world-brief-news 主題。
4. 由可信任的後端使用 Firebase Admin SDK 或 HTTP v1 發送資料訊息至該主題。資料欄位：id、title、summary、url、image、category、type（breaking 或 hourly）。
5. 發送端必須在新聞發布後執行，並去重；切勿將服務帳戶私密金鑰放入 App 或公開 GitHub。

目前已內附世界晨報的 Firebase Android 設定檔（worldbrief-2e5ff），直接同步 Gradle 並重新執行 App 即可。新聞發送端也已準備，待設定安全的伺服器憑證並測試通知後才算完整啟用。網站資料仍使用原有的每小時更新。勿擾時段及通知分類已在手機端提供，所有外部付費服務須另行取得同意。

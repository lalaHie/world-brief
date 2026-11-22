# 世界晨報 — Codex 開發規範

## 專案界線
- Android 原生 App 維護分支：`native-v1`（目前版本 2.0.0，versionCode 20）；此名稱是歷史沿用，不代表版本 1。
- Android Studio 專案根目錄 `android/`，套件 `tw.worldbrief.app`，Kotlin / Jetpack Compose、Gradle wrapper 8.11.1、Android SDK 35、JDK 17 或 21。
- `main` 是既有新聞網站、GitHub Pages 及新聞發布/推播流程；Android 工作不得直接合併到 main 或改動網站資料格式/排程，除非使用者另外授權。
- 保留 V2.0 既有功能、SQLite 資料及使用者設定；版本更新不可要求刪除資料或解除安裝作為預設做法。新增資料庫結構須有移轉策略。

## 設計與內容
- 以 `android/DESIGN.md` 及其中連結的 V2.0 概念圖為正式設計基準。新功能沿用米白、森林綠、金色、晨字旭日枝葉標誌、報刊標題、左圖右文、細線分隔、金線選中導覽。
- 頁面：今日、即時、追蹤、知識庫、設定；保留三種 Widget。使用台灣繁體中文，保留深色模式、字體大小、朗讀、下拉更新。
- 新聞以可信來源、連結、時間與查證狀態為依據；AI 生成分析需標示推論與資料不足，不能把相似報導當作查證；不得用無關圖片補位。

## 整合與隱私
- Firebase、Gemini、Google Drive 均為選用功能，離線閱讀與原始新聞同步不依賴它們。不要擅自啟用計費、擴大權限或公開私人設定。
- `android/app/google-services.json` 已列入 ignore；服務帳戶私鑰、API Key 不可出現在 Git、APK、日誌或聊天。Gemini 金鑰由使用者輸入，裝置端 Keystore 保護；不等同無法遭裝置端抽取。應確認免費專案、不假定會員含 API。
- 推播優先改善 OPPO ColorOS 鎖屏/背景體驗。區分本機通知、WorkManager、真正 FCM；不得宣稱未測試的遠端推播已正常。服務端須用 GitHub Actions secret `FCM_SERVICE_ACCOUNT`，避免把部署憑證放進 App。

## 工作方式
- 先讀 `android/START-HERE.md`、`android/FCM-SETUP.md`、`android/ROADMAP.md`，再檢查現有程式及 Git 差異。
- 使用 `cd android && ./gradlew assembleDebug --no-daemon` 驗證編譯；Windows 使用 `gradlew.bat`。若本機無 SDK，使用此分支既有 Build Android APK workflow。不要將 build 成品或 IDE 快取提交到 Git。
- 如有實際行為變動，應新增或執行適切測試，明確區分編譯成功、模擬器測試、真機測試及外部服務授權結果。
- 未經使用者要求，不改整體品牌/排版、不發布網站、不開通付費服務、不重寫 Git 歷史。交付時附上變更、檢查結果、待設定事項，必要時提供 APK。

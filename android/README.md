# 世界晨報 Android 小工具

此資料夾是原生 Android Studio 專案，提供主畫面頭條小工具，並以 WebView 開啟完整 PWA。小工具從現有 `news.json` 讀取最新日期的第一則新聞，顯示分類、標題、摘要、來源和發布時間；點擊卡片開啟完整報導，點擊右上角圖示立即更新。

## 安裝與預覽

1. 使用 Android Studio 開啟 `android/` 資料夾。
2. 安裝 Android SDK Platform 37、Build Tools 36.0.0 和 JDK 17。
3. 在此資料夾執行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`），或在 Android Studio 同步並執行。
4. 安裝 `app/build/outputs/apk/debug/app-debug.apk` 到手機。
5. 在 Android 主畫面長按空白處，選擇「小工具」→「世界晨報頭條」，將卡片加入主畫面。

專案使用 Android Gradle Plugin 9.5.0 與 Gradle 9.6.0。一般 Android 主畫面啟動器支援 App Widget；第三方啟動器可能有自己的限制。系統每小時請求一次更新，Android 仍可能因省電或背景限制延後更新；右上角可手動重整。

## 資料與限制

- 資料來源：`https://lalahie.github.io/world-brief/news.json`
- 「頭條」取最新日期版本中排序第一則新聞。若要更精確控制頭條排序，可在新聞資料中加入 `featured: true` 欄位，並由資料端明確標記。
- 點擊卡片開啟 PWA 對應報導；目前需與網站上的 PWA 版本一同部署。
- 此為可建置的 Android 原始碼專案，尚未附 APK。

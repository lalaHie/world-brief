# 世界晨報 1.0：免費推播接入

目前未綁定帳號、信用卡或任何付費服務。沒有 Firebase 設定時，原本的本機每小時背景新聞通知照常運作。

需要接入時請使用自己的 Google 帳號，在 Firebase Console 建立免費 Spark 專案，再註冊 Android 套件 tw.worldbrief.app，將 google-services.json 放入 app/。這個設定檔是 Android 客戶端設定，本機編譯即可使用；不要上傳服務帳戶私密金鑰到 App。

## 發送端
FCM 並不會自己判斷新聞何時發布。可在原有 GitHub Pages 的新聞資料更新流程後，使用獨立的 GitHub Actions 工作發送資料訊息。先確認 GitHub 帳號的免費 Actions 額度及 Firebase 仍在 Spark，再設定 GitHub Actions 密碼庫及服務帳戶的最小必要權限。不要將私密金鑰放進公開儲存庫或聊天訊息。

App 已接收 world-brief-news 主題的 data message；請不要同時使用 notification payload，以免繞過 App 的分類及勿擾規則。

資料屬性（皆為字串）：
- id：穩定且唯一的文章識別碼
- title：新聞標題
- summary：短摘要
- url：原始文章 HTTPS 網址
- image：允許引用的圖片 HTTPS 網址，可省略
- category：新聞分類，例如 政治 / 台灣 / 國際
- type：breaking 或 hourly

發送端應當只在新增新聞時發送，使用已發送識別碼去重；未經核對不應把一般報導當作已證實重大快訊。設定完成後，先在 Firebase Console 或測試發送端確認一則通知，再啟用自動化。

FCM 接入與真正即時推播並未完成實際連線驗證；需要使用者建立免費 Firebase 專案後才能完成。

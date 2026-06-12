# 📰 新聞報報

無廣告、多來源的新聞閱讀 Android App。使用 WebView + RSS/HTML 抓取，純本地端抓取新聞，無需登入、無追蹤。

## ✨ 功能特色

- **多來源整合** — 同時聚合 ETtoday、MSN、Yahoo奇摩、快科技等新聞
- **無廣告** — 內建廣告攔截，閱讀文章時不顯示廣告
- **分類篩選** — 即時、政治、財經、社會、運動、星聞、國際
- **來源篩選** — 可依新聞來源過濾顯示
- **深色模式** — 支援亮色 / 深色主題切換
- **圖片預覽** — 新聞列表顯示縮圖，提升閱讀體驗
- **WebView 閱讀器** — 點擊新聞直接開啟內建閱讀器

## 📱 支援的新聞來源

| 來源 | 方式 | 分類 |
|------|------|------|
| ETtoday | RSS (FeedBurner) | 即時、政治、財經、社會、運動、星聞、國際 |
| MSN | REST API | 自動依 URL 分類 |
| Yahoo奇摩 | RSS | 即時、財經 |
| 快科技 | HTML 抓取 | 科技 |

## 🔧 技術架構

- **前端**：單一 HTML 檔案（vanilla JS + CSS）
- **後端**：Android Java + WebView
- **資料抓取**：RSS XML 解析 + MSN JSON API + HTML 正則抓取
- **廣告攔截**：網域黑名單 + CSS 隱藏 + JavaScript 注入

## 📁 專案結構

```
news-app/
├── index.html              # 主要 UI（WebView 載入）
├── privacy-policy.html     # 隱私權政策
├── screenshots/            # App 截圖
└── android/                # Android 原始碼
    ├── app/
    │   ├── src/main/
    │   │   ├── java/com/newsapp/
    │   │   │   ├── MainActivity.java      # 主畫面 + 新聞抓取
    │   │   │   └── ArticleActivity.java   # 文章閱讀 + 廣告攔截
    │   │   ├── assets/
    │   │   │   └── index.html             # WebView 內嵌頁面
    │   │   └── AndroidManifest.xml
    │   └── build.gradle
    ├── build.gradle
    └── settings.gradle
```

## 🛠 編譯 APK

```bash
cd android
./gradlew assembleRelease
```

產生的 APK 位於：`android/app/build/outputs/apk/release/`

## ⚠️ 注意事項

- 本 App 僅供個人學習用途，新聞內容版權歸各來源所有
- RSS 來源可能因供應商變動而失效，需定期檢查更新
- MSN API 金鑰為硬編碼，若失效需替換

## 📄 授權條款

MIT License

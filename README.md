# TxtReader

自用 TXT 電子書閱讀器，走靜讀天下路線：**自繪引擎，不用 WebView/MuPDF**。
夜間黑底、秒開秒翻、支援簡轉繁、TTS 朗讀、Dropbox 進度同步、APP 內自動更新。

## 功能

- 自繪排版：`Paint.breakText` 斷行，只排可見頁；段首空兩格、段間距、左右對齊可調
- 秒開大檔：mmap＋位元組行偏移索引，掃一遍順手收章節（正則）
- 簡轉繁：官方 OpenCC 詞庫（s2t），顯示時即時轉；「吃」保留台灣用字不轉喫；第二層台灣用語（視頻→影片、軟件→軟體…，有歧義的不收）
- 書架＋歷史：Room 存進度（行號級），開 APP 自動回上次那本
- 備份／還原：JSON 匯出入；Dropbox 自動同步進度＋書單（App folder，登入一次一直有效）
- TTS：從目前行開始念，跟讀高亮＋跟隨翻頁，來電自動暫停，耳機鍵支援
- 自動更新：開機每日檢查 `version.json`，有新版下載安裝
- 顯示設定：字體大小、行距、字距、邊界、字色、自選字型檔（ttf/otf/ttc）全部記憶

## 建置

```bat
gradlew.bat assembleRelease
```

產物：`app\build\outputs\apk\release\TxtReader.apk`（已自動簽名）。

裝到手機（只裝本尊，不長分身）：

```powershell
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb install --user 0 -r "C:\TxtReader\app\build\outputs\apk\release\TxtReader.apk"
```

怪問題先跑 `gradlew.bat clean` 再 build。APK 不要用通訊軟體傳（會壞），走 USB 或 adb。

## 發版（一行）

```bat
.\release.bat 1.2 "這版做了什麼"
```

會做：版號 +1 → build（失敗自動還原）→ commit＋push → 建 GitHub release＋上傳 APK。
手機隔天開 APP 自動提示更新，或按設定裡的「檢查更新」。

前提：

- `github-token.txt` 放 PAT 第一行（本機專用，**絕不進版控**）
- `versionCode` 每次一定要 +1（腳本自動做），不然手機比不出新舊

## 簽名與備份（重要）

- `txtreader.jks`＋`gradle.properties` 裡四行密碼：**一起備份好**，換電腦兩份都要帶走
- 這兩樣都不進版控；丟了就只能換包名重裝，書架資料要重來
- 同一把 key 才能覆蓋安裝；Studio 按 Run 是另一把 debug key，資料各存各的

## Dropbox 同步設定（做過一次就不用重做；refresh token 不會 7 天過期）

1. https://www.dropbox.com/developers/apps → Create app
2. Choose an API：**Scoped Access** → Type of access：**App folder** → Name 取 `TxtReader` → Create
3. Permissions 頁籤：`files.metadata.read`、`files.content.read`、`files.content.write` 打勾 → Submit（找不到就全開 files.* 也行，自用）
4. Settings 頁籤：複製 **App key**
5. 貼到兩個地方（同一串，不要加減字）：
   - `DropboxSync.kt` 的 `APP_KEY = "..."`
   - `AndroidManifest.xml` 裡 `android:scheme="db-..."` 的引號內（保留 `db-` 前綴）
6. 重跑 `gradlew.bat assembleRelease` 裝上，書架按「同步」→ 瀏覽器登入一次，以後不用再登

規則：只同步進度＋書單（書檔本體不同步）；合併用**檔名**對（跨裝置 Uri 對不上），所以兩台手機放**同名**書檔；別台獨有的書不寫入本機書架。

精度：同步到「同一句話」（原文行＋段內偏移），不是「同一頁」——兩台字體／螢幕／邊距不同，同一行會落在不同頁，差幾頁是正常的。切手機前在舊手機按一次同步誤差最小（手動同步會先推後拉）。

## 操作備忘

- 點上 40% 上一頁、下 40% 下一頁、中間開關選單；上下滑也可翻頁
- 長按畫面＝丟字自我檢測（分頁數學的真機證據）
- 書架長按刪書；朗讀中手動翻頁會暫停跟隨，翻回朗讀頁自動接上
- 同步顯示「授權失效」＝ 去 Dropbox 網頁端收回過授權，重按同步登入即可（不會像 Google 那樣定期過期）

## 1. 設定與偏好儲存 (Settings and Preferences)

- [x] 1.1 於 `MainActivity.kt` 實作音量鍵翻頁設定開關：在 `openDisplaySettings` 加入 CheckBox 與讀取/寫入 `volumeKeyPaging` 偏好設定（實現 `Display setting toggle in openDisplaySettings` 與 `Volume key paging setting toggle`），驗證方式為確認設定介面勾選狀態與 SharedPreferences 寫入結果。

## 2. 音量按鍵攔截與翻頁 (Volume Key Navigation and TTS Handling)

- [x] 2.1 於 `MainActivity.kt` 攔截音量按鍵事件：在 `MainActivity dispatchKeyEvent` 攔截 `KEYCODE_VOLUME_UP` 與 `KEYCODE_VOLUME_DOWN`（實現 `Intercept volume key events in MainActivity dispatchKeyEvent` 與 `Volume key page navigation`），在 `repeatCount == 0` 時分別觸發 `reader.prevPage()` 與 `reader.nextPage()`，驗證方式為編譯通過並確保翻頁邏輯正確呼叫。
- [x] 2.2 於 `MainActivity.kt` 確保朗讀時優先調節音量：當 TTS 播放中（`speaker.isPlaying()`）時放行音量鍵事件（實現 `Priority for TTS playback`），由系統處理音量調節，驗證方式為檢查判斷條件並以 `./gradlew compileDebugKotlin` 驗證編譯無誤。

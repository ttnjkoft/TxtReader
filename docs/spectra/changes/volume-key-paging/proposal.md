## Why

在單手持握手機閱讀電子書時，使用者希望能夠透過實體音量按鍵（Volume Up / Volume Down）直接翻頁，減少手指頻繁觸控螢幕的疲勞，並提升閱讀體驗。同時需提供開關設定，且在語音朗讀（TTS）執行中保留音量鍵調節音量之功能。

## What Changes

- 新增音量鍵翻頁功能：在閱讀介面中，按下音量下鍵為下一頁，音量上鍵為上一頁。
- 設定選項：在「顯示設定」對話框中加入「音量鍵翻頁」核取方塊（預設開啟），並儲存於 SharedPreferences。
- 朗讀衝突處理：當 TTS 朗讀正在播放時，音量鍵恢復系統預設行為（調整媒體音量），不進行翻頁。

## Capabilities

### New Capabilities

- `volume-key-paging`: 支援在閱讀器中使用實體音量鍵翻頁，並支援在顯示設定中開啟或關閉該功能。

### Modified Capabilities

## Impact

- `app/src/main/java/com/example/txtreader/MainActivity.kt`: 攔截 `dispatchKeyEvent` 中的 `KEYCODE_VOLUME_UP` 與 `KEYCODE_VOLUME_DOWN`；在 `openDisplaySettings()` 與 `saveSettings()` 中加入音量鍵翻頁設定。

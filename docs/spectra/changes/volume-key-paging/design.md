## Context

目前 TxtReader 僅支援透過手勢滑動與點擊螢幕上下緣來切換上一頁／下一頁。在單手持機閱讀的情境下，頻繁點擊或滑動螢幕可能造成手指疲勞。使用者希望可以使用手機側邊的實體音量鍵來進行翻頁。

## Goals / Non-Goals

**Goals:**
- 在閱讀頁面下，按音量下鍵翻下一頁，音量上鍵翻上一頁。
- 當音量鍵觸發翻頁時，攔截按鍵事件，防止彈出系統音量調整面板。
- 在「顯示設定」中提供「音量鍵翻頁」開關（預設為開啟），讓不想使用的使用者可隨時關閉。
- 當 TTS 朗讀正在播放時，音量鍵不翻頁，交由系統調整媒體播放音量。

**Non-Goals:**
- 自訂長按音量鍵之特殊功能或反轉音量鍵方向（預設固定為：上鍵上一頁、下鍵下一頁）。
- 在書櫃、搜尋、設定對話框等非閱讀介面攔截音量鍵。

## Decisions

### Intercept volume key events in MainActivity dispatchKeyEvent

在 `MainActivity.dispatchKeyEvent` 中攔截 `KEYCODE_VOLUME_UP` 與 `KEYCODE_VOLUME_DOWN`：
1. 判斷 `volumeKeyPaging` 設定是否開啟（預設 `true`）。
2. 判斷目前是否正在朗讀（`::speaker.isInitialized && speaker.isPlaying()`）。若正在朗讀，不予攔截，走 `super.dispatchKeyEvent(event)` 調整音量。
3. 判斷是否有載入書籍且 `::reader.isInitialized`。
4. 在 `ACTION_DOWN` 且 `repeatCount == 0` 時，執行翻頁：
   - `KEYCODE_VOLUME_DOWN`: `reader.nextPage()`
   - `KEYCODE_VOLUME_UP`: `reader.prevPage()`
5. 對於 `ACTION_DOWN` 與 `ACTION_UP` 皆回傳 `true`，以避免系統音量面板被觸發。

### Display setting toggle in openDisplaySettings

在 `MainActivity.kt` 的 `openDisplaySettings()` 對話框中新增「音量鍵翻頁」CheckBox，讀取與儲存至 `prefs`（key: `volumeKeyPaging`，預設為 `true`），并在 `saveSettings()` 時一併同步。

## Implementation Contract

- **行為**:
  - 當使用者開啟書籍閱讀且未開啟 TTS 時，按音量下鍵進入下一頁，按音量上鍵回到上一頁。
  - 當 TTS 播放中時，音量鍵正常調整播放聲音大小。
  - 在顯示設定中關閉「音量鍵翻頁」後，音量鍵恢復為系統音量調節。
- **資料鍵名**:
  - SharedPreferences key: `volumeKeyPaging` (Boolean, 預設 `true`)。
- **介面調整**:
  - `openDisplaySettings`: 增加 CheckBox("音量鍵翻頁")。
- **驗收標準**:
  - 按下音量鍵下鍵能翻至下一頁；按下音量上鍵能翻至上一頁。
  - TTS 朗讀中按下音量鍵不會翻頁，音量能正常升降。
  - 取消勾選音量鍵翻頁後，音量鍵回歸系統音量調整。

## Risks / Trade-offs

- [長按音量鍵可能觸發多次事件] → 僅在 `repeatCount == 0` 時觸發單次翻頁，避免長按連續快速跳頁。
- [ACTION_UP 若未被消費可能造成系統音量提示] → 翻頁開啟時，`ACTION_DOWN` 與 `ACTION_UP` 均回傳 `true` 徹底攔截。

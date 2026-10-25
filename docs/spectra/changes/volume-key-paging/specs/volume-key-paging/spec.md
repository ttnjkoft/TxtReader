## Purpose

Allow users to navigate pages using physical volume buttons during reading, reducing touchscreen fatigue and enabling convenient single-handed operation.

## ADDED Requirements

### Requirement: Volume key page navigation

The system SHALL turn to the next page when the Volume Down key is pressed, and turn to the previous page when the Volume Up key is pressed, while reading and volume key paging is enabled.

#### Scenario: Volume down turns to next page
- **WHEN** user presses the Volume Down key while reading with volume key paging enabled and TTS not playing
- **THEN** the reader navigates to the next page and system volume is not altered

##### Example: Volume down on page 1
- **GIVEN** reader is displaying page 1 and volume key paging is enabled
- **WHEN** user presses KeyEvent.KEYCODE_VOLUME_DOWN
- **THEN** reader advances to page 2 and returns true to consume event

#### Scenario: Volume up turns to previous page
- **WHEN** user presses the Volume Up key while reading with volume key paging enabled and TTS not playing
- **THEN** the reader navigates to the previous page and system volume is not altered

##### Example: Volume up on page 2
- **GIVEN** reader is displaying page 2 and volume key paging is enabled
- **WHEN** user presses KeyEvent.KEYCODE_VOLUME_UP
- **THEN** reader returns to page 1 and returns true to consume event

### Requirement: Volume key paging setting toggle

The system SHALL provide a setting option in display settings to enable or disable volume key paging, persisting the preference across app launches.

#### Scenario: Disable volume key paging
- **WHEN** user disables volume key paging in display settings and presses a volume key while reading
- **THEN** the system adjusts audio volume and does not turn the page

##### Example: Volume key pressed when paging disabled
- **GIVEN** volume key paging setting is disabled in display settings
- **WHEN** user presses KeyEvent.KEYCODE_VOLUME_DOWN
- **THEN** system handles volume change and reader does not turn page

### Requirement: Priority for TTS playback

The system SHALL allow physical volume keys to adjust audio volume without navigating pages when text-to-speech reading is actively playing.

#### Scenario: Volume keys during TTS playback
- **WHEN** user presses volume keys while TTS is speaking
- **THEN** the system adjusts audio volume and the reader does not turn the page

##### Example: Volume key pressed during active TTS
- **GIVEN** TTS speaker is actively reading aloud
- **WHEN** user presses KeyEvent.KEYCODE_VOLUME_UP
- **THEN** system adjusts media volume and reader does not navigate page

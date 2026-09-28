# Reader follow, editing and About

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

## Cause and fix

The original highlight already used the actual prepared MediaPlayer chunk's UTF-16 document range. The scroll controller was wrong: any touch permanently set `follow = false` until a new Play, and its pre-draw callback had no playback-state guard. It could therefore reposition paused text. Its immediate scroll-to-line-top also ignored clipping by the compact Reader's outer scroll container.

`ReadingHighlight` still owns one drawing span; no text selection or speech-position timer was introduced. It now follows only while PLAYING. A Reader touch cancels both native scroll animation and parent relocation. The highlight continues tracking actual chunk ranges while the user scrolls. Follow resumes at the next chunk or explicit playback Resume; a chunk arriving during a held gesture waits for release. Pause preserves the highlight and cancels movement. Stop clears the span.

Scrolling derives the current line bounds from Android's text Layout, including wrapping and variable line heights. It compares those bounds with the current scroll offset, text padding and visible clipped rectangle, then animates the smallest required scroll. Layout/IME changes recalculate the bounds. The native animation changes only scroll position, not highlight timing. Compose's existing [BringIntoViewRequester](https://developer.android.com/reference/kotlin/androidx/compose/foundation/relocation/BringIntoViewRequester) maps the visible text rectangle into any scrollable ancestors; no hardcoded screen offset is used.

## View and Edit modes

The existing native EditText is retained. New sessions start in View Mode: null key listener/input type, no focus/cursor/long-press selection, no soft keyboard, and ScrollingMovementMethod. Accessibility editing/selection actions are also removed and rejected; disabling only the key listener did not block Android's accessibility SET_TEXT action in testing.

The selected Material 3 Edit/Done editing chip restores the original key listener/input type and ArrowKeyMovementMethod for editing. Native typing, deletion, selection and clipboard operations remain available. Clear appears only while editing. Mode is restored across activity recreation; no alternate document copy or new text editor framework exists.

The header uses a wrapping Material layout so enlarged text cannot squeeze the document label into a vertical column. Scrollable native text retains touch interception during a drag, preventing the compact Reader's outer Compose scroller from stealing it. The listener never consumes touches; native text scrolling, clicks and selection still handle them.

Entering Edit Mode stops existing playback. Starting playback while editing remains safe, but any subsequent text modification stops the service, invalidates the span and prevents resuming stale generated chunks. A new Play snapshots the updated document through the existing generation path. Exiting Edit Mode preserves document content and hides the keyboard.

## About

Settings has one About navigation entry, not the old summary card. The existing bottom-sheet navigation opens a new Material 3 About presentation with version, local Pocket TTS reading, waveform/playback/speed/export, recording/import/trimming, DeepFilterNet3 Before/After, custom voices and privacy/consent information.

Old About dialog, repository-opening action, repository constant and obsolete localized About messages were removed. The new UI has no repository/source links. Existing component attribution remains; bundled voice-enhancement license notices are readable offline. The unchanged license text includes Apache's license URL, not a repository link. Native Pocket TTS, ONNX Runtime, recording, trimming and model assets were not replaced.

## Verification scope

`ReaderModesTest` checks read-only touch/key/accessibility behavior, native editing and clipboard operations, recreation, long wrapped text with variable-size spans, scroll-away/chunk transitions, a held gesture, pause/resume, stale-offset invalidation and About/navigation/notices. Existing keyboard tests explicitly enter Edit Mode. The real Reader MediaPlayer fixture test also verifies stopping playback when entering Edit Mode and when modifying text during playback.

Highlight tests inject deterministic chunk snapshots to verify layout separately from synthesis. Native Pocket TTS generation and perceptual spoken-chunk timing still require a physical ARM64 device; the emulator's native translation is unsuitable for that graph. No word-level timing is inferred.

Final build command: `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` with JBR 21. Successful; 21 unit tests passed, lint 0 errors / 25 warnings. All three ReaderModes tests also passed at 360 × 640 dp with 150% font scaling, including native manual scrolling, outer-container clipping/relocation, native editing and About notices. Light/dark/AMOLED About and read/edit screenshots were inspected; test screenshots are under `app/build/reader-modes-small-release`.

The subsequent bundled-English/trim-seeking continuation completed the interrupted regression verification: the 24-case full suite finished in 243.708 seconds with 22 passes and two explicit physical-ARM64 synthesis skips. ReaderModes, ReaderIme and existing Reader/media/theme tests passed. See BUNDLED_ENGLISH_AND_TRIM_SEEKING.md for the new work and final build results.

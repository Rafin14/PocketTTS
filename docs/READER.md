# Pocket TTS Reader: implementation and device checks

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

## Audit and changes

The starting app already had local Pocket TTS inference, model import, reference-WAV voice cloning, Android system TTS registration, a Reader, manual themes, and a media session. The decorative Reader image was already removed.

The audit found unsafe Stop/new-session coordination, pause state overwritten at chunk boundaries, incomplete audio-focus handling, no media position/metadata, no seeking/export, and no voice-recording/deletion UI. The original clause splitter also treated clause boundaries like ordinary spaces. These paths have been revised.

The UI now has Reader, Voices, and Settings tabs. Reader uses an independently scrolling editor constrained to available height; editing with the keyboard hides secondary controls. Text is saved asynchronously to an atomic private document file, with a short debounce; large text is not put into saved-instance-state parcels. The old preference-based document is migrated. Clear requires confirmation.

The UI now uses Jetpack Compose and the official Material 3 library, with a native Android editor embedded via AndroidView for long-document editing. System/Light/Dark/AMOLED preferences and existing documents remain compatible. Dark mode uses near-black backgrounds, translucent charcoal cards/sheets, low-contrast borders, muted periwinkle actions and green only for active playback/recording or a ready recording. Light mode uses opaque tonal surfaces; AMOLED uses a true-black main background. The removed decorative Reader image remains removed.

Colors, shapes, content colors and glass tokens live in PocketTheme.kt. PocketScreens.kt owns Reader, lazy voice lists, details, Settings, engine sheets and confirmation dialogs; VoiceRecordingDialog.kt uses the same Material 3 sheet. The existing Activity/controller retains repository, service, picker, microphone permission and draft contracts. Compose BOM 2024.12.01, activity-compose 1.9.3 and the Compose compiler plugin matching Kotlin 2.0.21 were added; SDK 35/minimum 26, AGP, NDK and native runtime versions were not upgraded.

The intentional glass fallback uses tinted surfaces rather than backdrop blur. Compose's standard blur modifier blurs its own content, not arbitrary content behind a card; native cross-window blur is Android 12+ and can be disabled by the OS. There are no screenshot-backed blurs, per-card RenderEffects or third-party blur libraries. See [Compose graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) and [Android window blur](https://source.android.com/docs/core/display/window-blurs). Content remains opaque and readable without effects. English fallback strings are preserved pending reviewed translations.

## Engine and playback

`PocketEngine` serializes native access and holds one cached model for the Reader and Android `PocketTtsService`. Idle models are released after 60 seconds. Model changes, imports, and voice deletion invalidate the native cache under the same lock. Reader synthesis releases the lock between chunks, so system TTS can use the same implementation. The original JNI, ONNX Runtime, and vendored inference sources are unchanged. Developer self-test actions remain available.

The Reader synthesizes natural-boundary text chunks on one worker, writing PCM16, mono, 24 kHz WAV chunks to private cache storage. Android `MediaPlayer` plays finished chunks in order. Playback starts after the first chunk is ready, rather than waiting for the entire document. Generation can continue while playback is paused, enabling more seeking and later export. Stop cancels generation cooperatively at the next native audio callback and immediately stops output. Old session callbacks cannot start a new session accidentally.

`ReaderPlaybackService` owns a framework `MediaSession` and media-playback foreground notification. Notification actions dispatch through the session controller; lock-screen/headset actions call the same service transport methods. The session publishes document title, actual player position, playback state, speed, available seek range, and final duration once known. Previous/next navigate generated chunks where available. Stop is also exposed as a custom action for modern system media controls. Exact system control layout depends on Android/device software.

The session uses local media routing and speech audio attributes. Focus loss, including ducking and permanent loss, pauses speech; resume is explicit and reacquires focus. Disconnecting a noisy audio route pauses playback. A partial wake lock protects synthesis while the screen is off; MediaPlayer manages its own playback wake lock.

Media-session notifications are exempt from Android 13's notification permission requirement, so the app does not add an unnecessary notification-permission prompt. References: [Android media notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [audio focus](https://developer.android.com/media/optimize/audio-focus), [MediaPlayer](https://developer.android.com/reference/android/media/MediaPlayer).

## Voices

Voices lists all repository voices with language/pack information, Preview and Use actions. The selected voice has a tonal surface, accent outline, checkmark, and accessibility selection state in every theme. Use updates selection without leaving Voices; changing the voice/pack stops an existing Reader session so Resume cannot silently use the previous voice. New imported/recorded voices are marked user-created and can be deleted with confirmation. Older manifests lack provenance, so their voices are conservatively labelled pack/legacy and protected from individual deletion.

Add Voice chooses an installed model pack, then offers WAV import or microphone recording. Microphone permission is requested only for recording and checked again when initializing the recorder. `AudioRecord` captures actual PCM16 mono at 24 kHz. Recordings must be at least three seconds and are capped at 30 seconds; the user can stop, preview, re-record, name, and save. The shared WAV writer writes/finalizes the RIFF header. Saving goes through the existing repository/reference-voice mechanism; native voice conditioning is still performed by Pocket TTS on synthesis. Imported samples must be uncompressed PCM or IEEE-float WAV with valid format and data chunks.

Short previews use one foreground-only MediaPlayer helper with audio focus. The compact player shows the voice, status, progress, pause/resume and Stop. Tap its title or drag upward to expand into a standard Material 3 sheet with seeking. Switching previews releases the previous player; completion/Stop removes the player. Previews stop when leaving the screen. Voice management/preview is blocked while Reader generation/output is active.

Recording has permission, ready, recording, processing, denoising, review, saving, and failure states. The waveform is a bounded 48-bar history of actual signed PCM16 microphone peaks, sampled in 50 ms blocks, not an animation. Duration derives from captured bytes. Updates end when recording stops. DeepFilterNet3 then processes the finalized WAV offline; review offers real Before/After waveforms, shared playback and Original/Enhanced selection. Enhanced is the default on success; failure keeps Original usable and offers Retry. Save stays fixed below the scrollable review content and above the keyboard. A recording stops and its unsaved temporary sample is discarded when leaving the screen, including rotation/backgrounding; the screen explains this. Saved voices retain both sources for comparison in voice details and become selected. See [DEEPFILTER.md](DEEPFILTER.md) for provenance, lifecycle and verification.

Deletion invalidates the native voice/model cache, checks ownership and path containment, removes the manifest entry atomically with rollback on failure, removes the sample, and clears a deleted default selection so repository fallback can choose an available voice. Exported audio is unaffected.

## Seeking and export

The seek bar controls real MediaPlayer seeks into the selected cached WAV chunk, with offsets translated to document time. During generation, the UI labels the current available duration as generated audio, not a predicted total. Seeking beyond generated audio is clamped. Final duration is known only after generation finishes. Replay within the cached audio is possible without re-synthesizing it using the seek bar/Resume; starting a new reading replaces the cache.

Export WAV becomes available when the whole document is generated. Android's Create Document picker lets the user choose a filename and destination; no storage permission is required. The service streams chunk PCM into one correctly sized WAV, without collecting the entire audio in RAM. Export reflects original-speed synthesis; the playback speed control is not baked into the file. Failed export reports that the destination may contain a partial file.

Limits: cached audio consumes disk space (roughly 173 MB/hour at 24 kHz mono PCM16); standard RIFF WAV supports less than 4 GiB. Chunk transitions can include short preparation gaps, and generation slower than playback causes buffering. Process death does not automatically resume/recover generated audio; the document and saved voices persist, and stale audio caches are cleaned on service creation. Export before leaving the app if the audio must be retained.

## Settings

Appearance: System, Light, Dark, AMOLED. Playback speed: 0.75x, 1x, 1.25x, 1.5x, 2x, persisted locally. Engine: model-pack selection/import/deletion, language/precision/private path, default voice, temperature (0–2), LSD steps (1–8), CPU threads (1–8), sentence pause (0–2000 ms), and maximum text tokens (10–200). Inference settings are existing engine capabilities. Stop generation before changing them.

## Build and checks

From PowerShell in the project root:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
& "$env:ANDROID_HOME\platform-tools\adb.exe" devices
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -n org.pockettts.android.engine/.MainActivity
```

Native dependencies are present locally, including ONNX Runtime 1.20.0. The model pack `PocketTTS-english-FP32.zip` is imported inside the app; it is not a replacement for the native runtime library. Do not change the existing ARM64-only build configuration to run an x86 emulator.

Host tests cover import path security, empty/large-document chunking, clause preference, Unicode boundaries, actual WAV header/PCM concatenation, invalid/empty WAV rejection, RIFF size limits, and seeking across chunk boundaries. A pre-existing test fixture containing a colon in a Unix document path was made host-portable for Windows. The test still checks a permitted document path; security policy was not weakened.

## Device acceptance checklist

The Material 3 migration and voice changes build successfully. There are 17 passing host tests, including theme contrast and signed PCM amplitude checks; lint reports zero errors (22 warnings). The final Android 16 emulator suite completed with six passing tests and one physical-ARM64-only test skipped, zero failures. The interrupted long-list fixture now uses a cache-owned reference as required by the recording import API. Instrumentation covers theme/navigation, a 260,000-character document, 30 added voices, focused-editor/IME transitions, preview switching/expansion/completion, and recording/re-recording/preview/WAV save/selection. Screenshots were inspected, including selected voices in light/dark/AMOLED, preview players, Add Voice, live recording, and saving with the keyboard visible. The emulator microphone supplies silence; signed amplitude response is unit-tested, but audible microphone quality and performance on physical hardware remain device acceptance items.

Keyboard crash status: no matching phone crash trace was supplied. The emulator passes the focused-editor + Play validation test with the keyboard left open; Play no longer clears focus or forces the IME closed, and snapshots the editable before playback. This does NOT establish the root cause or prove the reported native-playback crash is fixed. This x86 Android 16 (16 KB) emulator SIGILLs inside ONNX under ARM64 translation even without the keyboard. The physical-ARM64-only `ThemeSmokeTest#playbackAndBackground` now exercises composing text + Play, rapid Pause/Resume, keyboard close/open during real playback, background media, and Stop; it is intentionally skipped on x86. Run it on the affected phone and capture its crash log. No Pocket TTS/JNI/ONNX runtime was replaced.

To run the device tests without Android Studio (use a test device; tests temporarily modify voice selection and create/delete their own QA samples):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d install -r app\build\outputs\apk\debug\app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d shell am instrument -w -r org.pockettts.android.engine.test/androidx.test.runner.AndroidJUnitRunner
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d logcat -b crash -d
```

- Switch System/Light/Dark/AMOLED; verify system bars, dialogs, legibility, larger fonts, portrait/landscape, and keyboard layout.
- Paste a long document; scroll/select/copy/paste inside the editor. Check controls remain reachable. Rotate and reopen the app; verify saved text and cursor. Test Clear/cancel.
- Import the English model ZIP and play with Alba. Listen through multiple generated chunks and the final word. Repeat with a user voice.
- Pause/resume while loading and playing; repeat Play/Stop rapidly. Change voice after Stop. Seek backward/forward during generation and after completion; compare audible content with displayed position. Confirm seeking past the generated range is unavailable.
- Inspect notification shade and lock screen, including title and position; exercise pause/resume, stop and available previous/next controls. Test headset/Bluetooth buttons, unplugging headphones, another app taking audio focus, app backgrounding and screen lock.
- Deny microphone permission, then grant it. Record 3–30 seconds, preview, re-record, save with a name. Verify the saved voice after restarting. Test background/rotation during an unsaved recording. Confirm built-in/legacy voices have no Delete action. Delete a selected user voice and verify fallback/removal.
- After full generation, export a short and long document to a local destination. Play the exported WAV in another app and compare start/end/duration; export again while Reader is paused. Cancel the picker and test a failed destination without losing the cached audio.
- Verify empty input, missing model, missing voice, no microphone, low storage, audio-focus rejection and error recovery.
- Select Pocket TTS in Android system TTS settings and speak independently of Reader, including after importing/deleting a voice. Test system TTS while Reader generation is active to check serialized model access and focus behavior.
- Force-stop the app: no audio/microphone should remain active. Reopen: document/voices should persist, while the prior audio session is intentionally not resumed.

Useful diagnostics: `adb logcat -s PocketTTS AndroidRuntime`, `adb shell dumpsys media_session`, and `adb shell dumpsys audio`.

For the subsequent playback highlight, real PCM waveform, IME layout, WAV-import enhancement, icon and splash update, see [READER_ENHANCEMENTS.md](READER_ENHANCEMENTS.md). It supersedes the earlier import flow and lists the new checks and physical-device limitations.
# Sample trimming and playback speed

See [TRIMMING_AND_SPEED.md](TRIMMING_AND_SPEED.md) for the shared record/import trim workflow, non-destructive sample retention, live 0.05× playback-rate control, and verification scope.

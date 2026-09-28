# Source limits and UI completion — September 28, 2026

## Scope and preservation

The earlier Extract from Video flow was already implemented: Android picker, platform codec extraction, shared preview, trim, real DeepFilterNet3, original/trimmed/enhanced archives, save and voice selection. This continuation keeps that architecture and the existing Material 3/glass, typography, light/dark/AMOLED themes, native engine and bundled models. No dependencies or placeholder controls were added.

## Limits and memory

- `PcmWav`: shared 256 MiB uncompressed WAV-file cap (previously 64 MiB) and 1,800-second source duration. RIFF lengths, byte counts and duration arithmetic use `Long`; malformed format/data chunks are rejected with readable messages.
- `VoiceRecordingDialog` and `ModelPackRepository`: bounded streaming imports and shared validation, including voice preparation and archived source files. Localized size errors updated.
- `VideoAudioExtractor`: metadata duration and actual decoded PCM byte/duration guards use those same limits. Compressed video size is not mistaken for decoded WAV size. The decoder's unrelated 15-second stall timeout is unchanged.
- `WavSamples` / DeepFilter JNI: source inspection uses a 4,096-frame block and a fixed waveform envelope, checks cancellation between blocks, and accepts sources through 30 minutes. Import/scan/extraction remain on workers.
- Pocket TTS's WAV reference loader now streams/downmixes only the first 30 seconds, matching its existing voice encoder limit instead of loading the entire expanded WAV into RAM before truncating it.
- The separate **3–30-second recording/selected reference/DeepFilterNet3 window is intentionally unchanged**. A 30-minute source can be scanned and trimmed; this does not mean denoising or conditioning the model on 30 minutes. Reader export is not subject to voice-source caps.

## Taste Skill audit and fixes

Used `redesign-existing-projects` for an audit of actual emulator screenshots and live instrumentation flows: Reader, Voices/selection/details, Add Voice, recording, WAV/video import, trim, enhancement comparison/save, preview mini/expanded controls, Settings, engine settings, About, loading/errors, and all three manual themes. Kept this Android application's existing design rather than applying web-specific typography or decoration.

- Pinned bottom-sheet titles and an accessible 48 dp Close action remain available while content scrolls.
- Settings navigation rows now have chevrons; radio selection remains distinct.
- Voice mini-player has a tonal backing so list text does not bleed through it.
- Review exposes a real Adjust trim action without losing the original source.
- Save voice is distinct from choosing original/enhanced audio; missing-name feedback is visible beside Save.
- Failed imports have wired Choose another WAV/video actions; malformed WAV errors are readable.
- Add Voice/About guidance now includes video and source limits, without claiming the filename automatically names a voice.

No rename, batch-processing or text-file-import controls were invented: those workflows do not exist. Existing playback, seek, waveform, speed, selected-voice, keyboard and theme behavior remain in the regression suite.

## Verification

Executed on the API 36 Pixel 8 Pro x86_64 emulator:

- Final `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, and `lintDebug`: successful. 22 unit tests pass; lint has 0 errors and 22 warnings. APK ZIP 16 KiB alignment verification passes.
- Full instrumentation regression: 28 passes, 3 explicit ARM64-only skips (31 discovered; runner reports `OK (31 tests)`). Includes Reader/IME, media speed/seek/notifications, actual recording, waveform, trim, real DeepFilter inference, video extraction, save/selection, preview and themes.
- After adding the long-video fixture and exercising retry buttons: 3 targeted tests pass (long-video decode/rejection plus both limits/UI tests). Across these runs, 29 distinct instrumentation tests passed and 3 were hardware-skipped.
- Final UI build: 2 further UI checks pass at 360 x 640 dp with 1.3 font scale, covering sheet close/retry/adjust-trim and theme/navigation/large-document behavior. Emulator display/font overrides restored afterward.
- Viewed before/after screenshots, including mini-player backing, settings arrows, light/dark/AMOLED sheets and compact-screen trim/error layouts. Scrollable content can extend below the viewport; pinned Close/Save actions remain reachable.
- Final crash buffer is empty. Searches found no obsolete relevant 64 MiB or 10-minute source guard in application code.

Runnable checks added:

- `VoiceSampleLimitsTest`: exact 256 MiB accepted, next frame rejected; exact 30 minutes accepted, next sample rejected; forged unsigned RIFF length rejected without allocation.
- `VoiceLimitsUiTest`: actual near-256 MiB import/native waveform scan/tail trim, exact 30-minute scan, cancellation/cleanup and responsive main-thread access; persistent close, retry picker actions and adjust-trim/save validation across themes.
- `VideoImportTest.nearThirtyMinuteDecodeAndOverDurationRejection`: real 29:59 video-to-PCM decode and rejection of over-30-minute metadata. Tiny test-only fixtures contain a black video and silent AAC; not included in the app APK.

Test fixture reproduction:

```powershell
ffmpeg -f lavfi -i 'color=c=black:s=16x16:r=0.1' -f lavfi -i 'anullsrc=r=8000:cl=mono' -t 1799 -c:v libx264 -preset ultrafast -c:a aac -b:a 8k -movflags +faststart app/src/androidTest/assets/video/long-test.mp4
ffmpeg -itsoffset 3 -i app/src/androidTest/assets/video/long-test.mp4 -c copy app/src/androidTest/assets/video/over-duration-test.mp4
```

Physical ARM64 Pocket ONNX synthesis/voice quality still requires a phone: emulator ARM translation cannot run that stack reliably. Do not equate emulator UI/media/DeepFilter checks with that hardware test. Disk-full, every vendor codec, battery/GPU profiling and full TalkBack navigation were not fault-tested.

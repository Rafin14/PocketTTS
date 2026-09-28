# Extract from Video

Voices → Add voice → Extract from Video opens Android's `OpenDocument` picker for `video/*`. No new permissions, network requests, media dependencies, or native engine changes.

## Implementation

- `MainActivity.kt`, `VoiceControls.kt`, `PocketScreens.kt`: third creation card, picker result/cancellation, sheet ownership and lifecycle cleanup.
- `VideoImportDialog.kt`: cancellable worker, filename/duration/codec information, `TextureView` video surface, shared `SamplePreview`/`PreviewControls`, extraction progress/errors.
- `VideoAudioExtractor.kt`: `MediaExtractor` selects the first audio track; `MediaCodec` actually decodes it. PCM16 or float decoder output is streamed into PCM16 WAV, preserving source rate/channel count. Rejects protected tracks, malformed output, decoder stalls, absent audio, audio under 3 seconds, audio over 30 minutes or an uncompressed WAV over 256 MiB. These are shared source limits, not a compressed-video file-size cap. Codec/container support is device-dependent. Select a local seekable document; non-seekable provider streams may be rejected.
- `PcmWav.kt`: optional sample rate/channel count in the existing writer; default remains mono 24 kHz.
- `SamplePreview.kt`: same MediaPlayer lifecycle and controls, with an FD/surface input for video. No second playback engine.
- `VoiceRecordingDialog.kt`: takes ownership of extracted cache WAV and enters the existing inspect/trim flow. No duplicate waveform, trim, seek, denoise, save, or voice format.

The shared trimmer normalizes the selected 3–30 second region to mono 24 kHz before the existing DeepFilterNet3 path. Before/after preview, saving, selection and deletion are unchanged. Original extracted audio, trimmed audio and enhanced audio are archived by `ModelPackRepository`; preview/processing cache files are removed on save/cancel. Backgrounding cancels an unsaved video flow, just like the recording flow. Saves already committing retain existing atomic repository behavior.

## Verification commands

Original video-feature verification, September 28, 2026 on the API 36 Pixel 8 Pro x86_64 emulator (before the subsequent limits/UI follow-up; see `LIMITS_AND_UI_AUDIT.md` for current results):

- Debug APK and instrumentation APK build; 21 unit tests pass; lint: 0 errors, 23 existing warnings; ZIP 16 KB alignment check passes.
- Existing regression suite: 22 passes, 2 ARM64-only skips, including Record Voice, WAV import, Reader IME handling, trim seeking, media playback and themes.
- New video suite final run: 4 passes, 1 ARM64-only synthesis skip. The initial picker test clicked the thumbnail's external preview action; selecting the exact filename fixed the test. The final five-test run passed with that hardware skip.
- Inspected light/dark/AMOLED preview screenshots and the actual extracted speech waveform. Crash log empty after the final run.
- Not verified here: physical-device synthesis/voice quality, every vendor codec, disk-full injection, and maximum-size files. Those inputs have bounded validation/error paths, but were not all fault-injected. Native TTS/JNI/ONNX code is unchanged.

From the repository in PowerShell:

```powershell
# Set JAVA_HOME to your installed JDK 17+ directory first.
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am instrument -w -r -e class org.pockettts.android.engine.VideoImportTest org.pockettts.android.engine.test/androidx.test.runner.AndroidJUnitRunner
```

`VideoImportTest` exercises real decode/WAV peaks; no-audio, corrupt and short inputs; picker select/cancel; background/cancel cleanup; video playback and actual MediaPlayer seeking; existing audio seeking/trim; real DeepFilterNet3; before/after preview; original archive preservation; save and selection. It captures light/dark/AMOLED screenshots. The separate synthesis test skips x86 emulators because Pocket TTS's ARM64 ONNX native stack is not supported by their translator; run on an ARM64 phone to validate speech generation with the video-derived reference.

Test-only MP4 fixtures live in `app/src/androidTest/assets/video`, not the application APK. `speech-test.mp4` is eight seconds of the existing bundled Alba reference encoded as stereo 48 kHz AAC with a generated test video. `silent-test.mp4` has no audio; `short-test.mp4` has one second of 440 Hz audio. Desktop FFmpeg generated these fixtures; the Android app does not use FFmpeg. Example reproduction after building the bundled assets:

```powershell
ffmpeg -y -f lavfi -i 'testsrc2=size=320x180:rate=15' -stream_loop -1 -i app/build/generated/pocketAssets/pockettts/voices/alba.wav -t 8 -c:v libx264 -preset ultrafast -crf 36 -c:a aac -ar 48000 -ac 2 -movflags +faststart app/src/androidTest/assets/video/speech-test.mp4
```

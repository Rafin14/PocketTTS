# Sample trimming and playback speed

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

## Shared recording/import flow

`VoiceRecordingDialog` now routes both microphone completion and validated WAV imports through a shared TRIMMING phase before loading DeepFilterNet3. `TrimControls` uses Material 3's accessible RangeSlider, a real 512-bin peak envelope, selected/dimmed regions, hundredth-second timestamps, 0.1-second adjustment buttons, and a preview playhead. Both workflows use the same background executor, decoder, resampler and preview player.

`WavSamples` reuses the bundled dr_wav decoder and existing band-limited resampler in deepfilter_jni. Inspection streams 4096-frame blocks into a fixed 512-bin envelope; it never holds the full imported PCM. Applying or previewing decodes only the selected 3–30 seconds, downmixes and converts to mono PCM16 24 kHz. The full original is never overwritten. No additional dependency, model or download was added.

Selected preview plays that separate WAV, not an imprecise timer-bounded portion of the original. It therefore cannot play discarded leading/trailing audio. Pause, restart and seek use the existing SamplePreview. Selection changes stop preview; unchanged selections reuse the prepared file instead of decoding again. Conversion/inference/writing run off the UI thread. Closing queues cleanup behind native work; re-recording clears the selection and derived files.

The existing 64 MB WAV import limit and 30-second microphone limit remain. Longer imported WAVs can be scanned and trimmed to 3–30 seconds. Unsupported/corrupt/non-finite audio is rejected. Valid shorter/longer full samples can still be saved unchanged using Continue without trimming, with an explicit enhancement-limit message. The selected-preview/enhancement path requires 3–30 seconds; it never silently truncates a long file.

Continue without trimming retains the old full-sample enhancement flow for valid 3–30 second inputs. Trimmed review clearly labels its Before sample. Saving archives full `original.wav`, optional `trimmed.wav`, and `enhanced.wav`; Before playback in voice details uses trimmed.wav when present, otherwise the original. The chosen sample remains the regular voice WAV used by the unchanged cloning pipeline. Deleting a user voice continues to delete its archive through the existing repository operation.

## Speed root cause and fix

Reader audio is played by ReaderPlaybackService's per-chunk MediaPlayer, not directly by native synthesis. Previously MainActivity only saved a five-item preset index; it never changed the active MediaPlayer. The service's immutable Session.speed captured the initial value, including on subsequent chunks/resume. SamplePreview had no playback-rate handling at all.

Reader now stores a numeric rate, migrates the old preset preference on read, and sends live changes to the service. The session rate is mutable; each prepared chunk/resume applies the current rate with pitch fixed at 1.0. Changes during playback update the actual player and MediaSession's reported rate. Changes during preparation/seek/pause are retained and applied on start; they do not resume paused audio. This respects Android's [MediaPlayer PlaybackParams behavior](https://developer.android.com/reference/android/media/MediaPlayer#setPlaybackParams(android.media.PlaybackParams)): setting a nonzero rate on a prepared player starts it.

The Reader bottom sheet and expanded preview controls use 0.50–2.00× in 0.05× steps, with a numeric value and 1.00× reset. Reader selection persists across restarts/new generation/voice changes. Preview instances initialize from the Reader preference, retain their own adjustable audition rate across preview switches, and apply it on each preparation/resume. Changing Reader speed also updates the activity's existing preview instance. Audition changes do not modify saved audio or Reader preferences.

The native synthesis engine, ONNX Runtime, model files, Android system-TTS callback path, and exported PCM remain unchanged. Playback speed is a player effect, not a change to reference audio, voice-cloning input, generated-file duration or synthesis parameters. External apps using the system TTS engine do not inherit the Reader's playback preference.

## Verification

Final build on 2026-09-26: assembleDebug + assembleDebugAndroidTest + testDebugUnitTest + lintDebug successful (JDK 21); 21 unit tests passed, lint 0 errors / 27 existing warnings. The full 17-case emulator suite completed in 184.911 seconds: 16 passed, physical native generation skipped. After making trim busy state observable and adding the slider/background assertion, the final six trim/speed/voice workflow tests passed in 91.566 seconds. APK 16 KB zip alignment passed; deepfilter_jni LOAD alignment remains 0x4000. Bundled model SHA-256 is unchanged. Light/dark/AMOLED trim and speed-sheet screenshots were visually inspected.

Continuation check on 2026-09-27 recovered the final small-screen run: import/trim/preview/enhance/save/cancel passed at 360×640 dp with 1.3× font scale in 17.820 seconds. Its screenshots were inspected, and emulator size/density/font settings were confirmed restored. The crash log was empty. No unfinished implementation TODOs were found in the new trim/speed workflow.

Before new changes, the interrupted previous task was rebuilt and verified: 20 unit tests passed, lint 0 errors / 27 warnings, 12 emulator checks passed and one physical ARM64 test skipped. See READER_ENHANCEMENTS.md.

New checks in `TrimSpeedTest` exercise real selected PCM boundaries from a 60-second stereo 48 kHz WAV, bounded waveform data, invalid ranges, short/corrupt inputs, trim preview completion/restart, actual DeepFilter inference, archive preservation, save/selection, cancel/re-import, and light/dark/AMOLED presentation. Recording tests cover no trim and re-record → trim. Existing format tests cover PCM8/16/24 and IEEE float32/64 at multiple rates through the same decoder.

Speed checks measure real MediaPlayer position advancement against elapsed wall time at 0.5, 0.75, 1, 1.25, 1.5, 2 and intermediate rates. Reader checks use generated WAV fixtures through the actual service, including pitch parameter, media-session metadata, pause/rate-change/resume, seeking, chunk transitions and stop/restart. Preview checks separately exercise its real MediaPlayer, live changes, paused changes and player recreation. These are output-timing checks, not a claim that an agent listened to sound.

Full Pocket TTS generation/cloning and perceptual speech/pitch quality still require the physical ARM64 device: the x86 emulator's native ARM translator cannot reliably run the unchanged Pocket TTS ONNX graph. The existing physical-only test remains explicitly skipped there; fixture playback is not represented as native-generation proof.

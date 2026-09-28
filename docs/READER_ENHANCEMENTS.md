# Reader, WAV import and identity update

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

## Playback-linked highlighting

`ReaderTextChunker.ranges` supplies exact UTF-16 source offsets while preserving the existing chunk boundaries. `ReaderPlaybackService` keeps those offsets alongside each generated WAV. Its prepared MediaPlayer chunk index determines the active range; there is no guessed speech timer. Pause retains the range; Stop, completion and errors clear it. Seek follows the actual opened chunk.

The existing native EditText now defaults to read-only scrolling, with an explicit Edit Mode. `ReadingHighlight` applies one BackgroundColorSpan, not a text selection or replacement document. Scrolling waits until pre-draw so text layout is current. A manual scroll suspends automatic following until the next spoken chunk or explicit Resume, and a held gesture is never interrupted. Editing invalidates cached speech and stops playback; a new Play uses the updated document. Rotation checks the restored draft against the playback source before highlighting. See [READER_MODES_AND_ABOUT.md](READER_MODES_AND_ABOUT.md) for the current follow/editing behavior and redesigned About sheet.

## Real generated-audio envelope

The existing native synthesis callback feeds `AudioPeaks` while writing its normal WAV cache. The collector retains at most 512 peaks, merging adjacent bins as duration grows; it does not retain another PCM buffer. Each completed, playable chunk publishes a small immutable envelope. The Canvas draws those peaks with the MediaPlayer position and supports tap-to-seek; the existing accessible Slider also remains available. New playback replaces the envelope, Stop/error hides it, and completion preserves the finished waveform for seeking/export. The service's existing audio lifetime and background notification ownership are unchanged.

## IME layout

The old layout switched between weighted and fixed-height editors based on the *IME-reduced* viewport, while independently hiding header/navigation/playback sections using an IME visibility flag. These switches could occur on different frames during dismissal.

The editor now keeps a stable layout mode based on window configuration/font scale. `imePadding` handles the keyboard inset once; header/navigation/secondary controls collapse by the same animated inset fraction during measurement. No delay, extra animation clock or forced keyboard dismissal was introduced. Small windows and large fonts retain a scrollable fallback. This follows Android's [Compose inset guidance](https://developer.android.com/develop/ui/compose/system/insets-ui), which recommends reading animated inset sizes during layout rather than composition.

## Imported voice samples

Import WAV now opens the existing recording/review workflow. The selected file is copied unchanged under the existing 64 MB bound, validated, decoded through the already-used dr_wav library, downmixed and resampled using the existing band-limited resampler. The same packaged DeepFilterNet3 graph produces a separate enhanced WAV. No second denoiser, inference runtime, Gradle dependency, internet permission or model download was added.

Enhancement supports 3–30 seconds, 1–8 channels, 8–192 kHz, PCM8/16/24/32 or IEEE float32/64. The original format/bytes are preserved for Before playback and archived when saving. Enhanced output remains mono PCM16 24 kHz for the existing cloning pipeline. Corrupt files cannot be saved; valid samples outside enhancement bounds may still be saved unchanged. Neither long samples nor channels are silently truncated. See `DEEPFILTER.md` for model provenance, licenses and retention details.

A single process-wide session slot prevents canceled/reopened sheets from loading multiple enhancement models while old inference finishes. Waiting and inference remain on the serial workers, never the UI thread. Closing releases the slot and model only after native work returns; canceled flows skip further processing. Import decode buffers are bounded by the 30-second/192 kHz/8-channel limits and released after conversion.

## App identity and About

The launcher now uses an adaptive vector icon: layered reading cards with a small audio glyph, dark background and periwinkle strokes. Android 13+ also gets a monochrome layer; the media notification uses a matching white silhouette. No generated bitmap or decorative Reader image was added.

Android 12+ uses the [platform splash](https://developer.android.com/develop/ui/views/launch/splash-screen) with the new icon and near-black background; earlier supported Android versions use a matching starting-window drawable. There is no splash Activity, keep-on-screen condition, second splash or artificial startup delay. About copy now describes implemented features and third-party attribution, including the existing German, Spanish, Italian and Portuguese locales.

## Verification and remaining device checks

Resumed verification on 2026-09-26 before the trimming/speed changes: assembleDebug, assembleDebugAndroidTest, testDebugUnitTest and lintDebug succeeded with JDK 21. All 20 unit tests passed; lint reported 0 errors / 27 warnings. The complete emulator instrumentation run finished in 154.996 seconds: 12 passed, one physical-ARM64 playback test skipped (runner reports OK, 13 tests).

Use JDK 21 and the build/instrumentation commands in `READER.md`. `ReaderVisualDataTest` covers exact ranges in long/repeated/Unicode text and bounded PCM peak accumulation. `ReaderEnhancementsTest` covers highlight/pause/manual-scroll/edit behavior, waveform rendering, mixed-format WAV normalization, imported Before/After/save/original preservation, corrupt input, long-file fallback, adaptive icon rendering, repeated keyboard open/close and activity recreation. Its Reader snapshot is synthetic; its imported WAV enhancement runs the actual local model.

The physical-ARM64 `ThemeSmokeTest.playbackAndBackground` additionally asserts ranges/waveforms from real Pocket TTS, pause/seek/chunk transitions, keyboard interactions and background playback. It is skipped on this x86 emulator because the unchanged Pocket TTS/ONNX native path previously SIGILLed under ARM translation. A passing deterministic visual test is not proof of physical speech timing or perceptual enhancement quality.

Still test on the affected phone: actual spoken chunk alignment through completion, repeated waveform seeks with active generation, long-form memory/thermal behavior, noisy real speech and cloning quality, OEM keyboard animation and rotation. The original reported physical-device Play crash remains unverified without that phone/trace.

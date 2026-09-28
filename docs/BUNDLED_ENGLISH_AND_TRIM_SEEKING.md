# Bundled English and trim preview seeking

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

## Preserved work

The previous Reader layout-based follow, read/edit modes and new About remain intact. No native Pocket TTS, ONNX version, media service, recording, denoising or theme architecture was replaced. The interrupted full-suite result was unavailable after the session ended; regression verification is rerun with this implementation.

## Trim controls and playhead

The four ±0.1-second controls retain the same boundaries and actions. They now use Material 3 filled-tonal surfaces, the existing rounded shape/border palette, 48dp minimum touch height and standard pressed/disabled behavior.

The full waveform is a tap/drag seek target with an accessible progress action. A contrasting vertical line and dot indicate the source timestamp. Horizontal coordinates map to the full source duration and clamp to the selected [start, end] interval. Trim handles remain the separate range control.

Preview still uses the shared `SamplePreview` MediaPlayer and the existing normalized selected WAV. Source coordinates translate to clip-relative milliseconds (`sourcePosition - trimStart`). The first seek prepares the selected clip paused; Play begins at that position. Dragging seeks through MediaPlayer.SEEK_CLOSEST, with only the latest pending seek retained. Progress polling reads MediaPlayer.currentPosition; it does not invent audio progress. Seek completion reconciles the displayed timestamp with the actual player, and stale polling is suppressed during seeks.

Paused seeks remain paused; active seeks remain active. Completion stays at the selected end, paused. Replay starts at the selected start, never the full source start. Changing trim handles stops the obsolete clip and clamps its source playhead to the new region. Applying a trim, navigating away or re-recording releases preview resources. The regular Voices mini-player still disappears at completion.

## Bundled model

Gradle's `bundledPocketAssets` task consumes the provided project-root ZIP and builds `assets/pockettts/` containing:

- `models/flow_lm_main.onnx`
- `models/flow_lm_flow.onnx`
- `models/mimi_encoder.onnx` (reference-voice conditioning)
- `models/mimi_decoder.onnx`
- `models/text_conditioner.onnx`
- `models/tokenizer.model`
- `voices/alba.wav`
- `manifest.json`, `MODEL_LICENSE.txt`, `VOICE_ATTRIBUTION.md`
- generated `files.tsv` with sizes and SHA-256 checksums.

All ten files in this particular ZIP are useful runtime data or attribution, so none were excluded. The ZIP itself is not shipped alongside the extracted assets. No additional model, server or runtime dependency was introduced.

JNI passes absolute model/tokenizer/voice paths to the existing PocketTTS.cpp ONNX sessions. These require filesystem access. `BundledPocketTts.ensure` copies assets to private `files/model-packs/english-fp32` on a worker, using atomic per-file writes and checksum validation. It is shared through ModelPackRepository by Reader and Android system TTS. Existing byte-identical models are reused; installed manifests/custom voice entries, archives and preferences are preserved. A local completion index avoids hashing/copying the large files on each launch. Interrupted copies retry locally. Insufficient storage produces an error instead of marking preparation complete.

The separate model import/delete/picker UI, external ZIP ingestion, related activity result path and obsolete resources were removed. Voice WAV import/recording, default voice and generation settings remain. Legacy installed voices are not deleted; new voices target bundled English.

There are no runtime HTTP calls in this path. The app requests no INTERNET permission. Build-time Gradle/CMake dependency fetching is distinct from installed-app operation.

## Size and verification

Initial bundled debug APK: approximately 258.7 MB (246.7 MiB), versus 30.5 MB before bundling. Assets add approximately 228 MB to the compressed APK and require approximately 439 MB of private extracted storage. A filesystem copy is necessary for the existing native engine; no extra runtime archive is retained.

Network-disabled emulator tests verified fresh extraction into an empty directory, every asset checksum, reuse without recopying, preservation of the installed manifest, system TTS discovery of offline English/Alba, and actual preview-player drag/tap/paused/active/boundary/completion behavior. Physical ARM64 synthesis has a separate runnable test covering both Alba and a custom reference. The x86 emulator cannot reliably execute this ONNX graph through ARM translation, so this is explicitly skipped there; successful extraction/discovery is not proof of successful speech generation.

Final results (2026-09-27):

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`: successful with JBR 21. 21 unit tests passed; lint 0 errors / 23 warnings.
- Full emulator suite: 22 passed, 2 physical-only synthesis skips, no failures (243.708 seconds). Log: `app/build/bundled-regression.txt`.
- After the final rapid-toggle guard: all four targeted preview/voice tests passed (65.684 seconds), including actual MediaPlayer speed, drag seeking, selected voice, preview replacement and record/re-record/save. Log: `app/build/preview-final-regression.txt`.
- At 360 × 640 dp and 130% font size: both drag/seek and import/trim/preview/enhance/save/theme tests passed (23.527 seconds). Log: `app/build/trim-small-regression.txt`. Normal and compact light/dark button/playhead screenshots were inspected; no overlap or clipped labels. Device overrides were restored.
- Final debug APK: 258,691,685 bytes. `zipalign -c -P 16 4` passed. Native libraries were not changed. Crash log was empty after tests.
- No physical device was connected. Native Pocket TTS speech generation, custom-voice synthesis and end-to-end system-TTS audio on the bundled build remain unverified on hardware. The runnable physical tests are `BundledPocketTest.realBundledSynthesisAndCustomReference` and `ThemeSmokeTest.playbackAndBackground`; the latter also covers Reader media/background behavior with real generated speech.

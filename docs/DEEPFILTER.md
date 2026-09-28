# Offline recording noise reduction

> Development/verification history. For current installation, bundled models, limits and release steps, use the [root README](../README.md). Earlier test counts and intermediate behavior below describe their original implementation stage.

DeepFilterNet3 runs after the shared trim/preview step for microphone recordings and imported WAV reference samples. It processes the chosen 3–30 seconds, not discarded audio. It does not process Reader playback, system TTS, or the microphone in real time. No STT, VAD, transcription, SpeechPipeline, networking, services, or new Gradle dependencies were integrated. See [TRIMMING_AND_SPEED.md](TRIMMING_AND_SPEED.md) for trimming and source retention.

## Provenance and dependencies

The supplied `speech-android-main` is a wrapper with an absent `speech-core` submodule. Its JNI pipeline explicitly disables DeepFilter because its 16 kHz pipeline is incompatible with the model's 48 kHz DSP. Its setup downloads only an auxiliary binary, not the required graph. Copying that wrapper would not provide working denoising.

The necessary implementation was therefore extracted from its declared upstream, [soniqo/speech-core](https://github.com/soniqo/speech-core), pinned to `f0050757a7d7d24c60b17e60016989ecd07a491d`:

- `deepfilter_dsp.cpp/.h`: STFT, Vorbis window, ERB features, normalization, complex deep filtering, overlap-add.
- `resampler.cpp` and `speech_core/audio/resampler.h`: only the offline band-limited resampler; streaming classes excluded.
- KissFFT's real/complex FFT sources, headers and license.
- Upstream DSP regression test (include path adjusted) and source licenses.

`app/src/main/cpp/deepfilter/denoiser.h` replaces the upstream general inference engine with a small session using the app's existing ONNX Runtime **1.20.0**. `jni.cpp` exposes create/process/release. No second ONNX runtime or inference framework is bundled. The new `deepfilter_jni` library has 16 KB ELF alignment; Pocket TTS's native implementation is unchanged.

The packaged `assets/deepfilter/deepfilter.onnx` comes from [soniqo/DeepFilterNet3-ONNX](https://huggingface.co/soniqo/DeepFilterNet3-ONNX), revision `63d8ba442ba900143c468b798e94a04009b2f0c9`:

- FP32, opset 17; 8,608,859 bytes.
- SHA-256: `e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3`.
- Inputs: `feat_erb [1,1,T,32]`, `feat_spec [1,2,T,96]`.
- Outputs: `erb_mask [1,1,T,32]`, `df_coefs [1,5,T,96,2]`.
- Auxiliary tables are generated canonically by the pinned DSP implementation; the optional parity-diagnostic auxiliary binary is not required or bundled.
- Model/source/FFT notices are bundled beside the graph in `NOTICES.txt`.

## Recording and review

`VoiceRecordingDialog` stops/releases AudioRecord, finalizes the raw WAV, then opens the shared trim step. Applying a selection or continuing without trimming queues conversion and denoising on its serial worker. `DeepFilterNet3Denoiser` validates canonical PCM16 mono 24 kHz input, resamples to 48 kHz, runs the upstream DSP plus model, and resamples to 24 kHz without changing sample count. The original is never overwritten. Enhanced output is a separate valid PCM16 WAV.

The sheet shows processing status, then actual PCM-derived Before/After waveforms. Both preview buttons use the existing single `SamplePreview`; switching stops/releases the previous audio. Its existing controls provide progress, seeking and pause/resume. Enhanced is selected by default; Original remains selectable. Denoising failure leaves Original available and offers Retry. The extracted model is checksum-validated and automatically repaired from the bundled asset, without network access.

Saving passes the selected WAV to the existing `ModelPackRepository.importRecording` and voice-cloning path. It also retains `voices/.recordings/<voice-id>/original.wav` and, when available, `enhanced.wav`. Voice details exposes both previews after saving. Deleting that voice or its pack removes its archived sources. Existing saved and built-in voices remain unchanged.

WAV imports now enter the same review sheet. The original is copied byte-for-byte to private temporary storage (existing 64 MB import limit); the existing dr_wav decoder and band-limited resampler downmix 1–8 channels at 8–192 kHz into a separate canonical PCM16 mono 24 kHz file for enhancement. Supported input formats are PCM8/16/24/32 and IEEE float32/64. The original external file is never modified. Enhanced output is selected by default and the untouched imported original is archived on save. Corrupt/unsupported WAVs show an error; valid samples outside the 3–30 second enhancement bound retain Original save/preview. There is no silent truncation and no second model or denoising implementation.

Re-recording explicitly replaces the unsaved take. Closing/backgrounding the recording sheet discards unsaved temporary files, as explained in the UI. Cleanup and session release are queued behind any active native inference, avoiding use-after-free. Late callbacks cannot restore a closed sheet. Saved source files are not affected by sheet cleanup.

## Bounds and verification

CPU inference uses two intra-op threads and one inter-op thread. A session is reused across takes within a recording workflow, then released. Processing uses the bounded 3–30 second clip as one sequence, preserving model context instead of resetting it in arbitrary chunks. There is no per-frame blur or animation added.

Build using JDK 21 (the installed Android Studio JDK 25 is incompatible with this Gradle/Kotlin setup):

```powershell
# Set JAVA_HOME to your installed JDK 17+ directory first.
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

Instrumentation includes `DeepFilterTest` for real bundled-model inference, repeat/reopen, finite output, WAV shape and original preservation. `VoiceWorkflowTest` exercises recording/re-recording, Before/After switching and saved source retention. Existing theme/navigation and preview tests remain in place. Run instrumentation using the commands in `READER.md`.

Physical-device acceptance still requires listening to real speech with fan/AC noise, comparing cloning quality, and checking 30-second inference time/memory/thermal behavior. Emulator silence and synthetic audio cannot establish perceptual speech quality. Reader's previously reported physical-device keyboard/Play crash is a separate acceptance item; this feature does not claim to resolve it.

### Verified 2026-09-26

- Debug APK and instrumentation APK build successfully with JDK 21; 18 JVM tests pass; lint has 0 errors and 23 warnings.
- Five upstream native DSP checks pass, including FFT, feature layout, deep-filter fusion and non-aligned reconstruction (maximum round-trip error `8.9e-8`).
- Android 16 emulator runs the actual bundled model successfully: repeated 3-second inference, release/reopen, corrupted extracted-model recovery and a full 30-second clip. Original bytes remain unchanged during enhancement; output length/format/finiteness are checked.
- Recording permission states, live waveform, re-recording, Before/After preview switching, default enhanced save, both archived WAVs and selected voice pass instrumentation. Review screenshots inspected. Existing theme/navigation, large document, long voice list and expandable preview tests pass.
- First broad run exposed a lazy-list test locator issue (fixed) and a transient IME visibility assertion. The final focused rerun passes all three tests (DeepFilter, recording workflow and Reader IME), taking 68.8 seconds overall. The physical ARM64 Reader playback test remains skipped on this x86 emulator; do not interpret IME validation as proof of native TTS playback.
- APK ZIP alignment passes `zipalign -c -P 16 4`; all new native library LOAD segments have `0x4000` alignment. Dependency inspection shows the existing `libonnxruntime.so` plus Android system libraries only.

The unused, unbuildable Windows-only model-test draft was removed; Android instrumentation provides executable real-model coverage instead. No existing user source or recording was deleted by that cleanup.

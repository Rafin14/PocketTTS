# Third-party notices

This is an unofficial community project. It is not affiliated with or endorsed
by Kyutai, VolgaGerm, Microsoft, Google, mackron, Google Play, Android, or Layla.

## Included or used software

| Component | Purpose | Upstream | License |
| --- | --- | --- | --- |
| Pocket TTS | Model architecture, Python export source, and model weights | [kyutai-labs/pocket-tts](https://github.com/kyutai-labs/pocket-tts) | Code: MIT; model weights: CC BY 4.0 plus the upstream access/use conditions |
| Pocket TTS official voices | Default reference recordings bundled with release model packs | [kyutai/pocket-tts](https://huggingface.co/kyutai/pocket-tts) and [kyutai/tts-voices](https://huggingface.co/kyutai/tts-voices) | CC BY 4.0; per-voice sources and modifications are listed in `VOICE_ATTRIBUTION.md` |
| PocketTTS.cpp | C++/ONNX inference runtime used as the native foundation | [VolgaGerm/PocketTTS.cpp](https://github.com/VolgaGerm/PocketTTS.cpp) | MIT |
| ONNX Runtime Android 1.20.0 | ARM64 ONNX inference | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT |
| SentencePiece 0.2.1 | Text tokenization | [google/sentencepiece](https://github.com/google/sentencepiece) | Apache-2.0 |
| dr_libs / dr_wav | WAV decoding | [mackron/dr_libs](https://github.com/mackron/dr_libs) | Public domain or MIT-0, at the user's option |
| AndroidX Core, AppCompat, Activity, Compose and Material 3 | Android UI/support libraries | [androidx/androidx](https://github.com/androidx/androidx) | Apache-2.0 |
| DeepFilterNet3 FP32 ONNX | Offline selected-sample noise reduction | [soniqo/DeepFilterNet3-ONNX](https://huggingface.co/soniqo/DeepFilterNet3-ONNX) | MIT; bundled in `app/src/main/assets/deepfilter` |
| speech-core DSP/resampler | Adapted offline DeepFilter DSP | [soniqo/speech-core](https://github.com/soniqo/speech-core) | Apache-2.0; original copyright retained |
| KissFFT | DeepFilter FFT | [mborgerding/kissfft](https://github.com/mborgerding/kissfft) | BSD-3-Clause |
| Kotlin | Android application language/runtime | [JetBrains/kotlin](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| Gradle | Build system | [gradle/gradle](https://github.com/gradle/gradle) | Apache-2.0 |

The snapshot under `vendor/pocket-tts` is based on upstream commit
`d108410d23eef7e01db282f9442891162dbc3db6`. Its original MIT license is kept
at `vendor/pocket-tts/LICENSE`.

The files under `vendor/PocketTTS.cpp` are based on upstream commit
`e801e7d6c2692121a39e80ae525cb5265174a495` and contain Android/multilingual
compatibility changes. The original MIT license is kept at
`vendor/PocketTTS.cpp/LICENSE`.

The Android build fetches SentencePiece at tag `v0.2.1` and dr_libs at commit
`50bb723e6a459dbb781e26cefee4fd9ca6714d6a`.

The current APK bundles English FP32 and the Alba reference only. The legacy
export tools also describe other official voices; those are not current bundled
languages. See `VOICE_ATTRIBUTION.md`. The test-only speech MP4 derives from Alba
(AAC/stereo conversion plus a generated test pattern); other video fixtures are
generated tones/silence. No user-imported recording belongs in the repository.

Complete additional license texts are packaged under
`app/src/main/assets/licenses/`: ONNX Runtime 1.20.0 and its upstream third-party
notices; SentencePiece 0.2.1 and its bundled absl/darts_clone/esaxx/protobuf-lite
licenses; dr_libs; KissFFT BSD terms; and CC BY 4.0. Original Pocket licenses stay
under `vendor/`. DeepFilter model/source notices remain bundled in
`app/src/main/assets/deepfilter/NOTICES.txt`, with source license in the DSP tree.
These files enter the APK as assets, not only the source repository.

DeepFilter model revision: `63d8ba442ba900143c468b798e94a04009b2f0c9`.
Adapted speech-core revision: `f0050757a7d7d24c60b17e60016989ecd07a491d`.
See `docs/DEEPFILTER.md` for exact hashes and modifications.

## Related application

This engine was developed for use with [Layla](https://www.layla-network.ai/).
Layla supports voices exposed by Android system TTS engines; its multilingual
TTS setup is described in the
[Layla guide](https://blog.layla-network.ai/post/how-to-add-multilingual-text-to-speech-for-your-characters-in-layla).
Layla is not included in this repository and is not a dependency of the engine.

The complete license texts and notices distributed by downloaded dependencies
remain controlling for those components.

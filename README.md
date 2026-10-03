# Pocket TTS

**Offline Android speech, a focused Reader, and custom voices made from your own audio.**

Pocket TTS brings Kyutai's Pocket TTS to Android through a native ONNX runtime. Read long documents, listen with background media controls, or create a custom voice from a recording, WAV file, or video's audio—all on your device.

This is an unofficial community application, not an official Kyutai Android release. The current app is **0.5.2 (22)**, supports **Android 8.0+**, and builds for **ARM64**.

## What you can do

### Read and listen

- English FP32 model and the Alba reference voice are bundled in the APK; first launch prepares them locally, without a download.
- Long-form Reader with automatic text chunking, read-only and Edit modes, spoken-section highlighting, and scroll synchronization.
- Compact playback controls leave more room for text. Play, pause/resume, stop, move between actual chunks (including chunks still generating), seek within prepared audio, change speed from **0.50× to 2.00×**, and export generated WAV audio.
- Audio-derived waveform and progress display. Highlighting follows generated sections, not forced-aligned individual words.
- Background playback with Android media-session/notification controls.
- Android system text-to-speech engine integration for other apps.

### Create a voice

- Record with microphone-driven waveform and duration feedback, import a WAV, or **Extract from Video** using Android's file picker.
- Preview video locally, decode its audio, then use the same waveform/trim/review pipeline.
- Select a **3–30-second** reference using trim handles, 0.1-second adjustments, and a draggable playback position.
- Apply bundled **DeepFilterNet3** noise reduction and compare real before/after waveforms and audio. Keep the unenhanced sample if preferred.
- Name/save/select custom voices and preview them directly in an expanded player with waveform, seeking and speed controls.
- Rename, trim or denoise saved custom voices from their action menu. Edits preserve the voice ID, name and original source; **Restore Original** restores the actual cloning reference and clears obsolete derived audio.
- Retain original, trimmed, and enhanced recordings for saved voices. Voice cloning conditions the model on the reference sample; it does not train a new model.

### Make it yours

- Material 3 with restrained glass surfaces, muted periwinkle accents, and readable tonal hierarchy.
- System, Light, Dark, and AMOLED appearance modes.
- Generation controls for temperature, LSD steps, CPU threads, text segment size, and sentence pauses.
- In-app About and enhancement-license information.

## Screenshots

Screenshots from the app running on an Android 16 ARM64 phone. The video example uses the bundled Alba reference with a generated test picture; no personal recordings are shown.

<table>
<tr>
<td align="center"><img src="docs/screenshots/reader.png" width="230" alt="Reader with sample text"><br>Focused Reader</td>
<td align="center"><img src="docs/screenshots/voices.png" width="230" alt="Voices and active selection"><br>Voice selection</td>
<td align="center"><img src="docs/screenshots/add-voice.png" width="230" alt="Voice creation methods"><br>Record, import, or extract</td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/video-extraction.png" width="230" alt="Local video preview and extraction"><br>Video-to-voice</td>
<td align="center"><img src="docs/screenshots/audio-trimming.png" width="230" alt="Audio waveform and trimming"><br>Precise sample trimming</td>
<td align="center"><img src="docs/screenshots/voice-enhancement.png" width="230" alt="Audio enhancement comparison"><br>Before/after enhancement</td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/record-voice.png" width="230" alt="Ready to record a voice sample"><br>Voice recording</td>
<td align="center"><img src="docs/screenshots/settings.png" width="230" alt="Appearance and engine settings"><br>Settings</td>
<td align="center"><img src="docs/screenshots/about.png" width="230" alt="About Pocket TTS"><br>About</td>
</tr>
</table>

See [screenshot capture instructions](docs/screenshots/README.md) for refreshing the gallery.

## Architecture

```text
Reader text → bounded text chunks → PocketEngine → JNI / PocketTTS.cpp / ONNX
                                                    ↓
                                  generated PCM / cached WAV chunks
                                                    ↓
                           media playback → waveform, seeking, section highlighting

Android TTS client → PocketTtsService → same Pocket engine → Android PCM callback

Microphone / WAV import / video MediaExtractor + MediaCodec
                            ↓
                  original WAV → waveform + trim
                            ↓
                   3–30 s mono 24 kHz sample
                            ↓
                 optional DeepFilterNet3 enhancement
                            ↓
                before/after review → named WAV reference
                            ↓
              voice repository → Pocket voice conditioning
```

- **UI:** Kotlin, Compose and Material 3. The Reader uses an embedded native Android text editor for robust selection/IME behavior.
- **Audio ownership:** Reader foreground service/media session; shared `SamplePreview` MediaPlayer for sample/video previews. No ExoPlayer or FFmpeg runtime.
- **Voice storage:** `ModelPackRepository` manages metadata, selected voices and preserved source recordings in app-private storage.
- **Native code:** `pockettts_jni` wraps PocketTTS.cpp; `deepfilter_jni` performs selected-sample DSP/inference using the same ONNX Runtime library.
- **Packaging:** Gradle extracts required models/tokenizer/Alba/notices from the root ZIP into generated assets. DeepFilter's graph is directly in `app/src/main/assets/deepfilter/`.

## Technology stack

| Technology | Version / role |
| --- | --- |
| Kotlin / Compose compiler plugin | 2.0.21 |
| Gradle wrapper / Android Gradle Plugin | 8.11.1 / 8.7.3 |
| Android SDK | min 26, compile/target 35 |
| Application ID / namespace | `org.pockettts.android.engine` |
| Java / Kotlin bytecode | 17 |
| Compose BOM / Material 3 | 2024.12.01; Compose UI and Material 3 |
| AndroidX | Core KTX 1.15.0, AppCompat 1.7.0, Activity Compose 1.9.3 |
| PocketTTS.cpp / Pocket TTS | Vendored C++17 runtime and Python export source; English FP32 ONNX model |
| ONNX Runtime Android | 1.20.0, ARM64 native inference |
| SentencePiece / dr_libs | 0.2.1 tokenizer; pinned native audio decoding |
| DeepFilterNet3 | Bundled FP32 graph, speech-core DSP/resampler, KissFFT |
| Android media APIs | AudioRecord, MediaPlayer, MediaSession, MediaExtractor, MediaCodec |
| Tests | JUnit 4.13.2, AndroidX runner 1.6.2, Compose UI tests |

Python/export tools are optional developer utilities, not Android runtime dependencies.

## Build locally

### Requirements

- JDK **17** or the tested JBR **21**, with Java/Kotlin target 17.
- Android SDK Platform **35**, Build Tools **34.0.0** (AGP default), platform-tools, NDK **27.2.12479018**, CMake **3.22.1**.
- Git and **Git LFS** for the bundled model archive.
- Bash, curl, unzip, tar and sha256sum for the native-dependency preparation script (WSL or an appropriately equipped Bash environment on Windows).
- Android Studio compatible with AGP 8.7.3; no exact IDE release is pinned. Android Studio is optional for command-line builds.
- Internet for initial build dependency downloads; normal app operation is offline.

```bash
git lfs install
git clone <repository-url>
cd <repository-directory>
git lfs pull
bash scripts/prepare_android_native_deps.sh
./gradlew :app:assembleDebug
```

On Windows, run the preparation script in Bash/WSL, then from PowerShell:

```powershell
# Set JAVA_HOME to your JDK installation and ANDROID_HOME to your Android SDK.
.\gradlew.bat :app:assembleDebug
```

The required `PocketTTS-english-FP32.zip` is approximately **198 MiB** and uses Git LFS. It must be the actual ZIP, not an LFS pointer. DeepFilter's approximately 8.2 MiB ONNX file is included normally. Do not remove these assets or use a blanket `*.onnx` / `*.zip` ignore rule. GitHub source ZIP downloads may contain LFS pointers; cloning with LFS is the reliable setup.

Output: **`app/build/outputs/apk/debug/app-debug.apk`**.

### Android Studio

1. Clone with Git LFS and prepare ONNX Runtime as above.
2. Open the repository root, not `app/`.
3. Set the Gradle JDK and install the exact SDK/NDK/CMake components through SDK Manager.
4. Sync Gradle, select the `app` configuration and an ARM64 device, then Run.

See [Building and signing](docs/BUILDING.md) for full setup and troubleshooting.

## Install and run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Pocket TTS**, allow local model preparation to complete, enter Reader text, and press Play. Microphone permission is requested when recording; imported media uses Android's picker without broad storage permission.

For system TTS, open Android's **Text-to-speech output** settings and select Pocket TTS as the preferred engine. Paths vary by device; the app has a settings shortcut.

Use a physical **ARM64 Android 8+ device** for native synthesis. ARM64 emulators may also be used where supported. x86 emulator translation can exercise UI, media decoding and DeepFilter but is not supported for Pocket ONNX speech generation.

## Source limits and performance

- Maximum source WAV: **256 MiB of uncompressed WAV bytes**, not compressed video file size.
- Maximum source audio duration: **30 minutes**.
- Recording/selected enhancement/cloning reference window: **3–30 seconds**, intentionally separate from source limits.
- Video decoding and WAV scanning stream bounded blocks off the UI thread. The Pocket reference loader only decodes the first 30 seconds needed for conditioning.
- Expect roughly **420 MiB** for extracted Pocket assets in addition to the APK, DeepFilter, generated audio and saved source recordings. Large documents/audio also require RAM, processing time and temporary disk space.

## Privacy and responsible use

The app manifest has **no INTERNET permission**. TTS, voice conditioning, noise reduction and media extraction run locally; the app does not upload recordings or require an inference service. Models are packaged, not downloaded at runtime. Android backup is disabled for app data.

Android's document picker may show cloud-backed providers; that provider can download a selected file independently of this app. Exported files are written to the destination you choose. Development builds may log diagnostic information—review logs before sharing them.

Only clone voices you have permission to use. Do not present generated speech as an authentic recording of another person. Model and recording rights are separate from the application's source-code license.

## APK releases

Distribute signed APKs through **GitHub Releases**, not as source-repository build artifacts.

1. Update `versionCode` and `versionName` in `app/build.gradle.kts` when making a new version. Current values are **22 / 0.5.2**.
2. Create/retain a private signing keystore **outside** the checkout. Set `POCKETTTS_KEYSTORE_PATH`, `POCKETTTS_KEYSTORE_PASSWORD`, `POCKETTTS_KEY_ALIAS`, and `POCKETTTS_KEY_PASSWORD`.
3. Run `./gradlew :app:assembleRelease :app:lintRelease` (Windows: `.\gradlew.bat`).
4. With signing configured, locate `app/build/outputs/apk/release/app-release.apk`. Without all four variables Gradle produces `app-release-unsigned.apk`, which must not be published as installable.
5. Verify the signature with Android Build Tools `apksigner verify --verbose --print-certs`, then install/test on ARM64 hardware.
6. Copy the finished signed file to a release-artifact directory as **`Pocket-TTS-v0.5.2-release.apk`**. Renaming a completed APK does not modify its signature.
7. Commit and push the reviewed source, then open GitHub **Releases → Draft a new release → Choose a tag**. Use a version tag against the tested commit.
8. Add a title, release notes, supported architecture, known limitations and the signed APK attachment; publish when ready.

Optional CLI example (choose the version tag for your release):

```bash
git tag -a v0.5.2 -m "Pocket TTS 0.5.2"
git push origin v0.5.2
gh release create v0.5.2 release-assets/Pocket-TTS-v0.5.2-release.apk --verify-tag --draft --title "Pocket TTS 0.5.2" --notes-file release-assets/release-notes.md
# Review the draft in GitHub, then publish it.
```

Keep the same signing key for future updates. A new release key cannot update an APK signed by a different developer/debug key without uninstalling; uninstalling deletes app-private voices and documents. Back up anything important first.

GitHub references: [managing releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository), [large files / LFS](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github).

## Tests and known limitations

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

- English FP32 is bundled. Legacy model-pack/export code remains for compatibility/development, but the current fresh-install UI does not offer downloadable language packs.
- Only ARM64 is packaged; native synthesis performance/voice quality vary by hardware and sample quality.
- Supported video/audio codecs depend on Android's decoders. The first supported audio track is selected; DRM-protected, corrupt or non-seekable inputs may be rejected.
- Enhancement cannot reliably remove music, echo or other speakers. Choose clean, single-speaker speech.
- A large self-contained APK is expected; model extraction and archived audio require extra storage.
- Use ARM64 hardware for native synthesis tests. UI fixtures and emulator checks do not establish subjective speech quality or performance on every device.

See the [Reader guide](docs/READER.md), [voice management](docs/PLAYBACK_AND_VOICE_EDITING.md), [audio limits](docs/LIMITS_AND_UI_AUDIT.md), [video flow](docs/VIDEO_VOICES.md) and [publishing checklist](docs/PUBLISHING_CHECKLIST.md).

## Repository map

```text
app/src/main/             Android app, TTS/media services, JNI, bundled DeepFilter
app/src/androidTest/      Device tests and small generated/media fixtures
app/src/test/             JVM regression tests
PocketTTS-english-FP32.zip Required Pocket model input (Git LFS)
vendor/PocketTTS.cpp/      Patched native inference and exporter
vendor/pocket-tts/         Upstream Python/export source snapshot
scripts/                  Dependency preparation and model tooling
docs/screenshots/         Actual app screenshots
```

## Licenses and attribution

Application code: [MIT](LICENSE). Vendored software retains its original notices.

- Pocket TTS and PocketTTS.cpp code: MIT. Converted Pocket model and Alba voice have separate CC BY 4.0 attribution/use conditions; see [model license](MODEL_LICENSE.md) and [voice attribution](VOICE_ATTRIBUTION.md).
- DeepFilterNet3 model: MIT; adapted speech-core DSP/resampler: Apache-2.0; KissFFT: BSD-3-Clause.
- ONNX Runtime: MIT; SentencePiece, AndroidX/Compose and Kotlin: Apache-2.0; dr_libs offers public-domain/MIT-0 licensing.
- Full notices, pinned provenance, modifications and bundled license texts: [Third-party notices](THIRD_PARTY_NOTICES.md), [packaged licenses](app/src/main/assets/licenses/), [DeepFilter notices](app/src/main/assets/deepfilter/NOTICES.txt).

Upstream software, models and recordings retain their original copyright notices and licensing requirements.

## Contributing

Fork, create a focused branch, run build/unit/lint checks and relevant device tests, then submit a PR describing behavior changes, screenshots where relevant, and testing limitations. Never include credentials, signing keys or private recordings. Keep bundled model changes explicit and preserve license/attribution files. See [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

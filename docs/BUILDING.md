# Building & testing 

## Prerequisites

- JDK 17 or the tested JBR 21 (Java/Kotlin target 17).
- SDK Platform 35; Android Build Tools 34.0.0 (AGP 8.7.3 default), platform-tools.
- NDK 27.2.12479018, CMake 3.22.1 (install through Android Studio SDK Manager).
- Git, Git LFS; Bash with curl, unzip, tar and sha256sum for native dependency preparation.
- An ARM64 Android 8.0+ device for actual Pocket TTS inference. No exact Android Studio release is pinned; use one compatible with AGP 8.7.3.

## Required model inputs

Run `git lfs install` before cloning, then `git lfs pull`. The root
`PocketTTS-english-FP32.zip` must be the actual approximately 198 MiB ZIP,
not a small LFS pointer. It contains the five ONNX graphs, tokenizer, Alba
reference and attribution. Gradle packages them automatically. The DeepFilter
graph/notices under `app/src/main/assets/deepfilter/` are also required.

Neither model is downloaded by the installed app. Build-time downloads are different.

## Prepare ONNX Runtime

From a Bash terminal at the repository root:

```bash
bash scripts/prepare_android_native_deps.sh
```

The script downloads/checksums ONNX Runtime Android 1.20.0 and extracts ARM64
binaries and matching headers into Git-ignored directories. On Windows use
Git Bash with the required utilities, or WSL against this same checkout.

Set `ANDROID_HOME` to the local SDK, or let Android Studio create ignored
`local.properties`. Set `JAVA_HOME` to your JDK. CMake fetches pinned
SentencePiece and dr_libs sources during the first native build.

## Build

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

PowerShell equivalent:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

For Android Studio: open the repository root, configure the Gradle JDK, install
the SDK/NDK/CMake prerequisites, sync, and run the `app` configuration.

## Device checks

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest
adb logcat -b crash -d
```

First launch prepares built-in English/Alba locally. No language-pack picker
is needed. Test Reader playback and Android system TTS settings. Microphone
permission is only needed for recording. Native Pocket ONNX synthesis requires
ARM64 hardware; x86 emulator translation is unsupported for that graph. A skipped
native test is not a successful synthesis check. Use a dedicated test installation:
instrumentation can change settings and create/delete test voices.


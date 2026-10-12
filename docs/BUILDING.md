# Build PocketTTS

## Requirements

- JDK 17 or 21; Java/Kotlin bytecode targets Java 17.
- Android SDK Platform 35 and platform-tools.
- NDK 27.2.12479018 and CMake 3.22.1.
- Git and Git LFS.
- Bash, curl, unzip, tar and sha256sum for native dependency preparation.
- An ARM64 Android 8.0+ device for PocketTTS synthesis.

Android Studio is optional. Use a version compatible with Android Gradle Plugin 8.7.3.

## Model and native dependencies

Run `git lfs install` and `git lfs pull`. The root `PocketTTS-english-FP32.zip` must be the actual model archive, not an LFS pointer. Gradle packages its graphs, tokenizer, manifest, Alba reference and notices. The model and notices under `app/src/main/assets/deepfilter/` are also required.

From Bash at the repository root:

```bash
bash scripts/prepare_android_native_deps.sh
```

This downloads checksum-pinned ONNX Runtime Android 1.20.0 and prepares ARM64 libraries and matching headers in ignored directories. Windows users can use Git Bash or WSL with the required utilities. CMake fetches pinned SentencePiece and dr_libs sources on the first native build.

Set `ANDROID_HOME` to your Android SDK and `JAVA_HOME` to your JDK. Alternatively, Android Studio can create an ignored `local.properties` SDK configuration. Models are bundled; build-time dependency downloads are not runtime app downloads.

## Command-line build

```bash
./gradlew :app:assembleDebug
```

PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Android Studio

Open the repository root, configure the Gradle JDK, install the SDK/NDK/CMake requirements, prepare the native dependencies, and sync Gradle. Run the `app` module on an ARM64 device.

## Application identities and updates

The production application ID is `org.pockettts.android.engine`. The debug variant uses `org.pockettts.android.engine.apple`, allowing a separate development installation. The isolated instrumentation variant uses `org.pockettts.android.engine.apple.qa`.

An installed app can only be updated with a compatible application ID and the same signing key. Export custom voices and keep important Reader text before uninstalling or switching identities. A debug APK is not a production-signed release.

## Release signing

Provide these environment variables locally or through your CI secret store:

- `POCKETTTS_KEYSTORE_PATH`
- `POCKETTTS_KEYSTORE_PASSWORD`
- `POCKETTTS_KEY_ALIAS`
- `POCKETTTS_KEY_PASSWORD`

Then build with `./gradlew :app:assembleRelease`. Without a complete signing configuration, the release output is unsigned. Keep the original signing key secure; a new key does not update an app signed with the old one. Never include signing credentials or keystores in Git.

See [Contributing](../CONTRIBUTING.md) for development checks and [Third-party notices](../THIRD_PARTY_NOTICES.md) for bundled licensing.


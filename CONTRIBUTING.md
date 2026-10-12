# Contributing

Contributions are welcome. Open an issue for substantial behavior, architecture or model changes, and keep pull requests focused.

## Development checks

Follow the [build guide](docs/BUILDING.md), then run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python scripts/check_publishable.py
```

For instrumentation, use a dedicated emulator/device installation:

```bash
./gradlew :app:connectedDebugAndroidTest -PisolatedDeviceTest=true
```

Instrumentation uses disposable voices and may change application or device settings. Do not run it against a personal voice library. Native PocketTTS synthesis requires physical ARM64 hardware; x86 UI/media checks cannot establish native generation or audible quality.

## Pull requests

Describe the change, how it was checked, and any device-specific limitations. Include screenshots for visible UI changes.

- Preserve offline operation, Android system TTS, voice data and lifecycle safety.
- Retain functional regression tests and required models, tokenizer, voices and notices.
- Keep model archives in Git LFS and document their provenance.
- Keep public guides aligned with actual controls and supported features.
- Do not submit private audio/text, credentials, signing keys, local SDK paths, generated build outputs or personal diagnostic logs.
- Preserve third-party copyright and license notices.

Original application contributions use the repository's MIT license. Third-party code, models and recordings retain their separate licenses. Report vulnerabilities through the [security policy](SECURITY.md).

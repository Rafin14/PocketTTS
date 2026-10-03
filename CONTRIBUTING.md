# Contributing

Fork the repository, create a focused branch, and submit a pull request with a
summary, test results and screenshots for visible UI changes. Discuss major
architecture/model changes first.

- Preserve Android system TTS, offline operation, voice data and lifecycle safety.
- Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
- Run relevant instrumentation tests, and test synthesis on a physical ARM64
  device when changing native inference or voice conditioning. Report skips honestly.
- Run `python scripts/check_publishable.py` before submitting changes.
- Keep the required bundled model archive in Git LFS. Do not exclude required
  runtime assets; model changes must include provenance and license review.
- Never submit private recordings, tokens, signing keys, local SDK paths or
  diagnostic logs containing private text.
- Preserve notices and document every new third-party component.

Contributions to original app code use the repository's MIT license.
Third-party sources/models/voices retain their own licenses and attribution.

# Publishing checklist

Use this checklist when preparing a source update or APK release.

## Review source

- Review the diff and preserve existing Git history.
- Keep required model inputs, vendored licenses and attribution.
- Exclude credentials, signing keys, SDK paths, private recordings and diagnostic
  logs containing personal text.
- Keep generated builds, caches and machine-local configuration out of Git.
  Ignore rules do not protect files placed in a manually uploaded folder archive.

From the repository root:

```powershell
git lfs install --local
git status
git diff --check
python scripts/check_publishable.py
git lfs fsck
git check-attr filter -- PocketTTS-english-FP32.zip
```

The archive must report `filter: lfs`. It exceeds GitHub's regular-file size
limit and must remain in LFS. The publication checker examines tracked and
non-ignored untracked candidates for common sensitive patterns, required assets
and README links. Review files manually too; pattern scanning is not exhaustive.

Default `PocketTTS-Voices-*.zip` backups are ignored because they contain personal
audio. Renamed backups and individual WAV exports must also stay outside the
checkout; ignore rules cannot identify private recordings by their content.

Do not delete `.git` or run blanket cleanup commands to prepare a commit.
Generated/native dependency caches are already ignored. Preserve useful local
APKs, SDK configuration and test evidence outside publishable source.

## Verify the application

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

Run relevant instrumentation and actual synthesis on ARM64 hardware. Check
Reader transport and keyboard behavior, Android system TTS, custom-voice creation,
edit/restore, microphone permission, theme modes and background playback.
Include known limitations and skipped checks in the release notes.

For sheet gestures, themed menus and compact Reader layout, run
`UiRefinementTest` on the dedicated QA installation described in
[isolated device testing](BUILDING.md#isolated-device-testing). Test actual
Pocket TTS generation on ARM64 separately; generated-WAV emulator fixtures do
not verify native synthesis.

## Commit a source update

Run the checks above, then inspect exactly what will be committed:

```powershell
git diff
git add --all
git diff --cached --check
git diff --cached --stat
git diff --cached
python scripts/check_publishable.py
git commit -m "feat: refine reader UI and add portable voice backups"
git push origin HEAD
```

Review new files and binary assets as well as the text diff. Do not commit private
audio, renamed backups, signing material or APKs. Use your configured remote in
place of `origin` if different. This source update does not require changing the
app version; increment it before distributing a new APK release.

## Sign and publish an APK

1. Increment `versionCode` and choose `versionName` in `app/build.gradle.kts`.
2. Configure the four `POCKETTTS_*` signing variables in
   [Building and signing](BUILDING.md#release-signing).
3. Build `:app:assembleRelease :app:lintRelease`.
4. Verify the signed APK using `apksigner` and test it on ARM64 hardware.
5. Copy the signed APK to an ignored release-artifact directory.
6. Commit and push reviewed source, then tag the tested commit.
7. Create a GitHub Release for the tag with the APK, supported architecture,
   installation instructions and known limitations.

Do not publish unsigned APKs or debug builds as signed releases. Retain a secure
backup of the release keystore and passwords; subsequent direct APK updates need
the same signing identity. Never put those files or passwords in Git.

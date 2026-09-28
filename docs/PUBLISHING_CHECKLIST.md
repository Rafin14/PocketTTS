# Fresh repository handoff

## Preparation verification — September 29, 2026

- Debug app/test APK builds passed; 22 JVM tests were explicitly rerun and passed.
- Lint: 0 errors, 22 warnings. APK ZIP 16 KiB alignment check passed.
- Actual screenshot flow passed on an isolated API 36 x86 emulator, including
  local video decoding, waveform/trim and real bundled DeepFilter enhancement.
- The APK contains both required model sets and 13 additional license/notice assets.
- Git initialization/status confirmed zero commits, zero staged files and no
  remotes. Required source/assets are candidates; generated/local paths are ignored.
- Read-only publication scanner and a synthetic private-path rejection test passed.
  No credential-pattern matches or signing keys were found in publishable files.
  `local.properties` remains local and ignored; personal paths in docs were replaced.
- No signed release was created and nothing was committed/uploaded. ARM64 native
  synthesis and release signing still require your device/key; not validated here.

Pocket ZIP SHA-256: `f2027032434a8616855c12f505fec8864bcbaccd42de43bdf69bff340fb411c4`.
DeepFilter SHA-256: `e1157049059434ae0d5857e32c812abea227b975e946b2eb64d001abbce156d3`.

The full upstream source/notice files remain intact. Stale pack-installation and
inherited signing-certificate claims were removed or labeled as legacy history.

No source commit, remote, push, release or upload was performed.

## Remaining manual cleanup

The environment blocked recursive deletion during the final cleanup. The temporary
root `.git` therefore remains **empty**, with no commits, staged files or remotes.
The old nested Git directories are only downloaded dependencies inside generated
`app/.cxx`; they are ignored. The unused reference checkout's `.gitmodules` was
removed successfully. Source code, models and licenses have not been deleted.

From the project root, after closing builds/emulators, remove only these disposable
paths yourself if you want an entirely uninitialized folder with no nested Git metadata:

```powershell
# Confirm you are in the intended project before deleting these exact local paths.
Get-Location
Resolve-Path .git, app/.cxx, build/readme-avd, docs/screenshots/readme-screenshots
Remove-Item -LiteralPath .git -Recurse -Force
Remove-Item -LiteralPath app/.cxx -Recurse -Force
Remove-Item -LiteralPath build/readme-avd -Recurse -Force
Remove-Item -LiteralPath docs/screenshots/readme-screenshots -Recurse -Force
```

The emulator has been stopped. `.cxx` is regenerable native build cache, not source;
removing it causes CMake to download/configure dependencies again on the next build.
`build/readme-avd` is the temporary screenshot emulator, not your normal AVD.
The nested screenshot directory contains only duplicate staging copies; the nine
curated images are directly in `docs/screenshots` and must be retained.
These directories are all excluded from publication even before manual cleanup.

## Before your first commit

From this project directory:

```powershell
git init
git lfs install --local
git status
python scripts/check_publishable.py
git check-attr filter -- PocketTTS-english-FP32.zip
```

The last command must report `filter: lfs`. The approximately 198 MiB model ZIP
cannot be pushed as a regular GitHub blob. Keep `.gitattributes` and ensure LFS
is installed **before you stage**. Review LFS account storage/bandwidth limits.
The DeepFilter ONNX graph is intentionally included as a regular Git file.

Review all files before doing your own staging/commit/push. The checker examines
both tracked and untracked non-ignored files, flags common secrets/private paths,
verifies required assets/LFS and README local links. It is not a guarantee against
every kind of secret or private recording.

## Local-only files

`local.properties`, `.idea`, Gradle/Kotlin/CMake caches, generated APKs, downloaded
ONNX Runtime and the unused `speech-android-main` reference checkout are ignored.
Required runtime inputs, licenses, source and test media are not blanket-ignored.
Do not ZIP your entire working directory for publication: Git exclusions do not
protect a manually uploaded folder archive.

The reference checkout is retained locally, not published; its obsolete
`.gitmodules` file was removed. Upstream vendor URLs, commit identifiers and
copyright notices are attribution, not inherited Git history, and remain intact.
CMake may create fresh dependency repositories inside ignored `.cxx` on future
builds; they are build caches, not app history or submodules to publish.

## Release checklist

1. Increment `versionCode`/`versionName` in `app/build.gradle.kts` for a new release.
2. Configure all four `POCKETTTS_*` signing variables documented in BUILDING.md.
3. Run `.\gradlew.bat :app:assembleRelease :app:lintRelease`.
4. Verify `app-release.apk` with `apksigner`, install and test it on ARM64 hardware.
5. Copy the signed APK to `release-assets/Pocket-TTS-v0.5.2-release.apk` (adjust version).
6. Commit/push reviewed source yourself; tag the tested commit.
7. GitHub: Releases → Draft a new release → choose/create that tag → attach APK → add notes → publish.

CLI alternative (after your tested source/tag are pushed):

```text
gh release create v0.5.2 release-assets/Pocket-TTS-v0.5.2-release.apk --verify-tag --draft --title "Pocket TTS 0.5.2" --notes-file release-assets/release-notes.md
```

Create your release-notes file first. Review/publish the draft yourself. Do not
attach `app-release-unsigned.apk` or a debug APK as the signed release.

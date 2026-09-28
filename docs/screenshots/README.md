# Actual application screenshots

Captured from the current application on an API 36 emulator at
1344 × 2992, portrait, Dark theme, default text size. No artwork/mockups or
synthetic playback state was substituted for the app UI.

`ReadmeScreenshotsTest` captures the Reader, Voices, Add Voice, microphone-permission
interface, local video preview, real extracted waveform, real DeepFilter review,
Settings and About. The example video uses the attributed bundled Alba sample
and a generated test pattern. Images contain no private recordings or text.

Native TTS-playing/highlighting is not pictured because the available x86
emulator cannot reliably run the ARM64 Pocket ONNX stack. Use an ARM64 phone
for that screenshot; do not fake a Playing state.

Reproduce with `:app:assembleDebugAndroidTest`, install the app/test APKs, then:

```text
adb shell am instrument -w -r -e class org.pockettts.android.engine.ReadmeScreenshotsTest org.pockettts.android.engine.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/org.pockettts.android.engine/files/readme-screenshots/reader.png docs/screenshots/reader.png
```

Repeat the pull for each captured filename. Use a test device containing no personal
data. Inspect every image before publishing. Wait until the test finishes before pulling.

# Application screenshots

The gallery contains actual app captures from an ARM64 Android 16 phone at
1272 × 2772, using Dark theme and default text size.

These captures predate the latest compact-player and overflow-menu refinements.
Refresh them on a device before presenting them as screenshots of the latest UI.

Screens show Reader, Voices, Add Voice, recording, video extraction, trimming,
enhancement review, Settings and About. The demonstration video uses an
attributed Alba reference with a generated test pattern. No private voice
recordings or document text are used.

## Refresh the gallery

Use a dedicated test installation. Build and install the debug app and
instrumentation APKs, then run:

```text
adb shell am instrument -w -r -e class org.pockettts.android.engine.ReadmeScreenshotsTest org.pockettts.android.engine.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/org.pockettts.android.engine/files/readme-screenshots/reader.png docs/screenshots/reader.png
```

Wait for the test to finish, then pull each generated image. Inspect the screens
for layout, contrast and private information before publishing. Capture actual
app states rather than fabricating playback or processing results.

For the optional isolated test package, use `org.pockettts.android.engine.qa`
in the device file path and `org.pockettts.android.engine.qa.test` for the runner.
See [isolated testing](../BUILDING.md#isolated-device-testing).

# Extract from Video

Use a video's audio as a reference for an authorized custom voice.

## Create a voice

1. Open **Voices → Add voice → Extract from Video**.
2. Choose a video using Android's document picker.
3. Preview it, then choose **Extract audio**.
4. Select a clean 3–30-second section containing one speaker.
5. Preview the selection, optionally use offline noise reduction, and save a name.

The original video is not modified. The extracted WAV enters the same trim,
Before/After enhancement and save workflow used for recordings and WAV imports.
The saved reference conditions Pocket TTS; it does not train a new model.

## Formats and limits

Support depends on Android's installed media decoders. The extractor uses the
first supported audio track. Protected media, missing audio, malformed files,
decoder stalls and non-seekable provider streams can be rejected.

Source audio must be at least 3 seconds, at most 30 minutes, and fit within
256 MiB of decoded WAV data. This is not a compressed-video size cap.
Enhancement and selected references use a separate 3–30-second window.

Android's picker does not require broad storage permission. Cloud-backed
providers can download the chosen file independently; extraction and inference
run locally.

## Implementation

`VideoAudioExtractor` uses Android MediaExtractor/MediaCodec and streams PCM
into a WAV. `VideoImportDialog` reuses the shared MediaPlayer for video preview.
`VoiceRecordingDialog` owns the extracted temporary WAV and supplies the shared
trim/denoise/save flow. No FFmpeg runtime or separate audio engine is needed.

Closing or backgrounding an unsaved flow cancels work and releases owned
resources. Temporary files are removed; saved original/derived sources remain
available with the voice.

## Contributor tests

`VideoImportTest` covers decoding, waveform data, missing/corrupt/short inputs,
picker cancellation, preview seeking, cleanup, real enhancement and saved voice
selection. Native generation tests require ARM64 hardware.

Test-only MP4 fixtures live in `app/src/androidTest/assets/video`, not the
application APK. The speech fixture uses an attributed Alba excerpt; other
fixtures contain generated patterns, tones or silence. Desktop FFmpeg is used
only to generate fixtures.

See [test commands](BUILDING.md),
[source limits](LIMITS_AND_UI_AUDIT.md) and
[voice attribution](../VOICE_ATTRIBUTION.md).

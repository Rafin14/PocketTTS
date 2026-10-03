# Audio limits and accessibility

## Source and processing limits

| Input or operation | Limit |
| --- | --- |
| Source WAV | 256 MiB of uncompressed WAV-file bytes |
| Source audio duration | 30 minutes |
| Microphone recording | Up to 30 seconds; at least 3 seconds to use |
| Selected trim / DeepFilter reference | 3–30 seconds |
| Playback speed | 0.50×–2.00× |

Video limits apply to decoded audio, not compressed video size. A source can be
long enough to contain several speakers; select a clean single-speaker reference
before enhancement. The native voice conditioner uses at most the first
30 seconds of a reference. Reader WAV export has separate RIFF size limits.

Imports, waveform scans and video decoding stream bounded blocks off the UI
thread. Waveforms retain a fixed peak envelope, not a full-source PCM copy.
Unsupported formats, corrupt files and over-limit inputs report errors.

For trimming and accepted WAV formats, see [Trimming and speed](TRIMMING_AND_SPEED.md).
For codec limitations, see [Extract from Video](VIDEO_VOICES.md).

## Layout and accessibility

Material 3 cards and sheets share theme tokens, readable contrast, rounded
geometry and restrained borders. Light mode uses opaque tonal surfaces;
Dark and AMOLED use readable charcoal surfaces without requiring backdrop blur.

Transport and trim buttons have 48 dp touch targets. Playback and trim sliders
provide accessible progress actions. Selected voices use tonal emphasis, an
accent outline, a checkmark and accessibility selection state.

Sheet titles and Close controls remain available while content scrolls.
Save/apply actions are separated from audio selection. Recording shows microphone
permission, live duration, actual amplitude and processing states. Import failures
offer a retry picker; enhancement failure keeps the original usable.

## Validation checklist

Contributors should check portrait/landscape, enlarged text, all theme modes,
keyboard transitions, long documents and voice lists, sheet scrolling, error
recovery, focus visibility and screen-reader navigation.

Run the checks in [Building and testing](BUILDING.md). Automated boundary and
UI tests do not cover every device codec, storage failure, thermal condition or
accessibility interaction; include relevant real-device checks with changes.

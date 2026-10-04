# Reader rendering and audio data

This reference describes the Reader's rendering and lifecycle behavior.
For controls and everyday use, see the [Reader guide](READER.md).

## Highlighting and scrolling

`ReaderTextChunker.ranges` supplies UTF-16 source offsets for actual synthesis
chunks. `ReaderPlaybackService` publishes the selected chunk's range.
`ReadingHighlight` applies a single background span without changing text
selection or maintaining another copy of the document.

Automatic following uses Android text-layout bounds and the visible clipped
rectangle. Manual scrolling suspends follow without losing the current highlight;
Pause cancels movement. `BringIntoViewRequester` handles scrollable ancestors
on compact layouts. Changes to the document invalidate cached speech.

## Audio waveform

`AudioPeaks` collects real synthesis PCM while the service writes its WAV cache.
It retains at most 512 peaks by merging bins as duration grows, rather than
keeping a second full audio buffer. Playback position determines the waveform
playhead. The waveform forms the accessible slider's track, combining visual
progress and seeking in one control.

Voice previews inspect actual WAV peaks on a worker. Recording waveforms use
microphone PCM amplitude. Neither display uses a decorative looping waveform.

## Keyboard and layout

The native Android editor is embedded in Compose for text selection and IME
behavior. Layout mode is based on window configuration and text scaling.
Keyboard insets are handled during layout; secondary sections collapse to keep
text and transport controls reachable. Small windows retain a scrollable fallback.

Starting playback snapshots the current editable text. Player/session ownership
guards prevent stale preparation, seek or completion callbacks from changing a
replacement session.

## Contributor checks

`ReaderVisualDataTest` checks text ranges and bounded peak collection.
`ReaderEnhancementsTest` and `ReaderModesTest` check highlighting, editing,
scrolling, recreation and keyboard layout. `PlaybackVoiceEditingTest` also
exercises real generation and pending-chunk transport on ARM64.

Deterministic layout snapshots and generated WAV fixtures are useful regression
checks, but do not establish subjective speech quality. Use ARM64 hardware for
native generation. See [test commands](BUILDING.md#device-checks).

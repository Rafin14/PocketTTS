# Reader guide

The Reader turns your text into offline speech using the selected Pocket TTS
voice. The text area is scrollable and the compact player keeps the document
visible while listening.

## Read and edit

1. Open Reader and choose **Edit Mode** to type or paste text.
2. Choose a voice and press **Play**. Playback can start with the keyboard open.
3. Use **Done editing** for read-only scrolling.

Editing text or entering Edit Mode stops an existing reading so cached speech
cannot resume against a changed document. The document is saved in app-private
storage. Clear requires confirmation.

The current spoken section is highlighted using actual TTS chunk boundaries,
not word-level alignment. Manual scrolling temporarily suspends automatic
following; following resumes at the next chunk or when playback resumes.
Pause retains the highlight without moving the text. Stop clears it.

## Playback controls

- **Play / Pause / Resume:** control the current reading.
- **Previous:** restart a partly played chunk, or move to the preceding chunk
  when already at its start.
- **Next:** select the next chunk, including one still being generated.
- **Stop:** stop audio and cancel remaining generation.
- **Seek:** move within audio already generated, using the slider or waveform.
- **Speed:** choose 0.50×–2.00× without changing exported audio.

A pending chunk buffers until its audio is ready. Paused navigation stays paused.
Already generated chunks are reused rather than synthesized again. The chunk
counter, highlighting and Android media controls share the same playback state.

Speech starts when the first chunk is ready. Generation can continue during
Pause. The displayed duration describes available generated audio until the
whole document finishes; it is not an estimate of unfinished speech.

## Background playback

Reader playback uses a foreground service and Android media session. Available
controls appear in notifications, on the lock screen and through compatible
headset controls. Their presentation varies by Android version and device.

Audio-focus loss or disconnecting headphones pauses playback. Resume is explicit.
Sample previews are foreground-only and do not replace Reader's media session.

## Export audio

Once generation finishes, **Export WAV** opens Android's document picker.
Choose the filename and destination. The export joins cached PCM chunks into
one WAV and uses the original synthesis speed, regardless of listening speed.

Canceling the picker leaves the cached audio available. A failed export can
leave a partial destination file. No broad storage permission is required.

## Limits

Generated PCM consumes approximately 173 MB per hour at 24 kHz mono PCM16.
Standard RIFF WAV exports must fit within the format's 4 GiB size limit.
Generation slower than playback causes buffering; chunk transitions can have
short preparation gaps.

Generated audio is temporary and is not recovered after process death.
Saved documents and voices persist. Export audio you want to keep.

See [building and testing](BUILDING.md),
[voice management](PLAYBACK_AND_VOICE_EDITING.md) and
[theme and Reader behavior](READER_MODES_AND_ABOUT.md).

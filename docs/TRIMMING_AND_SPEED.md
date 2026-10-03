# Sample trimming and playback speed

## Trim a recording or imported sample

After recording, importing a WAV or extracting video audio, review the full
waveform and select one speaker.

1. Drag the trim handles to choose the interval.
2. Fine-tune Start and End with the ±0.1-second buttons.
3. Tap or drag the waveform to set the preview position.
4. Preview the selection, then choose **Use Trimmed Audio**.
5. Review the unenhanced and enhanced samples, choose one, and save.

The selection must be 3–30 seconds for trimming/enhancement. **Continue without
trimming** preserves the full sample; samples outside the enhancement window
cannot be denoised. Source WAVs are limited to 256 MiB and 30 minutes.

Selected previews use a separate normalized WAV rather than timer-bounded
playback of the full source. Changing trim handles stops the old preview.
The original external file is never modified.

## Supported WAV input

Imports support 1–8 channels at 8–192 kHz, with PCM8/16/24/32 or IEEE float32/64.
The shared dr_wav decoder and band-limited resampler produce mono PCM16
24 kHz selected references. Corrupt, unsupported and non-finite audio is rejected.

Scanning uses bounded blocks and a fixed peak envelope. Applying a selection
decodes the chosen interval on a worker. Saving preserves the full original and
available derived samples in app-private storage.

To trim an already saved custom voice, use its **Edit audio** action. See
[voice management](PLAYBACK_AND_VOICE_EDITING.md).

## Playback speed

Reader and sample previews offer 0.50×–2.00× in 0.05× steps and a 1.00× reset.
Rate changes apply to the actual MediaPlayer with pitch fixed at 1.0.
Changing speed while paused does not resume playback.

Reader speed is remembered. Preview speed is an audition setting and does not
change saved reference audio. Playback speed does not alter generated PCM,
exported WAV duration, voice conditioning or Android system-TTS client settings.

## Cleanup

Re-recording replaces an unsaved take. Closing or backgrounding an unsaved
recording/import workflow discards temporary files. Native cleanup is serialized
behind active work; saved voices and original archives are not affected.

See [offline enhancement](DEEPFILTER.md) and [Reader controls](READER.md).

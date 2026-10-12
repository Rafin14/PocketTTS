# Audio trimming, gain and speed

## Choose a reference

After recording, importing WAV or extracting video audio:

1. Drag the trim handles to select a clean, single-speaker interval.
2. Fine-tune Start and End with the ±0.1-second buttons.
3. Tap or drag the waveform to choose the preview position.
4. Preview the selection and choose **Use Trimmed Audio**.
5. Compare original/enhanced audio, choose a version and save.

Trimming and enhancement require a 3–30-second interval. **Continue without trimming** keeps the full source; sources outside that duration cannot be enhanced. The original external file is never modified.

Preview uses a separate selected WAV, so discarded audio does not leak into playback. Changing selection stops the obsolete preview. Closing or backgrounding an unsaved workflow discards its temporary files.

## Gain and loudness normalization

Reference gain supports 50–200% (approximately −6 to +6 dB), with a reset to unity. Peak protection reduces effective gain when necessary to avoid exceeding 98% of full scale.

The review prepares separate unenhanced and denoised versions, then applies the selected gain/normalization to their review outputs. Gain changes rebuild from preserved raw audio rather than applying amplification repeatedly. Saving retains the edit recipe and source.

**Normalize volume** uses gated, K-weighted loudness measurement and a target calibrated from the bundled Alba reference, bounded between −24 and −16 LUFS. It is not simply peak maximization. Gain and peak protection can prevent reaching the target; silence has no valid normalization target. The interface reports the resulting adjustment.

Reference gain affects the audio used for voice conditioning. Reader per-voice gain affects generated listening audio instead. Android media volume is a third, device-level setting.

## WAV formats

Source WAVs support 1–8 channels, 8–192 kHz sample rates, PCM8/16/24/32 and IEEE float32/64. Selected references become mono PCM16 at 24 kHz through the shared decoder and band-limited resampler.

Source limits are 256 MiB and 30 minutes. Unsupported, corrupt or non-finite data is rejected. Waveform inspection uses bounded blocks rather than a full-source in-memory copy.

## Playback speed

Reader and sample previews offer 0.50×–2.00× in 0.05× increments and an exact 1.00× reset. Rate changes preserve pitch; changing speed while paused does not resume playback.

Reader speed is remembered. Preview speed is an audition preference. Listening speed does not change saved references, generated PCM, exported WAV duration or another application's system-TTS settings.

See [voice editing](PLAYBACK_AND_VOICE_EDITING.md), [noise reduction](DEEPFILTER.md) and [Reader](READER.md).

# Voice management and playback

## Select and preview

The selected voice is marked with a checkmark, accent outline and tonal surface.
Choose **Use** to update the voice used by Reader. Switching voices stops an
existing reading so Resume cannot continue using the old voice.

**Preview** opens the full player directly, with the voice name, real WAV
waveform, progress, seeking, pause/resume, stop and speed controls. Starting a
different preview releases the previous one. Completion, Stop or closing the
sheet ends the preview. Sample playback is foreground-only.

Shared bottom sheets resist short downward drags and residual scrolling flings.
Use a deliberate larger downward drag, Close, Back or the outside scrim to dismiss.
Internal scrolling, waveform seeking and trimming remain independent of dismissal.

Voice management is unavailable while active Reader generation/output would
conflict with the operation. Stop the Reader first.

## Rename a custom voice

Open the voice's action menu or details and choose **Rename**. Names are trimmed,
must contain 1–80 characters and must not duplicate another name in the pack
(case-insensitive). Rename preserves the voice ID, selected state, reference
audio and other metadata.

Built-in and protected legacy voices do not expose destructive user-voice actions.

## Edit a saved reference

Choose **Edit audio** to open the existing reference in the shared trimming and
enhancement workflow:

1. Select and preview a 3–30-second interval.
2. Optionally adjust **Volume** from 50–200%, or reset to 100%. Gain changes actual
   samples, not just preview loudness. Peak limiting prevents clipping; the review
   reports any reduced effective gain.
3. Review the real Before/After DeepFilterNet3 output.
4. Choose the sample to use, then **Save changes**.

The operation updates the existing voice rather than creating a duplicate.
Its ID, name and selection remain stable. Canceling leaves the saved reference
unchanged and removes unsaved temporary files.

## Restore Original

Edited custom voices offer **Restore Original**, with confirmation. This restores
the archived original as the actual reference used for cloning, not just a
display label. Obsolete derived audio and cached Reader speech are invalidated.

For older voices without an archived source, the first edit preserves the
earliest reference still available. Audio lost before an original was archived
cannot be reconstructed.

Deleting a custom voice removes its reference and archived samples after
confirmation. Exported Reader audio is unaffected.

## Contributor reference

`ModelPackRepository` stores voice metadata in an atomic JSON manifest.
Audio edits write new versioned files before committing the reference pointer;
cancellation before commit retains the previous reference. Original archives
are preserved across updates.

The existing engine lock serializes reference changes and native access.
Conditioning is invalidated and rebuilt on the next synthesis. Android system
TTS resolves the current reference under the same lock. Reader navigation uses
actual chunk indices and cached files, with guards against stale callbacks.

`PlaybackVoiceEditingTest` covers pending-chunk navigation, paused completion,
keyboard-visible generation, rename validation/persistence, trim/denoise/save,
cancellation, restoration and actual edited/restored-reference synthesis.
Use [the build guide](BUILDING.md#device-checks) for test commands.

See [Reader](READER.md), [trimming](TRIMMING_AND_SPEED.md) and
[DeepFilterNet3](DEEPFILTER.md).

## Portable exports

Every voice's action menu offers **Save .wav to device**. The system picker lets
you choose and rename the destination. The export copies the currently accepted
reference, including edits, rather than generating new speech or exporting an
obsolete original. Canceling leaves the voice unchanged.

Back up all custom voices from **Settings → Voice backup**. See the
[backup guide](VOICE_BACKUP.md) for restoration, privacy and compatibility.

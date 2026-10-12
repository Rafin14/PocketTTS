# Voice library and audio editing

## Select and preview

Tap a voice card to choose the Reader voice. A checkmark, accent border and tonal background identify the selection. Changing voices stops existing Reader playback.

**Preview** opens a player with the voice name, actual WAV waveform, progress, seeking, pause/resume, stop and speed controls. Starting another preview releases the previous sample. Sample playback is foreground-only; Reader retains its own background media session.

Custom voices expose their actions in the three-dot menu and details. Stop active Reader generation/playback before operations that need exclusive access to voice data.

## Edit a saved voice

Choose **Edit audio** to open the shared trimming and enhancement workflow:

1. Select and preview a 3–30-second region.
2. Adjust reference gain or use loudness normalization.
3. Compare the unenhanced and DeepFilterNet3-enhanced samples.
4. Choose the version to use and **Save changes**.

Saved gain, trim and enhancement settings are retained when reopening an edited voice. Processing starts from the preserved source rather than repeatedly amplifying the already processed file. Preview, accepted reference and WAV export use the selected processed version.

Edits preserve the voice's identity, name and selected state. Canceling leaves the saved voice unchanged. The source is retained for **Restore Original**; older voices without an archive preserve the earliest available reference on first edit.

Reader amplification is a separate per-voice playback preference. It does not modify the reference used for cloning or another voice's amplification setting.

## Manage and export

- **Rename:** provide a unique name of 1–80 characters; identity and audio remain unchanged.
- **Restore Original:** confirm restoration of the archived source, discarding current derived edits.
- **Save .wav to device:** export the accepted reference, including saved edits, through Android's file picker.
- **Delete:** confirm removal of the custom reference and its archived samples. Previously exported files are unaffected.

Built-in voices do not expose destructive custom-voice actions. Back up the custom library through **Settings → Voice backup**.

See [Reader](READER.md), [trimming and speed](TRIMMING_AND_SPEED.md), [noise reduction](DEEPFILTER.md) and [backups](VOICE_BACKUP.md).

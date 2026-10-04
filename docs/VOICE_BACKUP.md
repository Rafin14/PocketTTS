# Voice backup and restore

Use **Settings → Voice backup → Export Voices** and choose a destination in
Android's system file picker. Keep the ZIP outside app-private storage so it
survives uninstalling. **Import Voices** selects a previously exported ZIP.
Progress, voice counts, cancellation and failures appear in Settings.

Backups contain recordings and are **not encrypted**. Store/share them carefully.
Pocket TTS does not upload them or add network permissions. A cloud-backed file
provider chosen in Android's picker may sync the document independently.

## Contents and compatibility

Format `pockettts-voices`, schema version 1:

```text
manifest.json
voices/0/current.wav
voices/0/original.wav
voices/0/before.wav       # if available
voices/0/enhanced.wav     # if available
voices/1/...
```

The manifest records app/schema versions, voice count, stable ID/source ID, name,
model pack ID, language, precision, edited state, WAV sizes/SHA-256 checksums and
enhancement-model metadata. Only custom voices are included; built-in voices,
models, logs, preferences and Reader speech caches are excluded. Conditioning is
rebuilt from the accepted reference on the next synthesis, so no transient
native embedding cache is needed.

Import requires the matching installed model pack, language and precision.
Unsupported backup versions are rejected explicitly rather than guessed.
Existing IDs and names are preserved when available. Conflicts receive a fresh
stable ID and numbered name; source-ID provenance is retained. Existing voices
are never overwritten. Imported voices support normal selection, preview,
editing, enhancement, original restoration and WAV export.

## Safety and limits

Imports validate the complete ZIP, allowed paths, CRCs, metadata, checksums and
supported WAVs before committing. Arbitrary archive paths are never filesystem
destinations. Missing, duplicate, corrupt or unreferenced entries are rejected.
Failed/canceled imports roll back their manifest changes and staged files;
existing voice files are not replaced.

Limits are 1,000 voices, a 2 MiB manifest, 256 MiB per audio file and 4 GiB total
decoded audio. Imports require temporary disk space for the ZIP and decoded
files plus restored audio. Low storage or provider failures produce an error.
An interrupted export can leave an incomplete destination; remove it and retry.
Leaving/recreating the activity cancels an active transfer safely rather than
running an unowned background job. Backups do not automatically restore the
previous Reader document or selected voice preference.

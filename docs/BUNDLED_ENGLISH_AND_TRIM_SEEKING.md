# Bundled English model and trim preview

## Offline preparation

English FP32 and the Alba reference are bundled in the APK. First launch copies
them to app-private storage for the native engine; no model download or model-pack
picker is required.

Gradle's `bundledPocketAssets` task reads `PocketTTS-english-FP32.zip` and
packages the model graphs, tokenizer, Alba WAV, manifest and attribution as
assets. A generated `files.tsv` records sizes and SHA-256 checksums. The root
ZIP itself is not also shipped as a runtime archive.

`BundledPocketTts.ensure` prepares files on a worker using atomic writes.
Matching installed files are reused, custom voices and manifest entries are
preserved, and interrupted preparation retries locally. Insufficient storage
produces an error rather than a completed installation.

The native engine needs filesystem paths, so extracted models occupy additional
storage alongside the APK. Allow approximately 420 MiB for Pocket model assets,
plus generated audio, recordings and temporary files.

The required archive uses Git LFS. Follow the [build guide](BUILDING.md) to
retrieve the actual ZIP instead of an LFS pointer. Legacy export formats are
documented separately in [Model packs](MODEL_PACKS.md).

## Trim preview seeking

The waveform represents the full source. Trim handles choose the retained
interval, with separate ±0.1-second adjustment buttons. Tapping or dragging the
waveform seeks within that selection; a playhead shows the source timestamp.

Preview plays a separately normalized selected WAV, so discarded leading and
trailing audio cannot leak into playback. Source positions are translated into
clip-relative positions for the shared MediaPlayer. Paused seeking stays paused;
active seeking stays active. Completion leaves trim preview at the selected end,
and replay starts at the selection's beginning.

Changing the selection stops the obsolete preview and clamps its playhead.
Applying the trim or leaving the sheet releases preview resources. The full
original remains preserved. See [trimming and speed](TRIMMING_AND_SPEED.md).

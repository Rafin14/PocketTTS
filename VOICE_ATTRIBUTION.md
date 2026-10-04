# Bundled official Pocket TTS voices

The Android app bundles English FP32 and the Alba reference voice. Optional
legacy export tooling does not imply multilingual support in the distributed APK.
The test-only `speech-test.mp4` is an eight-second Alba excerpt encoded as stereo
AAC with a generated video pattern; it demonstrates video import and audio editing.

The release model packs bundle only the official default reference voices
defined by Kyutai Pocket TTS. They do not contain user-imported recordings.

| Pack | Voice | Upstream recording | License and attribution |
| --- | --- | --- | --- |
| English | Alba | [`alba-mackenna/casual.wav`](https://huggingface.co/kyutai/tts-voices/blob/main/alba-mackenna/casual.wav) | Voice performance by Alba MacKenna; CC BY 4.0 |


License: https://creativecommons.org/licenses/by/4.0/

Changes made for the Android packs: each upstream recording is converted to
24 kHz mono 16-bit PCM WAV and renamed to its stable Pocket TTS voice ID. No
voice is synthesized, edited to impersonate another speaker, or mixed with a
user recording during packaging.

The names above are Pocket TTS catalog identifiers. Their inclusion does not
imply endorsement by Kyutai or by any recorded speaker. Other rights, including
privacy, publicity, and moral rights, may still apply. Use the voices lawfully
and do not present generated speech as an authentic recording of a real person.

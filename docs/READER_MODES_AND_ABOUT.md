# Reader modes, appearance and About

## View and Edit modes

Reader defaults to read-only scrolling. **Edit** enables the native Android
editor's typing, cursor, text selection and clipboard operations. **Done editing**
returns to read-only mode and hides the keyboard without changing the document.

Entering Edit Mode stops existing playback. Play remains available while editing
and with the keyboard open; changing text stops the reading so stale audio cannot
be resumed. The document and editing preference survive activity recreation.

## Follow behavior

The highlight follows actual TTS sections, not guessed word timings. Reader
automatically brings the current section into view while playing. Touching or
scrolling the text suspends following without stopping audio. Following resumes
at the next chunk or explicit Resume, and does not interrupt a held gesture.
Pause preserves the highlight and stops scrolling.

## Appearance

Choose **System**, **Light**, **Dark** or **AMOLED** in Settings.

- System follows Android's light/dark preference.
- Light uses readable opaque tonal surfaces.
- Dark uses near-black backgrounds, translucent charcoal surfaces, subtle borders
  and muted periwinkle controls.
- AMOLED uses a true-black main background with readable surfaces above it.
- Green indicates appropriate active or successful states.

Theme tokens are centralized in `PocketTheme.kt`. The glass treatment uses
tinted surfaces rather than expensive backdrop effects and remains usable
without blur. Controls, dialogs and sheets use Material 3 behavior.

## Navigation and haptic feedback

Tap the floating dock to switch between Reader, Voices and Settings. Horizontal
swipes over the screen heading move to an adjacent tab without intercepting
text editing, vertical scrolling, sliders or waveforms. Tabs retain their
scroll positions.

Hold the dock and slide across its destinations to preview a selection. The
outlined preview differs from the filled current tab. Release over a destination
to navigate; release outside or cancel to keep the current page.

Settings → Appearance provides a master haptic toggle. Meaningful control
increments, confirmed transport changes, selections and completed operations
use restrained feedback that also respects Android's settings. Automatic speech
chunks and scrolling remain silent. Reduced-motion settings disable custom
movement while keeping selection feedback readable.

## About

Settings → About shows the app version, feature information, privacy/consent
guidance and component attribution. Enhancement license notices are readable
offline. For complete source and model licensing, see
[third-party notices](../THIRD_PARTY_NOTICES.md),
[model license](../MODEL_LICENSE.md) and
[voice attribution](../VOICE_ATTRIBUTION.md).

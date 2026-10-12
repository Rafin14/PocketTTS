# PocketTTS design system

PocketTTS uses Jetpack Compose and Material 3 with layered tonal surfaces, restrained translucency and platform typography. The visual system is shared across Reader, Voices, audio editing, Settings and overlays.

## Structure

- `PocketTheme.kt`: appearance palettes, typography, shapes and glass surface colors.
- `StudioDesign.kt`: reusable spacing, controls, cards, navigation and press feedback.
- `TabGestures.kt`: safe heading gestures between sibling destinations.
- `InteractionFeedback.kt`: native, preference-aware haptic policy.

## Colors

| Role | Light | Dark / AMOLED |
| --- | --- | --- |
| Background | `#F0F3FB` | `#0B0E19` / `#000000` |
| Reading surface | `#F5F7FD` | `#151D30` |
| Surface | `#FCFDFF` | `#1B2337` |
| Raised surface | `#E4EAF7` | `#263149` |
| Accent | `#4056C7` | `#B4C4FF` |
| Selected surface | `#E2E8FF` | `#293E72` |
| Primary text | `#17213A` | `#F4F6FF` |
| Secondary text | `#4C5973` | `#BFCBE3` |
| Success | `#116743` | `#72E5BC` |
| Error | `#BA1A1A` | `#FFB4AB` |

Primary buttons use indigo with white text. Success green represents active/successful states, not decoration. Glass treatments use readable, high-opacity surfaces and fine borders rather than continuous backdrop blur.

## Geometry and typography

Cards use 24 dp corners, sheets 34 dp top corners and the navigation dock 26 dp corners. Page padding is 20 dp; related controls use 12 dp spacing, while wrapped peer actions retain 10 dp gaps in both directions. Material touch targets remain at least 48 dp.

Android's system font and text-size preferences are retained. Hierarchy combines size, weight and line height. Long voice names wrap rather than shrink or disappear. The native document editor uses 18 sp with generous line spacing. Compact layouts and enlarged text use scrollable fallbacks.

## Interaction

Press feedback responds through Compose interaction sources. Selection and drag indicators use critically damped springs; the preview outline remains distinct from committed navigation state. Only safe header gestures switch tabs, leaving text, lists, sliders and waveforms independently interactive.

Material sheets retain standard scrolling, accessibility and dismissal mechanisms. Android's animation-duration preference disables custom movement when zero. Haptics use native predefined feedback, meaningful value increments and throttling, with one master setting.

## Component conventions

Reader prioritizes the document, selected voice and transport. Voices use distinct identities and clear selected-state semantics. Audio workflows group waveform, preview, processing and save actions. Settings group related options. Dialogs and sheets share readable colors, title/close patterns and appropriate destructive-action confirmation.

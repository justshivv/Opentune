# Optional interface designs

Open **Settings → Look and feel → Interface design**. Choose a style and try the Home and Player previews before pressing **Apply design**. Closing the preview or pressing Cancel leaves settings unchanged.

| Style | Appearance |
| --- | --- |
| Current | Original OpenTune interface and individual animations. This remains the default. |
| Flow | Larger shelf artwork, soft surfaces, a continue-listening card and spacious headings. |
| Editorial | Serif headings, restrained outlined surfaces and smaller corner radii. |
| Glass | Translucent cards, outlined surfaces and a luminous floating dock. |

The optional designs also include Library filters, coordinated settings groups and sheets, a styled mini player and player title card. Playback, search, queue actions, local music, downloads and service integrations keep their existing behaviour.

## Motion and layout

- **Existing** keeps individual dock animation, dock lens, page transition and player opening choices. The original dock remains available in every design with this setting.
- **Calm** uses short fades and restrained press feedback.
- **Fluid** adds smooth page movement and a softly springing five-destination dock.
- **Expressive** gives the dock and touch feedback a little more spring.
- **Comfortable / Compact** changes the new designs' artwork sizes and spacing.
- **Follow scrolling / Always visible** controls dock visibility in the optional designs.

Reduce animation takes priority. New motion also honours Android's animation scale. Glass uses the existing glass-style and reduced-blur controls, including an opaque fallback when blur is unavailable.

## Saved preferences

These options live in the additive `ui.design` settings object. Applying a design changes only that object. Existing theme, player layout, cover effects, lyrics, haptics and individual animation choices are preserved. Selecting Current restores the original presentation without rewriting those choices. Older backups with no design object restore as Current.

CI builds debug and release APKs, runs settings compatibility and Compose interaction tests, and uploads native-rendered screenshots as the **interface-design-previews** artifact. Previews include light/dark styles, full players, Library, and a narrow RTL layout with large text.

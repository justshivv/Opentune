# Optional interface designs

Open **Settings → Look and feel → Interface design**. Choose a style and try the Home and Player previews before pressing **Apply design**. Closing the preview or pressing Cancel leaves settings unchanged.

| Style | Appearance |
| --- | --- |
| Current | Original OpenTune interface and individual animations. This remains the default. |
| Flow | A listening dashboard with artwork heroes, collection tiles, discovery entry points, a title-first player with a transport tray, and an expanding destination dock. |
| Editorial | Serif headings, restrained outlined surfaces and smaller corner radii. |
| Glass | Translucent cards, outlined surfaces and a luminous floating dock. |

Flow changes the screen hierarchy: Home has For you / Recents / Mixes feeds; Library leads with saved collections and a cover grid for playlists; Search offers real mood searches and music recognition; Settings leads with Look and feel, Motion and Player. Its full player separates Playing / Lyrics / Queue with tabs, places the title above an artwork stage and action rail, and groups transport controls in a tray. Album and playlist shelves use artwork-backed cards throughout the app.

Editorial and Glass retain their existing presentation. All optional designs include Library filters, coordinated settings groups and sheets, and a styled mini player. Playback, search, queue actions, local music, downloads and service integrations keep their existing behaviour.

## Motion and layout

- **Existing** keeps individual dock animation, dock lens, page transition and player opening choices. The original dock remains available in every design with this setting.
- **Calm** uses short fades and restrained press feedback.
- **Fluid** adds smooth page movement and a softly springing dock. Flow separates Search from four destinations and expands the active destination into a labelled pill when space allows.
- **Expressive** gives the dock and touch feedback a little more spring.
- **Comfortable / Compact** changes the new designs' artwork sizes and spacing.
- **Follow scrolling / Always visible** controls dock visibility in the optional designs.

Reduce animation takes priority. New motion also honours Android's animation scale. Glass uses the existing glass-style and reduced-blur controls, including an opaque fallback when blur is unavailable.

## Saved preferences

These options live in the additive `ui.design` settings object. Applying a design changes only that object. Existing theme, player layout, cover effects, lyrics, haptics and individual animation choices are preserved. Selecting Current restores the original presentation without rewriting those choices. Older backups with no design object restore as Current.

CI builds debug and release APKs, runs settings compatibility and Compose interaction tests, and uploads native-rendered screenshots as the **interface-design-previews** artifact. Previews include light/dark styles, actual full players, Library, and a narrow RTL layout with large text. Flow also captures the actual populated and cold-start Home, Search and Settings screens, plus a 360 × 740 player at 120% text size. Home uses the same state-driven layout as production; no network or account is needed for its fixtures. Interaction checks cover feed switching, play callbacks, discovery queries, recognition, settings search and player panes.

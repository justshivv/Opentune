# App languages

Settings → Appearance → App language offers System language, English, हिन्दी and ਪੰਜਾਬੀ. Android 13 and newer also expose these choices in the system's per-app language settings. Older Android versions save the choice in OpenTune. Changing it recreates the activity, preserves the playback service and refreshes Home; YouTube requests use the chosen locale.

The initial translations cover navigation, common player/menu actions, key Home headings and settings labels. Detailed settings explanations and provider-supplied status text can still fall back to English. Song titles, artist names, user playlists and lyrics are never machine-translated. Lyrics translation remains a separate feature.

Community translations use normal Android resources:

- `app/src/main/res/values/ui_strings.xml`: English reference.
- `app/src/main/res/values-hi/ui_strings.xml`: Hindi.
- `app/src/main/res/values-pa/ui_strings.xml`: Punjabi (Gurmukhi).

Preserve resource names and any formatting placeholders. `UiStrings.kt` maps existing UI labels to resource IDs during the gradual migration from hard-coded strings. Add new strings to all supported locales and add new languages to `AppLanguage` and `locales_config.xml`. Test narrow screens, screen-reader labels and long translations before publishing.

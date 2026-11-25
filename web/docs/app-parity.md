# NepTune web and Android

The web player extends the existing web application, using the current Android app as the design reference. It keeps account integration, search, albums, playlists, library, queue editing, lyrics, Listen Together and browser extension playback.

## Shared appearance and interaction

- System, light and dark appearance, pure-black dark surfaces, ten Android accent choices, and artwork-following colors. Preferences persist in this browser.
- Mesh, gradient and echo-glow player backgrounds; classic, vinyl, lyrics-first, minimal, cassette, halo and polaroid cover presentations. Browser presentations adapt the Android layouts rather than running Compose animations.
- Solid full-player controls, floating glass mini-player, and mobile navigation.
- Compact icon and quality text centered under the seek bar. A FLAC search result does not change the quality label; the active engine must first load that file.
- Quick picks, a small history shortcut, playlist and Made for you links, varied feed shelves and a refresh action that reloads the home feed.
- Source toggles and visible drag handles, plus keyboard arrow reordering and explicit Move up/down actions.
- Loudness normalization (Settings › Playback, on by default) from YouTube's own loudness measurement, at the phone's Normal level. An audio element can't play above full volume, so the browser turns loud songs down but doesn't lift quiet ones, and has no Quiet/Loud levels. JioSaavn streams keep the YouTube measurement; lossless add-on files play untouched, as on Android.
- A focus ring for keyboard use only (Tab or the arrow keys); mouse and touch don't show it.
- Existing monthly community invitations and GitHub/Discord links remain in place.

## Platform differences

The browser does not expose Android wallpaper colors, Android audio effects, physical output routing, Android download workers, or the app's local media library. Web preferences and local source order are browser-specific; this change does not introduce account synchronization.

YouTube Music playback and account integration require the companion browser extension. Phone browsers that cannot install the extension should use the Android app. Browser addons currently require direct FLAC with media CORS; segmented streams and physical lossless-output checks remain Android features. The audio details view reports browser stream information without claiming bit-perfect hardware output.

## Validation

Run `pnpm test`, `pnpm typecheck`, and `pnpm --filter @opentune/app build` from `web/`. Check appearance persistence, the phone dock, source dragging with mouse/touch/keyboard, the quality label at narrow widths, and light/dark contrast in a real browser before release.

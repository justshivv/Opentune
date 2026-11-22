# OpenTune web preview

This extends an existing GPL-3.0 React player and WXT browser extension, named with its commit in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). It reuses that implementation rather than rebuilding the web app. Upstream design/research notes are preserved under `docs/` with their original attribution.

OpenTune changes include branding, GitHub/Discord community links, URL audio add-ons, measured direct-FLAC validation, background quality upgrades, a separate extension identity, and GitHub Pages routing under `/Opentune/player/`. Chrome and Firefox extensions use the same protocol. Google/Spotify session cookies remain inside the extension; the player receives metadata and request results.

## Build

Use Node 24 and pnpm (the package manager version is pinned in package.json).

```sh
cd web
pnpm install --frozen-lockfile
pnpm typecheck
pnpm test
pnpm --filter @opentune/extension icons
pnpm build:site
```

The test command downloads checksum-verified JSON fixtures from the pinned upstream commit if absent. There are no production API calls during those tests. `app/dist/` contains the site and packaged extensions under `downloads/`. The preview workflow uploads it as an artifact. GitHub Pages builds and publishes it at https://justshivv.github.io/Opentune/player/ on updates to main.

For development run `pnpm dev` and `pnpm dev:ext`. The app uses a hash router so refreshing a nested route works on GitHub Pages. The production base is `/Opentune/player/`; set `OPENTUNE_WEB_BASE=/` for another root deployment and adjust extension allowed origins/paths deliberately.

## Audio add-ons

Settings → Audio sources accepts a service root or manifest URL. Users can enable, remove and reorder sources. Background lookup verifies title, performer, duration and FLAC STREAMINFO; the best measured result wins, with source order breaking ties. Failed upgrades restore normal YouTube playback at the previous position. Audio stays on the listener's connection; there is no hosted audio proxy.

This browser preview supports **direct FLAC with CORS-enabled media**. Android additionally supports unencrypted HLS/DASH addons. Browser sources requiring a verified physical lossless output are skipped because the browser cannot attest the output route. Metadata requests can use the extension after the user grants the individual HTTPS host. The extension accepts bounded GET requests without custom headers, ambient credentials or redirects.

## Known platform limits

- Browser playback requires the OpenTune extension. It is not yet published to browser stores; install a preview package in developer mode. Firefox release installation requires Mozilla signing; temporary loading is suitable for testing.
- Native phone-to-TV QR sign-in is implemented in the Android app, using encrypted local-network pairing. It is not a web OAuth substitute and is not available in the JavaFX desktop or Wear UI.
- Android uses Nostr rooms; the inherited browser room protocol is different. Browser rooms require a separately configured compatible server. No upstream public server is silently selected, and cross-platform room interoperability is not claimed.
- Discord community links work normally. Upstream optional Discord account automation is disabled by default at build time.
- Android-only visual customisations and device-output controls are not all represented in this browser preview.

See [the addon author guide](../docs/audio-addons.md) and [the starter](../examples/audio-addon/README.md).

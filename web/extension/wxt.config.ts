import { readFileSync } from 'node:fs';
import { defineConfig } from 'wxt';
import { LYRIC_ORIGINS, patternOf } from './lib/lyricHosts';
import { matchPatternsFor, SITE_ORIGINS } from './site.config';

/** OPENTUNE_STORE_BUILD=1 for a Chrome Web Store upload. */
const STORE_BUILD = process.env.OPENTUNE_STORE_BUILD === '1';

/**
 * Discord presence is user-token automation (prompt §7.3), so it is a build
 * flag: on for the site's own zips, off for a Web Store upload unless
 * DISCORD_PRESENCE=1 says otherwise.
 */
const DISCORD_PRESENCE = process.env.DISCORD_PRESENCE ? process.env.DISCORD_PRESENCE === '1' : !STORE_BUILD;
const DISCORD_ORIGINS = ['https://discord.com/*'];
/** NepTune's own Discord application, for cover images and buttons; empty leaves both to plain links. */
const DISCORD_APP_ID = (process.env.DISCORD_APP_ID ?? '').trim();
if (DISCORD_APP_ID && !/^[0-9]{17,20}$/.test(DISCORD_APP_ID)) throw new Error('DISCORD_APP_ID must be a Discord application id');

const devPublicKey = readFileSync(new URL('./dev-key/public-key.txt', import.meta.url), 'utf8').trim();

export default defineConfig({
  manifestVersion: 3,
  vite: () => ({
    define: {
      // Checked against every sender at runtime (entrypoints/background.ts).
      __SITE_ORIGINS__: JSON.stringify(SITE_ORIGINS),
      __DISCORD_PRESENCE__: JSON.stringify(DISCORD_PRESENCE),
      __DISCORD_APP_ID__: JSON.stringify(DISCORD_APP_ID),
    },
  }),
  manifest: ({ browser }) => ({
    name: 'NepTune',
    description: DISCORD_PRESENCE
      ? 'Connects the NepTune web player to YouTube Music, Spotify, lyric sources and Discord.'
      : 'Connects the NepTune web player to YouTube Music, Spotify and lyric sources.',
    homepage_url: 'https://justshivv.github.io/Opentune/player/',
    action: { default_title: 'NepTune' },
    // Extension pages run only their own scripts, embed nothing and can't be framed.
    content_security_policy: {
      extension_pages: "script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
    },
    // `cookies` reads the YouTube session for signed-in requests (phase 5);
    // the cookies themselves never leave the extension (lib/ytAuth.ts).
    // `scripting` reads the Discord token from a discord.com tab, and only on
    // the user's "Connect Discord" (lib/discord.ts).
    permissions: ['storage', 'declarativeNetRequest', 'cookies', ...(DISCORD_PRESENCE ? ['scripting'] : [])],
    // YouTube (phases 3 and 5) and Spotify (phase 9; the sp_dc cookie is read
    // and used here only, lib/spotify.ts). The site's own origins are listed
    // because header rules need host access to the initiator as well as the
    // target.
    // `*.youtube.com` covers music., www. and s. (play reports), and the
    // youtube.com cookies themselves, whose changes signal a sign-in or out.
    host_permissions: [
      'https://*.youtube.com/*',
      'https://*.googlevideo.com/*',
      'https://open.spotify.com/*',
      'https://api-partner.spotify.com/*',
      'https://clienttoken.spotify.com/*',
      'https://open.spotifycdn.com/*',
      ...matchPatternsFor(SITE_ORIGINS),
    ],
    // Granted at use time, on the extension's own pages: lyric providers the
    // page can't reach itself (grant.html), and discord.com when the user
    // connects Discord (discord.html).
    optional_host_permissions: ['https://*/*', ...LYRIC_ORIGINS.map(patternOf), ...(DISCORD_PRESENCE ? DISCORD_ORIGINS : [])],
    ...(browser === 'firefox'
      ? {
          browser_specific_settings: {
            gecko: {
              id: 'web-extension@opentune.app',
              strict_min_version: '128.0',
              data_collection_permissions: { required: ['none'] },
            },
          },
        }
      : {
          // storage.session, AbortSignal.any and runtime.getContexts.
          minimum_chrome_version: '116',
          // Chromium pages message the extension directly. No `ids`: other extensions can't.
          externally_connectable: { matches: matchPatternsFor(SITE_ORIGINS) },
          // Pins the unpacked extension's id so the page can address it
          // (scripts/gen-dev-key.mjs). The Web Store assigns its own id and
          // refuses a `key`, so store builds leave it out.
          ...(!STORE_BUILD && { key: devPublicKey }),
        }),
  }),
  // The connect page only exists in builds that include Discord.
  filterEntrypoints: DISCORD_PRESENCE ? undefined : ['background', 'popup', 'grant', 'relay'],
  hooks: {
    // Firefox only: the relay content script runs on the site's origins.
    'build:manifestGenerated': (wxt, manifest) => {
      for (const script of manifest.content_scripts ?? []) {
        if (script.js?.some((file) => file.includes('relay'))) {
          script.matches = matchPatternsFor(SITE_ORIGINS);
        }
      }
    },
  },
});


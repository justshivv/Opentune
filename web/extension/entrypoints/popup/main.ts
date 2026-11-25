import './style.css';
import { mountUpdates } from './updates';
import {
  BRIDGE_CHANNEL,
  BRIDGE_PROTOCOL,
  type BridgeMethod,
  type BridgeResponse,
  type ParamsOf,
  type ResultOf,
  type DiscordState,
  type SpotifyAuthState,
  type YtAuthState,
} from '@opentune/shared/bridge';
import { sortGrants } from '../../lib/grants';
import { LYRIC_ORIGINS, patternOf } from '../../lib/lyricHosts';
import { POPUP_CHANNEL, type PlayersRequest } from '../../lib/popupMessages';
import type { PlayerContact } from '../../lib/players';
import { isPlayerUrl, matchPatternsFor } from '../../lib/site';

declare const __SITE_ORIGINS__: string[];

const manifest = browser.runtime.getManifest();
const PLAYER_URL = manifest.homepage_url ?? 'https://justshivv.github.io/Opentune/player/';
/** Install-time host access; Firefox (and Chrome's "site access" setting) let the user withhold it. */
const REQUIRED_HOSTS: string[] = (manifest as { host_permissions?: string[] }).host_permissions ?? [];
/** discord.com is the Discord connect page's to ask for; it isn't listed with the add-ons. */
const DISCORD_PATTERN = 'https://discord.com/*';

let requestCount = 0;

/** The popup asks the background, like the page does, so both see one state. */
async function call<M extends BridgeMethod>(method: M, params: ParamsOf<M>): Promise<ResultOf<M>> {
  const response = (await browser.runtime.sendMessage({
    channel: BRIDGE_CHANNEL,
    protocol: BRIDGE_PROTOCOL,
    direction: 'to-extension',
    id: `popup-${Date.now().toString(36)}-${(requestCount++).toString(36)}`,
    method,
    params,
  })) as BridgeResponse<M> | undefined;
  if (!response) throw new Error('The extension’s background didn’t answer.');
  if (!response.ok) throw new Error(response.error.message);
  return response.result;
}

function el<K extends keyof HTMLElementTagNameMap>(tag: K, className: string, text?: string): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function who(title: string, detail: string, detailTitle?: string | null): HTMLElement {
  const box = el('span', 'who');
  const second = el('span', 'detail', detail);
  if (detailTitle) second.title = detailTitle;
  box.append(el('span', 'title', title), second);
  return box;
}

function row(lead: HTMLElement, text: HTMLElement, action?: HTMLElement | null): HTMLLIElement {
  const item = el('li', 'row');
  item.append(lead, text, action ?? el('span', ''));
  return item;
}

/**
 * A button that runs [onClick], disabled meanwhile. A failure changes its
 * label and puts the reason in its tooltip, rather than passing silently.
 */
function button(text: string, onClick: () => Promise<unknown>, options: { title?: string; quiet?: boolean } = {}): HTMLButtonElement {
  const action = el('button', options.quiet ? 'action quiet' : 'action', text);
  action.type = 'button';
  if (options.title) action.title = options.title;
  action.onclick = () => {
    action.disabled = true;
    onClick()
      .catch((error: unknown) => {
        action.title = error instanceof Error ? error.message : String(error);
        action.textContent = 'Failed, retry';
      })
      .finally(() => (action.disabled = false));
  };
  return action;
}

/** A disc with the service's initial, where there's no account picture. */
const initial = (service: string) => el('span', 'avatar initial', service.charAt(0));

/** A connected account's picture, or the service's initial. */
function avatarOf(url: string | null | undefined, service: string): HTMLElement {
  if (!url) return initial(service);
  const image = el('img', 'avatar');
  image.alt = '';
  image.referrerPolicy = 'no-referrer';
  image.src = url;
  image.onerror = () => image.replaceWith(initial(service));
  return image;
}

const checking = (name: string) => row(initial(name), who(name, 'Checking…'));

// ---- Player tab ------------------------------------------------------------------

interface PlayerTab {
  tab: Browser.tabs.Tab;
  contact: PlayerContact | null;
}

async function playerTabs(): Promise<PlayerTab[]> {
  // The manifest patterns are host-wide for localhost; the exact origin (port included) is checked here.
  const tabs = (await browser.tabs.query({ url: matchPatternsFor(__SITE_ORIGINS__) })).filter(
    (t) => t.id !== undefined && isPlayerUrl(t.url, __SITE_ORIGINS__),
  );
  if (!tabs.length) return [];
  const request: PlayersRequest = { channel: POPUP_CHANNEL, type: 'players', tabIds: tabs.map((t) => t.id!) };
  const found = ((await browser.runtime.sendMessage(request).catch(() => [])) ?? []) as PlayerContact[];
  return tabs.map((tab) => ({ tab, contact: found.find((c) => c.tabId === tab.id) ?? null }));
}

const heard = (p: PlayerTab) => !!p.contact && (p.contact.live || p.contact.lastSeen !== null);

async function showTab(tab: Browser.tabs.Tab): Promise<void> {
  await browser.tabs.update(tab.id!, { active: true });
  if (tab.windowId !== undefined) await browser.windows.update(tab.windowId, { focused: true });
  window.close();
}

async function openPlayer(): Promise<void> {
  await browser.tabs.create({ url: PLAYER_URL });
  window.close();
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

async function renderPlayer(): Promise<void> {
  const list = document.getElementById('player')!;
  const open = document.getElementById('open') as HTMLButtonElement;
  let players: PlayerTab[];
  try {
    players = await playerTabs();
  } catch {
    players = [];
  }
  const connected = players.filter(heard).sort((a, b) => (b.contact?.lastSeen ?? 0) - (a.contact?.lastSeen ?? 0));
  const best = connected[0] ?? players[0];

  if (!best) {
    list.replaceChildren(row(el('span', 'light'), who('Not open', 'Open the player to start listening.')));
    open.textContent = 'Open player';
    open.onclick = () => void openPlayer();
    return;
  }
  open.textContent = 'Show player';
  open.onclick = () => void showTab(best.tab);
  if (connected.length) {
    const where = players.length > 1 ? `Open in ${plural(players.length, 'tab', 'tabs')}` : 'Open in a tab';
    list.replaceChildren(row(el('span', 'light ok'), who('Connected', where), button('Show', () => showTab(best.tab), { quiet: true })));
    return;
  }
  // Open, but it hasn't reached this extension: from before an update, or still loading.
  list.replaceChildren(
    row(
      el('span', 'light warn'),
      who('Open, not connected', 'Reload the tab if this doesn’t change.'),
      button('Reload', async () => {
        await browser.tabs.reload(best.tab.id!);
        setTimeout(() => void renderPlayer(), 2500);
      }),
    ),
  );
}

// ---- Accounts ----------------------------------------------------------------------

/** YouTube Music: who requests act as, and the way to stop (or start) using that session. */
function youtubeRows(state: YtAuthState | null): HTMLLIElement[] {
  if (!state) return [checking('YouTube Music')];
  if (!state.browserSignedIn) {
    const link = el('a', 'action', 'Sign in');
    link.href = 'https://music.youtube.com/';
    link.target = '_blank';
    link.rel = 'noreferrer';
    return [row(avatarOf(null, 'YouTube Music'), who('YouTube Music', 'Signed out in this browser'), link)];
  }
  if (!state.connected) {
    return [
      row(
        avatarOf(null, 'YouTube Music'),
        who('YouTube Music', 'Not used by NepTune'),
        button('Connect', () => call('yt.setConnected', { connected: true }).then(setYoutube)),
      ),
    ];
  }
  if (!state.active) {
    // Signed in, but the account couldn't be read: say why, and offer another try.
    const rows = [
      row(
        avatarOf(null, 'YouTube Music'),
        who('YouTube Music', 'Couldn’t read your account', state.problem),
        button('Retry', () => call('yt.authState', { refresh: true }).then(setYoutube)),
      ),
    ];
    if (state.problem) rows.push(el('li', 'problem', state.problem));
    return rows;
  }
  const detail = state.active.handle ?? 'YouTube Music';
  return [
    row(
      avatarOf(state.active.avatarUrl, 'YouTube Music'),
      who(state.active.name, `YouTube Music · ${detail}`),
      button('Disconnect', () => call('yt.setConnected', { connected: false }).then(setYoutube), {
        quiet: true,
        title: 'Stop using this account in NepTune. You stay signed in to YouTube.',
      }),
    ),
  ];
}

/** Spotify: the browser's open.spotify.com session, as for YouTube. */
function spotifyRow(state: SpotifyAuthState | null): HTMLLIElement {
  if (!state) return checking('Spotify');
  if (!state.browserSignedIn) {
    return row(avatarOf(null, 'Spotify'), who('Spotify', 'Signed out in this browser'), button('Sign in', () => call('spotify.signIn', {})));
  }
  if (!state.connected) {
    return row(
      avatarOf(null, 'Spotify'),
      who('Spotify', 'Not used by NepTune'),
      button('Connect', () => call('spotify.setConnected', { connected: true }).then(setSpotify)),
    );
  }
  if (!state.signedIn) {
    return row(
      avatarOf(null, 'Spotify'),
      who('Spotify', 'Couldn’t read your account', state.problem),
      button('Retry', () => call('spotify.authState', { refresh: true }).then(setSpotify)),
    );
  }
  return row(
    avatarOf(state.user?.avatarUrl, 'Spotify'),
    who(state.user?.name ?? 'Spotify', 'Spotify'),
    button('Disconnect', () => call('spotify.setConnected', { connected: false }).then(setSpotify), {
      quiet: true,
      title: 'Stop using this account in NepTune. You stay signed in to Spotify.',
    }),
  );
}

/** Discord: connect on the extension's own page; presence itself is switched on in the web player. */
function discordRow(state: DiscordState | null): HTMLLIElement | null {
  if (state && !state.available) return null;
  if (!state) return checking('Discord');
  if (!state.connected || !state.user) {
    return row(
      avatarOf(null, 'Discord'),
      who('Discord', 'Not connected'),
      button('Connect', () => call('discord.connect', {}).then(() => window.close())),
    );
  }
  const detail =
    state.gateway === 'ready'
      ? 'Showing what you play'
      : state.gateway === 'connecting'
        ? 'Connecting…'
        : state.gateway === 'error'
          ? (state.problem ?? 'Couldn’t connect')
          : state.enabled
            ? 'On, waiting for a song'
            : 'Presence off';
  return row(
    avatarOf(state.user.avatarUrl, 'Discord'),
    who(state.user.name, `Discord · ${detail}`),
    button('Disconnect', () => call('discord.disconnect', {}).then(setDiscord), { quiet: true, title: 'Forget this Discord account.' }),
  );
}

const accounts = document.getElementById('integrations')!;
let youtubeState: YtAuthState | null = null;
let spotifyState: SpotifyAuthState | null = null;
let discordStateNow: DiscordState | null = null;
let youtubeFailed: string | null = null;

function renderAccounts(): void {
  const discord = discordRow(discordStateNow);
  const youtube = youtubeFailed
    ? [row(avatarOf(null, 'YouTube Music'), who('YouTube Music', 'Couldn’t read the session', youtubeFailed))]
    : youtubeRows(youtubeState);
  accounts.replaceChildren(...youtube, spotifyRow(spotifyState), ...(discord ? [discord] : []));
}

function setYoutube(state: YtAuthState): void {
  youtubeState = state;
  youtubeFailed = null;
  renderAccounts();
}
function setSpotify(state: SpotifyAuthState): void {
  spotifyState = state;
  renderAccounts();
}
function setDiscord(state: DiscordState): void {
  discordStateNow = state;
  renderAccounts();
}

// ---- Host access -----------------------------------------------------------------------

function hostRow(origin: string, onRemoved: () => void): HTMLLIElement {
  const item = el('li', 'row compact');
  item.append(
    el('span', 'host', new URL(origin).host),
    button(
      'Remove',
      async () => {
        const removed = await browser.permissions.remove({ origins: [patternOf(origin)] });
        if (!removed) throw new Error('The browser kept this permission.');
        onRemoved();
      },
      { quiet: true, title: `Stop NepTune reaching ${new URL(origin).host}. The player asks again if it needs it.` },
    ),
  );
  return item;
}

async function renderGrants(): Promise<void> {
  const lyrics = document.getElementById('lyrics')!;
  const addons = document.getElementById('addons')!;
  const granted = (await browser.permissions.getAll()).origins ?? [];
  const grants = sortGrants(granted, REQUIRED_HOSTS, LYRIC_ORIGINS, [DISCORD_PATTERN]);
  const refresh = () => void renderGrants();

  document.getElementById('lyrics-count')!.textContent = `${grants.lyrics.length} of ${LYRIC_ORIGINS.length} allowed`;
  if (grants.lyrics.length) lyrics.replaceChildren(...grants.lyrics.map((o) => hostRow(o, refresh)));
  else lyrics.replaceChildren(el('li', 'empty', 'None allowed. The player asks when it needs one.'));

  document.getElementById('addons-section')!.hidden = !grants.addons.length;
  addons.replaceChildren(...grants.addons.map((o) => hostRow(o, refresh)));
}

/** Install-time host access the user has withheld (about:addons in Firefox, "Site access" in Chrome). */
async function renderAccess(): Promise<void> {
  const box = document.getElementById('access')!;
  const answers = await Promise.all(REQUIRED_HOSTS.map((p) => browser.permissions.contains({ origins: [p] }).catch(() => true)));
  const missing = REQUIRED_HOSTS.filter((_, i) => !answers[i]);
  if (!missing.length) return box.replaceChildren();
  const notice = el('div', 'notice');
  const text = el('p', '');
  text.append(
    el('strong', '', 'NepTune can’t reach every site it needs'),
    'Without it, YouTube Music, sign-in or the player connection may not work.',
  );
  notice.append(
    text,
    button('Allow access', async () => {
      const granted = await browser.permissions.request({ origins: missing });
      if (!granted) throw new Error('Not allowed.');
      await renderAccess();
    }),
  );
  box.replaceChildren(notice);
}

// ---- Start -----------------------------------------------------------------------------

document.getElementById('version')!.textContent = `v${manifest.version}`;
const openButton = document.getElementById('open') as HTMLButtonElement;
openButton.onclick = () => void openPlayer();

renderAccounts();
void renderPlayer();
void renderAccess();
void renderGrants().catch(() => {
  document.getElementById('lyrics')!.replaceChildren(el('li', 'empty', 'Couldn’t read the permissions.'));
});
call('yt.authState', {})
  .then(setYoutube)
  .catch((error: unknown) => {
    youtubeFailed = error instanceof Error ? error.message : String(error);
    renderAccounts();
  });
call('spotify.authState', {}).then(setSpotify).catch(() => undefined);
call('discord.state', {}).then(setDiscord).catch(() => undefined);

// Permissions change from the grant page or the browser's own settings while this is open.
browser.permissions.onAdded.addListener(() => void renderGrants());
browser.permissions.onRemoved.addListener(() => void renderGrants());

mountUpdates(document.getElementById('updates')!);

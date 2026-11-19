import { createPlaylist } from '@/data/music/api';
import { CONNECT_PLAYLIST_TITLE } from '@/data/music/library';
import { getSignedInnertube } from '@/playback/youtube/innertube';
import { ConnectGroup } from './group';

/**
 * Keeps the group's secret in the person's YouTube account, as
 * ConnectAccountSync.kt does on the phone: in the description of a private,
 * empty playlist named [TITLE], so every device signed in to it joins by itself.
 */
export const TITLE = CONNECT_PLAYLIST_TITLE;
const PREFIX = 'opentune-connect:v1:';
const SECRET = /opentune-connect:v1:([A-Za-z0-9_-]{43})/;

export const description = (group: ConnectGroup) =>
  `Lets your OpenTune devices find each other. Keep it private; deleting it only stops them joining by themselves.\n${PREFIX}${group.encoded}`;

/** The secret in any text from the playlist's page, or null. */
export const secretIn = (text: string): string | null => SECRET.exec(text)?.[1] ?? null;

type Json = Record<string, unknown>;
const textOf = (v: unknown): string => {
  const runs = (v as { runs?: { text?: string }[] } | undefined)?.runs;
  return runs?.map((r) => r.text ?? '').join('') ?? '';
};
const browseIdOf = (v: unknown): string | null => {
  const id = (v as { navigationEndpoint?: { browseEndpoint?: { browseId?: string } } } | undefined)?.navigationEndpoint?.browseEndpoint?.browseId;
  return typeof id === 'string' ? id : null;
};

/** The id of the playlist named [TITLE] in a library page's JSON, or null. */
export function pick(page: unknown): string | null {
  const stack: unknown[] = [page];
  while (stack.length) {
    const node = stack.pop();
    if (!node || typeof node !== 'object') continue;
    const o = node as Json;
    if (o.title && textOf(o.title) === TITLE) {
      const runs = (o.title as { runs?: unknown[] }).runs ?? [];
      const id = browseIdOf(o) ?? runs.map(browseIdOf).find(Boolean) ?? null;
      if (id) return id;
    }
    for (const value of Object.values(o)) if (value && typeof value === 'object') stack.push(value);
  }
  return null;
}

async function find(): Promise<string | null> {
  const yt = await getSignedInnertube();
  const page = await yt.actions.execute('/browse', { browseId: 'FEmusic_liked_playlists', client: 'YTMUSIC' });
  return pick(page.data);
}

/** The group kept in the account (its secret), or null when there's none yet. */
export async function readSecret(): Promise<string | null> {
  const id = await find();
  if (!id) return null;
  const yt = await getSignedInnertube();
  const page = await yt.actions.execute('/browse', { browseId: id.startsWith('VL') ? id : `VL${id}`, client: 'YTMUSIC' });
  return secretIn(JSON.stringify(page.data));
}

/** Puts [group] in the account: the playlist made if it isn't there, its description changed if it is. */
export async function write(group: ConnectGroup): Promise<void> {
  const id = await find();
  if (!id) {
    await createPlaylist(TITLE, [], description(group));
    return;
  }
  const yt = await getSignedInnertube();
  await yt.actions.execute('/browse/edit_playlist', {
    playlistId: id.replace(/^VL/, ''),
    actions: [{ action: 'ACTION_SET_PLAYLIST_DESCRIPTION', playlistDescription: description(group) }],
    client: 'YTMUSIC',
  });
}

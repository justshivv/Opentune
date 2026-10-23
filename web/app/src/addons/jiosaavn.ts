import { desEcbDecrypt } from './des';

/** JioSaavn's catalog API, reached through the extension (it sends no CORS headers); its CDN plays directly. */
export const JIOSAAVN_ORIGIN = 'https://www.jiosaavn.com';
const KEY = new TextEncoder().encode('38346591');

/** A song to look for: the YouTube Music names, not the video's. */
export type Wanted = { title: string; artist: string; album?: string | null; duration: number };
export type SaavnStream = { url: string; kbps: number; title: string };

function api(call: string, params: Record<string, string>): string {
  const url = new URL(`${JIOSAAVN_ORIGIN}/api.php`);
  url.search = new URLSearchParams({ __call: call, _format: 'json', _marker: '0', ctx: 'android', api_version: '4', ...params }).toString();
  return url.href;
}

const ENTITIES: Record<string, string> = { '&amp;': '&', '&quot;': '"', '&#039;': "'", '&#39;': "'", '&lt;': '<', '&gt;': '>' };
export const unescape = (text: string) => text.replace(/&(?:amp|quot|#0?39|lt|gt);/g, (m) => ENTITIES[m] ?? m);
/** A name compared on its words: no accents, case, brackets ("(From "Raees")") or trailing " - Remix" parts. */
export const plain = (text: string) =>
  unescape(text).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()
    .replace(/\([^)]*\)|\[[^\]]*\]/g, ' ').replace(/\s[-–]\s.*$/, ' ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
/** The kind of version a title names; a remix or a live take is never the studio recording. */
const VERSION = /\b(remix|mix|lo-?fi|slowed|reverb|unplugged|live|acoustic|instrumental|karaoke|cover|reprise|sped up|8d)\b/gi;
const versions = (title: string) => [...new Set([...unescape(title).toLowerCase().matchAll(VERSION)].map((m) => m[1]!.replace('-', '')))].sort().join(',');
const people = (line: string) => line.split(/,|&| x | feat\.? | featuring | ft\.? | and /i).map(plain).filter(Boolean);

type Result = { id?: unknown; title?: unknown; more_info?: Record<string, unknown> };

/** The credits a Saavn song lists: singers, composers and lyricists, never the actors. */
function credits(info: Record<string, unknown>): string[] {
  const map = (info.artistMap ?? {}) as Record<string, { name?: unknown; role?: unknown }[] | undefined>;
  const roles = new Set(['music', 'composer', 'singer', 'lyricist', 'primary_artists', 'featured_artists']);
  return ['primary_artists', 'featured_artists', 'artists'].flatMap((field) =>
    (Array.isArray(map[field]) ? map[field]! : [])
      .filter((a) => field !== 'artists' || roles.has(String(a.role)))
      .map((a) => plain(String(a.name ?? ''))),
  ).filter(Boolean);
}

/**
 * The search result that is the wanted recording: the same title, length
 * within 3 s, a 320 kbps rendition, and at least one credited person in
 * common (YouTube often bills a soundtrack to its composer, Saavn to its singer).
 */
export function matchSaavn(results: Result[], wanted: Wanted): string | null {
  const title = plain(wanted.title);
  const asked = people(wanted.artist);
  for (const r of results.slice(0, 8)) {
    const info = r.more_info ?? {};
    if (String(info['320kbps']).toLowerCase() !== 'true' || typeof r.id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(r.id)) continue;
    const length = Number(info.duration);
    if (!Number.isFinite(length) || Math.abs(length - wanted.duration) > 3) continue;
    if (plain(String(r.title ?? '')) !== title || versions(String(r.title ?? '')) !== versions(wanted.title)) continue;
    const credited = credits(info);
    if (asked.some((a) => credited.includes(a))) return r.id;
  }
  return null;
}

/** The 320 kbps rendition of a decrypted media URL, on Saavn's own CDN only. */
export function rendition320(decoded: string): string | null {
  let url: URL;
  try { url = new URL(decoded.trim()); } catch { return null; }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  url.protocol = 'https:';
  if (url.hostname !== 'saavncdn.com' && !url.hostname.endsWith('.saavncdn.com')) return null;
  const pattern = /_(48|96|160|320)\.mp4$/i;
  if (!pattern.test(url.pathname)) return null;
  url.pathname = url.pathname.replace(pattern, '_320.mp4');
  return url.href;
}

export function decryptMediaUrl(encrypted: string): string {
  const bytes = Uint8Array.from(atob(encrypted), (c) => c.charCodeAt(0));
  return new TextDecoder().decode(desEcbDecrypt(bytes, KEY));
}

/** Whether the file really is AAC in MP4 at about 320 kbps, from its first bytes and its size. */
export function verified320(head: Uint8Array, totalBytes: number, durationSec: number): boolean {
  if (head.length < 12 || String.fromCharCode(...head.slice(4, 8)) !== 'ftyp' || !durationSec) return false;
  const kbps = (totalBytes * 8) / durationSec / 1000;
  return kbps >= 256 && kbps <= 380;
}

/** Reads the file's first bytes and its full size, then lets go of the rest. */
async function probe(url: string): Promise<{ head: Uint8Array; total: number }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(url, { credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal });
    if (!response.ok || !response.body) throw new Error(`its file server returned HTTP ${response.status}`);
    const total = Number(response.headers.get('content-length'));
    const reader = response.body.getReader();
    const first = await reader.read();
    await reader.cancel().catch(() => {});
    return { head: first.value ?? new Uint8Array(0), total };
  } finally {
    clearTimeout(timer);
    controller.abort();
  }
}

/**
 * JioSaavn's 320 kbps AAC stream for [wanted], checked to be that file, or
 * null with the reason. Album first, as Android asks: an artist search can
 * fill the first page with compilations.
 */
export async function findJioSaavn(
  wanted: Wanted,
  /** The add-on client's JSON fetch: directly when allowed, otherwise through the extension. */
  fetchJson: (url: string) => Promise<unknown>,
): Promise<{ stream: SaavnStream | null; note: string }> {
  try {
    const queries = [...new Set([wanted.album ? `${wanted.title} ${wanted.album}` : null, `${wanted.title} ${wanted.artist}`].filter((q): q is string => !!q))];
    let id: string | null = null;
    for (const q of queries) {
      const found = (await fetchJson(api('search.getResults', { q, n: '8', p: '1' }))) as { results?: Result[] };
      id = matchSaavn(found.results ?? [], wanted);
      if (id) break;
    }
    if (!id) return { stream: null, note: 'JioSaavn: no matching 320 kbps recording' };
    const details = (await fetchJson(api('song.getDetails', { pids: id }))) as Record<string, Result | undefined> & { songs?: Result[] };
    const song = details[id] ?? details.songs?.[0];
    if (!song || song.id !== id) return { stream: null, note: 'JioSaavn: track details unavailable' };
    const encrypted = song.more_info?.encrypted_media_url;
    const url = typeof encrypted === 'string' ? rendition320(decryptMediaUrl(encrypted)) : null;
    if (!url) return { stream: null, note: 'JioSaavn: 320 kbps stream unavailable' };
    const { head, total } = await probe(url);
    if (!verified320(head, total, wanted.duration)) return { stream: null, note: 'JioSaavn: stream failed the audio/bitrate check' };
    return { stream: { url, kbps: 320, title: unescape(String(song.title ?? wanted.title)) }, note: 'JioSaavn: verified 320 kbps stream' };
  } catch (error) {
    const why = error instanceof Error ? error.message : String(error);
    return { stream: null, note: /permission|allow/i.test(why) ? 'JioSaavn: allow www.jiosaavn.com through the extension in Audio sources' : `JioSaavn: ${why}` };
  }
}

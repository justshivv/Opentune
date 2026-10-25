import { create } from 'zustand';
import { currentConfig } from '@/config/remote';
import { endpoint, flacInfo, manifestOf, matches, publicHttps, qualityParams, type Addon, type CatalogTrack, type Recording } from '@opentune/shared/addons';
import { BridgeCallError } from '@opentune/shared/bridge';
import { getBridge } from '@/extension/bridge';
import { fetchPublished, mergeCatalog } from './catalog';
import { needsVerification, verificationHeaders } from './verification';
const KEY = 'opentune:audio-addons';
const SAAVN_KEY = 'opentune:jiosaavn';
/** Twenty of the listener's own and twenty published ones at most. */
function read(): Addon[] { try { const v = JSON.parse(localStorage.getItem(KEY) ?? '[]'); return Array.isArray(v) ? v.filter(x => typeof x?.url === 'string' && typeof x?.name === 'string').slice(0, 40) : []; } catch { return []; } }
function readSaavn(): boolean { try { return localStorage.getItem(SAAVN_KEY) !== 'false'; } catch { return true; } }
export const useAddons = create<{
  sources: Addon[]; update: (sources: Addon[]) => void; status: string; checkingVideoId: string | null;
  /** JioSaavn's 320 kbps AAC, played while (or when no) FLAC is found. On by default, as on Android. */
  jioSaavn: boolean; setJioSaavn: (on: boolean) => void;
  /** Sources whose host the extension hasn't been allowed to reach yet, by id. */
  locked: string[];
}>(set => ({
  sources: read(), status: '', checkingVideoId: null, locked: [],
  update: sources => { try { localStorage.setItem(KEY, JSON.stringify(sources)); } catch { /* Kept for this session. */ } set({ sources }); },
  jioSaavn: readSaavn(),
  setJioSaavn: jioSaavn => { try { localStorage.setItem(SAAVN_KEY, String(jioSaavn)); } catch { /* Kept for this session. */ } set({ jioSaavn }); },
}));

/**
 * Folds the sources published from the owner dashboard into the list ("Added by
 * admin"), keeping the listener's order and on/off for them. Quiet on failure:
 * the list as it was keeps working.
 */
export async function syncPublished(force = false): Promise<boolean> {
  try {
    const published = await fetchPublished(force);
    if (!published) return true;
    const { sources, update } = useAddons.getState();
    const merged = mergeCatalog(sources, published);
    if (JSON.stringify(merged) !== JSON.stringify(sources)) update(merged);
    return true;
  } catch { return false; }
}
const cache = new Map<string, { until: number; value: unknown }>();
const pending = new Map<string, Promise<unknown>>();
/** Servers that said 429, until when. Held for one song only, as on Android: the next song asks again. */
const cooldown = new Map<string, number>();
let cooldownSong = '';
/**
 * Servers a page can't read (no CORS), until when: asked through the extension
 * meanwhile, then directly again, so one bad answer doesn't stick for the session.
 */
const corsBlocked = new Map<string, number>();
const CORS_RETRY_MS = 10 * 60_000;
/** Servers that have answered this page directly: a later failure there is the network or the server, never CORS. */
const corsOk = new Set<string>();
/** The extension's refusal for a host it hasn't been allowed to reach. */
const isPermission = (error: unknown) => error instanceof BridgeCallError && error.code === 'permission';
export async function fetchJson(url: string): Promise<unknown> {
  const origin = publicHttps(url).origin;
  if ((cooldown.get(origin) ?? 0) > Date.now()) throw new Error('is rate limiting requests; it is asked again on the next song');
  const old = cache.get(url); if (old && old.until > Date.now()) return old.value;
  const running = pending.get(url); if (running) return running;
  const job = (async () => {
    let status: number, text: string, retryAfter = '';
    try {
      if ((corsBlocked.get(origin) ?? 0) > Date.now()) throw new TypeError('Blocked by CORS');
      const headers = await verificationHeaders(url);
      const r = await fetch(url, { headers, credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(currentConfig().data.network.timeoutMs) });
      status = r.status;
      retryAfter = r.headers.get('retry-after') ?? '';
      const reader = r.body?.getReader(); if (!reader) throw new Error('Empty response');
      const decoder = new TextDecoder(); text = ''; let size = 0;
      try { while (true) { const item = await reader.read(); if (item.done) break; size += item.value.length; if (size > 2097152) throw new Error('Response too large'); text += decoder.decode(item.value, { stream: true }); } text += decoder.decode(); }
      finally { await reader.cancel().catch(() => {}); }
      corsOk.add(origin);
    } catch (error) {
      // Proofs and enrollment errors must never be forwarded to extension/third-party requests.
      if (needsVerification(url)) throw error;
      // Only a server that refuses pages (no CORS) is a reason to go through the extension.
      // A timeout is a slow server, and a failure from one that has answered here before is
      // the network or an error page: those are said as they are, not as a missing permission.
      if (!(error instanceof TypeError)) throw error instanceof DOMException && error.name === 'TimeoutError' ? new Error('timed out (the server is slow or waking up)') : error;
      if (corsOk.has(origin)) throw new Error("couldn't be reached (network error)");
      const bridge = getBridge(); if (!bridge) throw new Error("doesn't let browsers ask it directly; the OpenTune extension can");
      corsBlocked.set(origin, Date.now() + CORS_RETRY_MS);
      const r = await bridge.call('addon.fetch', { url }); status = r.status; text = r.body; retryAfter = r.headers['retry-after'] ?? '';
    }
    if (status === 429) {
      // As long as the server asks (Retry-After), within reason; 30 s when it doesn't say.
      const wait = Number(retryAfter);
      cooldown.set(origin, Date.now() + (Number.isFinite(wait) && wait > 0 ? Math.min(120, Math.max(5, wait)) * 1000 : 30_000));
      throw new Error('is rate limiting requests; it is asked again on the next song');
    }
    if (status < 200 || status >= 300) throw new HttpError(status, reasonIn(text));
    const value: unknown = JSON.parse(text);
    if (cache.size > 100) cache.clear();
    cache.set(url, { until: Date.now() + (new URL(url).pathname.includes('/stream/') ? 300000 : 600000), value });
    return value;
  })().finally(() => pending.delete(url));
  pending.set(url, job); return job;
}
/** A source's own explanation, when it sent one ({"error": "..."}), kept short. */
function reasonIn(text: string): string {
  try {
    const error = (JSON.parse(text) as { error?: unknown }).error;
    return typeof error === 'string' ? error.slice(0, 140) : '';
  } catch {
    return '';
  }
}
export class HttpError extends Error {
  constructor(readonly status: number, readonly reason: string) {
    super(`returned HTTP ${status}${reason ? `: ${reason}` : ''}`);
  }
}
export async function addSource(raw: string): Promise<void> {
  const url = endpoint(raw, 'manifest.json');
  const manifest = manifestOf(await fetchJson(url));
  const { sources, update } = useAddons.getState();
  if (sources.filter(s => !s.admin).length >= 20) throw new Error('Up to 20 sources can be added');
  if (sources.some(x => x.url === url)) throw new Error('This source is already added');
  update([...sources, { id: crypto.randomUUID(), name: manifest.name, url, enabled: true }]);
}
export async function allowSource(raw: string): Promise<void> {
  const bridge = getBridge(); if (!bridge) throw new Error('Install the OpenTune extension first');
  await bridge.call('addon.requestPermission', { url: publicHttps(raw).href });
}
/** Asks for every one of these sources' hosts on one extension page (an older extension asks for the first). */
export async function allowSources(sources: Addon[]): Promise<void> {
  const bridge = getBridge(); if (!bridge) throw new Error('Install the OpenTune extension first');
  const [first, ...rest] = sources.map(s => publicHttps(s.url).href);
  if (first) await bridge.call('addon.requestPermission', { url: first, urls: rest });
}
function setLocked(id: string, locked: boolean) {
  const now = useAddons.getState().locked;
  if (locked !== now.includes(id)) useAddons.setState({ locked: locked ? [...now, id] : now.filter(x => x !== id) });
}
/**
 * Reads each enabled source's manifest to learn which hosts still need the
 * extension's permission, so Audio sources can offer to allow them before a
 * song needs them. Cached manifests make this cheap.
 */
export async function checkAccess(): Promise<void> {
  await Promise.all(useAddons.getState().sources.filter(s => s.enabled && !s.ownerOff).map(async source => {
    try { await fetchJson(source.url); setLocked(source.id, false); }
    catch (error) { if (isPermission(error)) setLocked(source.id, true); }
  }));
}
/** [cors]: whether the file's server lets a page read it; [stated]: the format is the source's word, as the browser couldn't read the header. */
export type LosslessResult = { url: string; provider: string; bits: number; rate: number; duration: number; sourceId: string; cors: boolean; stated: boolean };
/** Every verified file, best first, and why each other source had nothing. */
export type LosslessSearch = { ranked: LosslessResult[]; notes: string[]; transient: boolean };
/** Errors worth asking again about a little later: the server erred or was busy, it didn't say no. */
const TRANSIENT = /HTTP (5\d\d|408)|rate limit|timed? ?out|abort|network|failed to fetch/i;
class NoMatch extends Error {}
/** Any step's refusal by the extension: the source is marked so Audio sources offers to allow it. */
async function checkSource(source: Addon, recording: Recording): Promise<LosslessResult> {
  try {
    return await checkSourceSteps(source, recording);
  } catch (error) {
    if (!isPermission(error)) throw error;
    setLocked(source.id, true);
    throw new NoMatch('needs your OK: open Settings › Audio sources and press Allow');
  }
}
async function checkSourceSteps(source: Addon, recording: Recording): Promise<LosslessResult> {
  let manifest;
  try { manifest = manifestOf(await fetchJson(source.url)); setLocked(source.id, false); }
  catch (error) {
    if (isPermission(error)) throw error;
    // Its manifest is the first thing asked for: failing here means the server is down, not the song missing.
    if (error instanceof HttpError && error.status !== 429) throw new Error(`is offline right now (HTTP ${error.status})`);
    throw error;
  }
  // Web cannot verify the physical output route. Respect this gate conservatively.
  if (manifest.checkValidLossless) throw new NoMatch("plays in the Android app only: it needs a verified wired or USB output, which a browser can't check");
  const params = qualityParams(manifest);
  const response = await fetchJson(endpoint(source.url, 'search', { ...params, q: `${recording.title} ${recording.artist}` })) as { tracks?: CatalogTrack[] };
  const match = response.tracks?.find(x => matches(recording, x)); if (!match) throw new NoMatch('no matching recording');
  let stream: { url?: string; encrypted?: boolean; manifest?: string; codec?: string; format?: string; sampleRate?: number; bitDepth?: number };
  try {
    stream = await fetchJson(endpoint(source.url, `stream/${encodeURIComponent(match.id)}`, params)) as typeof stream;
  } catch (error) {
    // A 404 for the stream is the source saying it hasn't got this one.
    if (error instanceof HttpError && error.status === 404) throw new NoMatch(error.reason || 'no stream for this song');
    throw error;
  }
  if (!stream.url) throw new NoMatch('no stream for the match');
  if (stream.encrypted || (stream.manifest && stream.manifest !== 'none')) throw new NoMatch('only a segmented or encrypted stream, which plays on Android');
  const url = publicHttps(stream.url).href;
  let r: Response;
  try {
    r = await fetch(url, { headers: { Range: 'bytes=0-4095' }, credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
  } catch (error) {
    // The file's server doesn't let a page read it (no CORS), so its header can't be measured here.
    // A source that states a FLAC's format plainly is taken at its word; the player still has to
    // decode it, and gives way to the next source if it can't.
    const bits = Number(stream.bitDepth), rate = Number(stream.sampleRate);
    const flac = /flac/i.test(`${stream.codec ?? ''} ${stream.format ?? ''}`);
    if (error instanceof TypeError && flac && bits >= 16 && rate >= 44100) {
      return { url, provider: manifest.name, bits, rate, duration: match.duration, sourceId: source.id, cors: false, stated: true };
    }
    throw error instanceof TypeError ? new Error("its file server doesn't let browsers read the file") : error;
  }
  if (!r.ok || !r.body) throw new Error(`its file server returned HTTP ${r.status}`);
  const reader = r.body.getReader(); let bytes = new Uint8Array(0);
  try { while (bytes.length < 42) { const part = await reader.read(); if (part.done) break; const merged = new Uint8Array(bytes.length + Math.min(part.value.length, 4096)); merged.set(bytes); merged.set(part.value.slice(0, 4096), bytes.length); bytes = merged; } }
  finally { await reader.cancel().catch(() => {}); }
  let info: ReturnType<typeof flacInfo>;
  try { info = flacInfo(bytes); } catch { throw new NoMatch("its file isn't FLAC"); }
  if (Math.abs(info.duration - recording.duration) > 3) throw new NoMatch('its file is a different length');
  return { url, provider: manifest.name, ...info, sourceId: source.id, cors: true, stated: false };
}
/**
 * Asks every enabled source at once and ranks the verified FLAC files by measured
 * quality; equal quality keeps the listener's order. Each source has its own time
 * limits, so a slow one never cuts off another's answer.
 */
export async function findLossless(recording: Recording): Promise<LosslessSearch> {
  // A new song: rate limits from the last one are forgotten, and every source is asked again.
  const song = `${recording.title}\n${recording.artist}\n${recording.duration}`;
  if (song !== cooldownSong) { cooldownSong = song; cooldown.clear(); }
  const sources = useAddons.getState().sources.filter(s => s.enabled && !s.ownerOff);
  useAddons.setState({ status: `Checking ${sources.map(s => s.name).join(', ')}…` });
  const results = await Promise.allSettled(sources.map(source => checkSource(source, recording)));
  const ranked: LosslessResult[] = []; const notes: string[] = []; let transient = false;
  results.forEach((result, i) => {
    if (result.status === 'fulfilled') { ranked.push(result.value); return; }
    const why = result.reason instanceof Error ? result.reason.message : String(result.reason);
    // A server that's down isn't worth asking again within the song; one that's busy is.
    if (!(result.reason instanceof NoMatch) && TRANSIENT.test(why) && !why.startsWith('is offline')) transient = true;
    notes.push(`${sources[i]!.name}: ${why}`);
  });
  // Stable sort: equal quality keeps the order the sources were listed in.
  ranked.sort((a, b) => b.bits * b.rate - a.bits * a.rate);
  const best = ranked[0];
  const found = best ? `${best.provider} · ${best.stated ? 'FLAC, as the source states it' : 'verified FLAC'}` : 'No matching browser-compatible FLAC found';
  useAddons.setState({ status: [found, ...notes].join('\n') });
  return { ranked, notes, transient };
}
/** Adds why the verified files didn't play under what was found. */
export function reportLoadFailures(failures: string[]): void {
  if (!failures.length) return;
  const found = useAddons.getState().status.split("\nCouldn't switch")[0];
  useAddons.setState({ status: `${found}\nCouldn't switch to it: its stream didn't load.\n${failures.join('\n')}\nYouTube keeps playing.` });
}

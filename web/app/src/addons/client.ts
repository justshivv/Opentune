import { create } from 'zustand';
import { endpoint, flacInfo, manifestOf, matches, publicHttps, qualityParams, type Addon, type CatalogTrack, type Recording } from '@opentune/shared/addons';
import { getBridge } from '@/extension/bridge';
const KEY = 'opentune:audio-addons';
function read(): Addon[] { try { const v = JSON.parse(localStorage.getItem(KEY) ?? '[]'); return Array.isArray(v) ? v.filter(x => typeof x?.url === 'string' && typeof x?.name === 'string').slice(0, 20) : []; } catch { return []; } }
export const useAddons = create<{ sources: Addon[]; update: (sources: Addon[]) => void; status: string }>(set => ({
  sources: read(), status: '', update: sources => { localStorage.setItem(KEY, JSON.stringify(sources)); set({ sources }); },
}));
const cache = new Map<string, { until: number; value: unknown }>();
const pending = new Map<string, Promise<unknown>>();
const cooldown = new Map<string, number>();
async function fetchJson(url: string): Promise<unknown> {
  const origin = publicHttps(url).origin;
  if ((cooldown.get(origin) ?? 0) > Date.now()) throw new Error('Source rate limited; try later');
  const old = cache.get(url); if (old && old.until > Date.now()) return old.value;
  const running = pending.get(url); if (running) return running;
  const job = (async () => {
    let status: number, text: string;
    try {
      const r = await fetch(url, { credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
      status = r.status;
      const reader = r.body?.getReader(); if (!reader) throw new Error('Empty response');
      const decoder = new TextDecoder(); text = ''; let size = 0;
      try { while (true) { const item = await reader.read(); if (item.done) break; size += item.value.length; if (size > 2097152) throw new Error('Response too large'); text += decoder.decode(item.value, { stream: true }); } text += decoder.decode(); }
      finally { await reader.cancel().catch(() => {}); }
    } catch {
      const bridge = getBridge(); if (!bridge) throw new Error('Source needs CORS support or extension host permission');
      const r = await bridge.call('addon.fetch', { url }); status = r.status; text = r.body;
    }
    if (status === 429) cooldown.set(origin, Date.now() + 60000);
    if (status < 200 || status >= 300) throw new Error(`Source returned HTTP ${status}`);
    const value: unknown = JSON.parse(text);
    if (cache.size > 100) cache.clear();
    cache.set(url, { until: Date.now() + (new URL(url).pathname.includes('/stream/') ? 300000 : 600000), value });
    return value;
  })().finally(() => pending.delete(url));
  pending.set(url, job); return job;
}
export async function addSource(raw: string): Promise<void> {
  const url = endpoint(raw, 'manifest.json');
  const manifest = manifestOf(await fetchJson(url));
  const { sources, update } = useAddons.getState();
  if (sources.length >= 20) throw new Error('Up to 20 sources can be added');
  if (sources.some(x => x.url === url)) throw new Error('This source is already added');
  update([...sources, { id: crypto.randomUUID(), name: manifest.name, url, enabled: true }]);
}
export async function allowSource(raw: string): Promise<void> {
  const bridge = getBridge(); if (!bridge) throw new Error('Install the OpenTune extension first');
  await bridge.call('addon.requestPermission', { url: publicHttps(raw).href });
}
export type LosslessResult = { url: string; provider: string; bits: number; rate: number; duration: number; sourceId: string };
/** Every verified file, best first, and why each other source had nothing. */
export type LosslessSearch = { ranked: LosslessResult[]; notes: string[]; transient: boolean };
/** Errors worth asking again about a little later: the server erred or was busy, it didn't say no. */
const TRANSIENT = /HTTP (5\d\d|404|408|429)|rate limited|timed? ?out|abort|network|failed to fetch/i;
class NoMatch extends Error {}
async function checkSource(source: Addon, recording: Recording): Promise<LosslessResult> {
  const manifest = manifestOf(await fetchJson(source.url));
  // Web cannot verify the physical output route. Respect this gate conservatively.
  if (manifest.checkValidLossless) throw new NoMatch("needs a verified lossless output, which a browser can't confirm");
  const params = qualityParams(manifest);
  const response = await fetchJson(endpoint(source.url, 'search', { ...params, q: `${recording.title} ${recording.artist}` })) as { tracks?: CatalogTrack[] };
  const match = response.tracks?.find(x => matches(recording, x)); if (!match) throw new NoMatch('no matching recording');
  const stream = await fetchJson(endpoint(source.url, `stream/${encodeURIComponent(match.id)}`, params)) as { url?: string; encrypted?: boolean; manifest?: string };
  if (!stream.url) throw new NoMatch('no stream for the match');
  if (stream.encrypted || (stream.manifest && stream.manifest !== 'none')) throw new NoMatch('only a segmented or encrypted stream, which plays on Android');
  const url = publicHttps(stream.url).href;
  const r = await fetch(url, { headers: { Range: 'bytes=0-4095' }, credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
  if (!r.ok || !r.body) throw new Error(`its file server returned HTTP ${r.status}`);
  const reader = r.body.getReader(); let bytes = new Uint8Array(0);
  try { while (bytes.length < 42) { const part = await reader.read(); if (part.done) break; const merged = new Uint8Array(bytes.length + Math.min(part.value.length, 4096)); merged.set(bytes); merged.set(part.value.slice(0, 4096), bytes.length); bytes = merged; } }
  finally { await reader.cancel().catch(() => {}); }
  let info: ReturnType<typeof flacInfo>;
  try { info = flacInfo(bytes); } catch { throw new NoMatch("its file isn't FLAC"); }
  if (Math.abs(info.duration - recording.duration) > 3) throw new NoMatch('its file is a different length');
  return { url, provider: manifest.name, ...info, sourceId: source.id };
}
/**
 * Asks every enabled source at once and ranks the verified FLAC files by measured
 * quality; equal quality keeps the listener's order. Each source has its own time
 * limits, so a slow one never cuts off another's answer.
 */
export async function findLossless(recording: Recording): Promise<LosslessSearch> {
  const sources = useAddons.getState().sources.filter(s => s.enabled);
  useAddons.setState({ status: `Checking ${sources.map(s => s.name).join(', ')}…` });
  const results = await Promise.allSettled(sources.map(source => checkSource(source, recording)));
  const ranked: LosslessResult[] = []; const notes: string[] = []; let transient = false;
  results.forEach((result, i) => {
    if (result.status === 'fulfilled') { ranked.push(result.value); return; }
    const why = result.reason instanceof Error ? result.reason.message : String(result.reason);
    if (!(result.reason instanceof NoMatch) && TRANSIENT.test(why)) transient = true;
    notes.push(`${sources[i]!.name}: ${why}`);
  });
  // Stable sort: equal quality keeps the order the sources were listed in.
  ranked.sort((a, b) => b.bits * b.rate - a.bits * a.rate);
  const best = ranked[0];
  useAddons.setState({ status: [best ? `${best.provider} · verified FLAC` : 'No matching browser-compatible FLAC found', ...notes].join('\n') });
  return { ranked, notes, transient };
}
/** Adds why the verified files didn't play under what was found. */
export function reportLoadFailures(failures: string[]): void {
  if (!failures.length) return;
  const found = useAddons.getState().status.split("\nCouldn't switch")[0];
  useAddons.setState({ status: `${found}\nCouldn't switch to it: its stream didn't load.\n${failures.join('\n')}\nYouTube keeps playing.` });
}

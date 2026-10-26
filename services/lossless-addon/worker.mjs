import { withMetadataCache } from './cache.mjs';
import { withAbuseProtection } from './security.mjs';
import { withAppVerification } from './auth.mjs';
export { AddonAuthState } from './auth.mjs';

/** OpenTune lossless HTTP adapters. No media transcoding or audio proxying. */
const MONO = 'https://tracks.monochrome.st';
const ARCHIVE = 'https://archive.org';
const NETEASE = 'https://music.163.com';
const METING = 'https://api.qijieya.cn/meting/';
const MUSOPEN_ITEM = 'musopen-dvd-in-lossless-flac-format';
const UA = 'OpenTune-Lossless-Addon/0.1 (+https://github.com/justshivv/Opentune)';
const MAX_JSON = 2 * 1024 * 1024;
const MAX_HEADER = 65536;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Accept', 'Access-Control-Expose-Headers': 'Retry-After' };
const json = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), {
  status, headers: { ...cors, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers },
});
class SourceError extends Error {
  constructor(message, status = 502, retryAfter = null) { super(message); this.status = status; this.retryAfter = retryAfter; }
}
const sources = {
  netease: { id: 'org.opentune.netease', name: 'OpenTune · NetEase',
    description: 'NetEase catalogue with public Meting audio resolution. Native FLAC is checked before playback; availability and original resolution vary by recording.' },
  monochrome: { id: 'org.opentune.monochrome', name: 'OpenTune · Monochrome',
    description: 'Independent adapter for Monochrome’s public Track Streamer. Availability and quotas depend on that service.' },
  archive: { id: 'org.opentune.archive', name: 'OpenTune · Internet Archive',
    description: 'FLAC recordings from Internet Archive. Catalogue includes live recordings and independent releases; it is not a substitute for a studio catalogue.' },
  musopen: { id: 'org.opentune.musopen', name: 'OpenTune · Musopen Classical',
    description: 'Musopen Kickstarter collection on Internet Archive. Original classical FLAC recordings; collection credits are preserved when performer metadata is absent. Not a popular-music catalogue or a guaranteed hi-res tier.' },
};
function manifest(source) {
  return { ...sources[source], version: '0.1.0', resources: ['search', 'stream'], allowDownloads: true,
    checkValidLossless: false, settings: [{ key: 'quality', default: 'best', options: [
      { label: 'Best available FLAC · original file', value: 'best' },
    ] }] };
}
function opaque(value) {
  return btoa(String.fromCharCode(...encoder.encode(JSON.stringify(value)))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function unpack(value) {
  if (!/^[\w-]{1,1000}$/.test(value)) throw new SourceError('Invalid recording ID', 400);
  try { return JSON.parse(decoder.decode(Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0)))); }
  catch { throw new SourceError('Invalid recording ID', 400); }
}
function text(value) { return typeof value === 'string' ? value.trim() : ''; }
function names(value) { return (Array.isArray(value) ? value : [value]).map(v => typeof v === 'string' ? v : v?.name || v?.displayName || '').filter(Boolean).join(', '); }
function safeFile(value) {
  return typeof value === 'string' && value.length <= 500 && !value.includes('\\') && !/[\x00-\x1f]/.test(value) &&
    value.split('/').every(p => p && p !== '.' && p !== '..');
}
function archiveMedia(item, file) {
  return `${ARCHIVE}/download/${encodeURIComponent(item)}/${file.split('/').map(encodeURIComponent).join('/')}`;
}
function neteaseMediaUrl(value, base) {
  let url;
  try { url = new URL(value, base); } catch { throw new SourceError('Invalid NetEase audio link'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.port ||
      !(url.hostname === 'music.126.net' || url.hostname.endsWith('.music.126.net'))) {
    throw new SourceError('The source returned an unsupported audio host');
  }
  return url.toString();
}
function seconds(value) {
  if (typeof value === 'number') return value;
  const parts = String(value || '').split(':');
  return parts.every(p => /^\d+(\.\d+)?$/.test(p)) ? parts.reduce((n, p) => n * 60 + Number(p), 0) : 0;
}
/** Native FLAC STREAMINFO; never infer precision from file size or a requested tier. */
export function flacInfo(bytes) {
  if (bytes.length < 42 || decoder.decode(bytes.subarray(0, 4)) !== 'fLaC' ||
      (bytes[4] & 127) !== 0 || bytes[5] !== 0 || bytes[6] !== 0 || bytes[7] !== 34) {
    throw new SourceError('The source did not return a native FLAC file');
  }
  const b = bytes.subarray(8, 42);
  const sampleRate = b[10] * 4096 + b[11] * 16 + (b[12] >> 4);
  const channels = ((b[12] >> 1) & 7) + 1;
  const bitDepth = ((b[12] & 1) << 4 | b[13] >> 4) + 1;
  const samples = (b[13] & 15) * 2 ** 32 + b[14] * 2 ** 24 + b[15] * 2 ** 16 + b[16] * 256 + b[17];
  if (sampleRate < 8000 || sampleRate > 768000 || bitDepth < 8 || bitDepth > 32 || samples <= 0) {
    throw new SourceError('The source returned invalid or incomplete FLAC metadata');
  }
  return { sampleRate, bitDepth, channels, duration: samples / sampleRate };
}
async function readBounded(response, limit, prefix = false) {
  const reader = response.body?.getReader();
  if (!reader) throw new SourceError('The source returned an empty response');
  const chunks = []; let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!prefix && size + value.length > limit) throw new SourceError('The source response exceeded the size limit');
      const chunk = value.subarray(0, limit - size); chunks.push(chunk); size += chunk.length;
      if (prefix && size >= limit) break;
    }
  } finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return bytes;
}
export function createAddon({ fetcher = fetch, now = Date.now } = {}) {
  const cache = new Map(), pending = new Map(), cooldown = new Map();
  function memo(key, ttl, fn) {
    const hit = cache.get(key);
    if (hit && hit.until > now()) return Promise.resolve(hit.value);
    if (pending.has(key)) return pending.get(key);
    const task = fn().then(value => {
      if (cache.size >= 100) cache.delete(cache.keys().next().value);
      cache.set(key, { until: now() + ttl, value }); return value;
    }).finally(() => pending.delete(key));
    pending.set(key, task); return task;
  }
  async function request(url, { media = false, signal, neteaseLink = false, neteaseMedia = false } = {}) {
    const host = new URL(url).host;
    const wait = Math.ceil(((cooldown.get(host) || 0) - now()) / 1000);
    if (wait > 0) throw new SourceError('Source rate limit: please retry later', 429, wait);
    const deadline = AbortSignal.timeout(6000);
    try {
      const options = { redirect: neteaseLink || neteaseMedia ? 'manual' : 'follow', signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
        headers: { 'User-Agent': UA, Accept: media ? 'audio/flac, application/octet-stream' : 'application/json',
          ...(media ? { Range: `bytes=0-${MAX_HEADER - 1}` } : {}) } };
      let address = neteaseMedia ? neteaseMediaUrl(url) : url;
      let r = await fetcher(address, options);
      // Only this source follows a discovered media location. Validate every
      // redirect before sending a request, and never send app proofs upstream.
      if (neteaseMedia) for (let hop = 0; r.status >= 300 && r.status < 400 && hop < 3; hop++) {
        const location = r.headers.get('Location'); await r.body?.cancel();
        if (!location) throw new SourceError('The source returned an empty audio redirect');
        address = neteaseMediaUrl(location, address); r = await fetcher(address, options);
      }
      if (r.status === 429) {
        const raw = r.headers.get('Retry-After');
        const numeric = raw && /^\d+$/.test(raw) ? Number(raw) : (Date.parse(raw || '') - now()) / 1000;
        const wait = Math.max(1, Math.min(3600, Math.ceil(Number.isFinite(numeric) ? numeric : 60)));
        cooldown.set(host, now() + wait * 1000); await r.body?.cancel();
        throw new SourceError('Source rate limit: please retry later', 429, wait);
      }
      if (neteaseLink && [301, 302, 303, 307, 308].includes(r.status)) {
        const location = r.headers.get('Location'); await r.body?.cancel();
        if (!location) throw new SourceError('The source returned an empty audio link');
        return neteaseMediaUrl(location, url);
      }
      if (![200, 206].includes(r.status)) {
        await r.body?.cancel();
        const message = r.status === 401 || r.status === 403 ? 'Source refused access' : 'Source request failed';
        throw new SourceError(`${message} (HTTP ${r.status})`, r.status === 404 ? 404 : 502);
      }
      if (neteaseLink) { await r.body?.cancel(); throw new SourceError('Meting did not return an available audio link', 404); }
      if (media && r.status === 206 && !/^bytes 0-\d+\/\d+$/.test(r.headers.get('Content-Range') || '')) {
        await r.body?.cancel(); throw new SourceError('The source returned an invalid byte range');
      }
      const bytes = await readBounded(r, media ? MAX_HEADER : MAX_JSON, media);
      if (media) return flacInfo(bytes);
      try { return JSON.parse(decoder.decode(bytes)); } catch { throw new SourceError('The source returned invalid JSON'); }
    } catch (e) {
      if (e instanceof SourceError) throw e;
      throw new SourceError(signal?.aborted || deadline.aborted ? 'Source request timed out' : 'Source network request failed', 503);
    }
  }
  async function searchMono(q) {
    const data = await request(`${MONO}/search/tracks?q=${encodeURIComponent(q)}&limit=20`);
    if (!Array.isArray(data.tracks)) throw new SourceError('Monochrome returned an invalid search response');
    return data.tracks.filter(t => t && t.playable !== false).flatMap(t => {
      const rawId = String(t.trackId || t.id || '');
      const artist = names(t.artistNames?.length ? t.artistNames : t.artists);
      const duration = Number(t.duration);
      if (!/^[\w-]{1,128}$/.test(rawId) || !text(t.title) || !artist || !(duration > 0)) return [];
      return [{ id: opaque(['monochrome', rawId]), title: t.title, artist,
        album: text(t.albumTitle || t.releaseTitle || t.release?.title),
        duration: duration > 1000 ? duration / 1000 : duration,
        artworkURL: text(t.artwork || t.cover), isrc: text(t.isrc), format: 'flac' }];
    }).slice(0, 20);
  }
  async function searchNetease(q) {
    const params = new URLSearchParams({ s: q, type: '1', limit: '20' });
    const data = await request(`${NETEASE}/api/cloudsearch/pc?${params}`);
    if (data.code !== 200 || (!Array.isArray(data.result?.songs) && data.result?.songCount !== 0)) {
      throw new SourceError('NetEase returned an unavailable catalogue');
    }
    return (data.result.songs || []).slice(0, 20).flatMap(t => {
      if (!t) return [];
      const id = String(t.id || ''), title = text(t.name), artist = names(t.ar || t.artists);
      const duration = Number(t.dt || t.duration) / 1000;
      if (!/^[1-9][0-9]{0,15}$/.test(id) || !title || !artist || !(duration > 0 && duration <= 86400)) return [];
      return [{ id: opaque(['netease', id]), title, artist, duration,
        album: text(t.al?.name || t.album?.name), artworkURL: text(t.al?.picUrl || t.album?.picUrl).replace(/^http:\/\//, 'https://'),
        isrc: text(t.isrc), sourcePage: `${NETEASE}/song?id=${id}` }];
    });
  }
  async function archiveMetadata(id, signal) {
    return memo(`item:${id}`, 120000, () => request(`${ARCHIVE}/metadata/${encodeURIComponent(id)}`, { signal }));
  }
  async function searchMusopen(q) {
    const data = await archiveMetadata(MUSOPEN_ITEM);
    if (!Array.isArray(data.files)) throw new SourceError('Musopen archive returned an invalid catalogue');
    const normalize = value => String(value).normalize('NFKC').toLowerCase();
    const terms = normalize(q).match(/[\p{L}\p{N}]+/gu) || [];
    if (!terms.length) return [];
    return data.files.flatMap(f => {
      if (!/^flac$/i.test(f.format || '') || !safeFile(f.name)) return [];
      // This curated collection uses descriptive filenames, unlike arbitrary Archive uploads.
      const title = text(f.title) || f.name.split('/').at(-1).replace(/\.flac$/i, '');
      const artist = names(f.creator || f.artist || data.metadata?.creator);
      const duration = seconds(f.length);
      if (!/[\p{L}]/u.test(title) || !artist || !(duration > 0 && duration <= 86400)) return [];
      const album = f.name.includes('/') ? f.name.split('/').slice(0, -1).join(' / ') : text(data.metadata?.title);
      const haystack = normalize(`${title} ${album} ${artist} Musopen`);
      if (!terms.every(term => haystack.includes(term))) return [];
      return [{ id: opaque(['musopen', MUSOPEN_ITEM, f.name]), title, artist, album, duration,
        artworkURL: `${ARCHIVE}/services/img/${MUSOPEN_ITEM}`, format: 'flac',
        sourcePage: `${ARCHIVE}/details/${MUSOPEN_ITEM}` }];
    }).slice(0, 40);
  }
  async function searchArchive(q) {
    // Treat user terms as literals; never accept a caller-provided Lucene expression.
    const terms = q.match(/[\p{L}\p{N}]+/gu) || [];
    if (!terms.length) return [];
    const query = `mediatype:audio AND format:Flac AND (${terms.map(t => `"${t}"`).join(' AND ')})`;
    const queryUrl = new URL(`${ARCHIVE}/advancedsearch.php`);
    for (const [k, v] of Object.entries({ q: query, 'fl[]': 'identifier', rows: '3', output: 'json' })) queryUrl.searchParams.set(k, v);
    const signal = AbortSignal.timeout(7500);
    const data = await request(queryUrl.toString(), { signal });
    if (!Array.isArray(data.response?.docs)) throw new SourceError('Internet Archive returned an invalid search response');
    const results = await Promise.allSettled(data.response.docs.slice(0, 3).map(async item => {
      const id = item.identifier;
      if (!/^[\w.-]{1,160}$/.test(id) || id === '.' || id === '..') return [];
      const data = await archiveMetadata(id, signal);
      const files = (data.files || []).filter(f => /^flac$/i.test(f.format || '') && safeFile(f.name));
      return files.flatMap(f => {
        const title = text(f.title) || (files.length === 1 ? text(data.metadata?.title) : '');
        const artist = names(f.creator || f.artist || data.metadata?.creator);
        const duration = seconds(f.length);
        if (!title || !artist || !(duration > 0) || duration > 86400) return [];
        return [{ id: opaque(['archive', id, f.name]), title, artist, duration,
          album: text(data.metadata?.title), artworkURL: `${ARCHIVE}/services/img/${encodeURIComponent(id)}`,
          format: 'flac', sourcePage: `${ARCHIVE}/details/${encodeURIComponent(id)}` }];
      });
    }));
    const tracks = results.flatMap(r => r.status === 'fulfilled' ? r.value : []);
    if (!tracks.length && results.some(r => r.status === 'rejected')) throw results.find(r => r.status === 'rejected').reason;
    return tracks.slice(0, 40);
  }
  async function resolve(source, token) {
    const id = unpack(token);
    if (!Array.isArray(id) || id[0] !== source) throw new SourceError('Invalid recording ID', 400);
    let url;
    if (source === 'netease') {
      if (id.length !== 2 || typeof id[1] !== 'string' || !/^[1-9][0-9]{0,15}$/.test(id[1])) throw new SourceError('Invalid recording ID', 400);
      const params = new URLSearchParams({ server: 'netease', type: 'url', id: id[1], br: '999000' });
      url = await request(`${METING}?${params}`, { neteaseLink: true });
    } else if (source === 'monochrome') {
      if (id.length !== 2 || typeof id[1] !== 'string' || !/^[\w-]{1,128}$/.test(id[1])) throw new SourceError('Invalid recording ID', 400);
      url = `${MONO}/track/${encodeURIComponent(id[1])}`;
    } else {
      if (id.length !== 3 || typeof id[1] !== 'string' || !/^[\w.-]{1,160}$/.test(id[1]) || ['.', '..'].includes(id[1]) || !safeFile(id[2])) throw new SourceError('Invalid recording ID', 400);
      if (source === 'musopen' && id[1] !== MUSOPEN_ITEM) throw new SourceError('Invalid Musopen collection', 400);
      const data = await archiveMetadata(id[1]);
      if (!data.files?.some(f => f.name === id[2] && /^flac$/i.test(f.format || ''))) throw new SourceError('FLAC recording not found', 404);
      url = archiveMedia(id[1], id[2]);
    }
    const measured = await request(url, { media: true, neteaseMedia: source === 'netease' });
    return { url, codec: 'flac', container: 'flac', format: 'flac', manifest: 'none', encrypted: false, ...measured };
  }
  return async req => {
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (req.method !== 'GET') return json({ error: 'Method not allowed' }, 405, { Allow: 'GET, OPTIONS' });
    const url = new URL(req.url), parts = url.pathname.split('/').filter(Boolean);
    const source = Object.hasOwn(sources, parts[0]) ? parts.shift() : 'monochrome';
    const route = parts.join('/');
    try {
      if (route === 'manifest.json') return json(manifest(source));
      if (route === 'health') return json({ status: 'adapter-ready', source, upstreamVerified: false,
        note: 'Adapter availability only. Resolve a recording to check its FLAC header.' });
      if ((url.searchParams.get('quality') || 'best').toLowerCase() !== 'best') return json({ error: 'Unsupported quality; choose best' }, 400);
      if (route === 'search') {
        const q = (url.searchParams.get('q') || '').trim();
        if (q.length > 200) return json({ error: 'Search is too long' }, 400);
        if (!q) return json({ tracks: [] });
        const tracks = await memo(`${source}:search:${q}`, 60000, () => source === 'monochrome' ? searchMono(q) : source === 'netease' ? searchNetease(q) : source === 'musopen' ? searchMusopen(q) : searchArchive(q));
        return json({ tracks });
      }
      if (parts.length === 2 && parts[0] === 'stream') {
        const stream = await memo(`${source}:stream:${parts[1]}`, 30000, () => resolve(source, parts[1]));
        return json(stream);
      }
      return json({ error: 'Not found' }, 404);
    } catch (e) {
      return json({ error: e instanceof SourceError ? e.message : 'Invalid source response' }, e instanceof SourceError ? e.status : 502,
        e.retryAfter ? { 'Retry-After': String(e.retryAfter) } : {});
    }
  };
}
/** How long a paused add-on tells apps to stay away, in seconds. */
export const PAUSED_RETRY_S = 6 * 3600;
/**
 * With PAUSED = "true" in wrangler.toml, searches and streams are turned away
 * at once, before the request limiter and device verification, so a paused
 * add-on costs one cheap reply per request and calls no Durable Object.
 * Manifests, health and sign-in still answer, so apps can still list it and
 * the deployment check still runs.
 */
export function withPause(handler) {
  return async (request, env = {}) => {
    if (env.PAUSED !== 'true' || request.method === 'OPTIONS') return handler(request, env);
    const parts = new URL(request.url).pathname.split('/').filter(Boolean);
    if (Object.hasOwn(sources, parts[0])) parts.shift();
    if (parts[0] !== 'search' && parts[0] !== 'stream') return handler(request, env);
    return json({ error: 'Paused by its owner', paused: true }, 503,
      { 'Retry-After': String(PAUSED_RETRY_S), 'X-OpenTune-Paused': '1', 'Access-Control-Expose-Headers': 'Retry-After, X-OpenTune-Paused' });
  };
}
const handler = createAddon();
export default { fetch: withPause(withAbuseProtection(withAppVerification(withMetadataCache(handler)))) };

/** OpenTune's implementation of the BitChord HTTP audio add-on contract. */
export function publicHttps(raw: string): URL {
  const u = new URL(raw);
  const host = u.hostname.toLowerCase();
  if (u.protocol !== 'https:' || u.username || u.password || !host.includes('.') || /(?:^|\.)(localhost|local|internal|test|invalid)$/.test(host) || host.includes(':')) throw new Error('Use a public HTTPS URL');
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    const [a, b] = host.split('.').map(Number);
    if (a === 0 || a === 10 || a === 127 || a! >= 224 || (a === 169 && b === 254) || (a === 172 && b! >= 16 && b! <= 31) || (a === 192 && b === 168) || (a === 100 && b! >= 64 && b! <= 127)) throw new Error('Private network addresses are unsupported');
  }
  u.hash = '';
  return u;
}
export function endpoint(raw: string, path: string, params: Record<string, string> = {}): string {
  const u = publicHttps(raw.trim());
  u.pathname = u.pathname.replace(/\/manifest\.json\/?$/, '').replace(/\/+$/, '') + '/' + path;
  Object.entries(params).forEach(([k, v]) => u.searchParams.set(k, v));
  return u.href;
}
/** [admin]: published by OpenTune's owner (the community catalog); its URL stays out of sight and it can't be removed, only turned off. */
export type Addon = { id: string; name: string; url: string; enabled: boolean; admin?: boolean; ownerOff?: boolean };
export type Recording = { title: string; artist: string; duration: number };
export type CatalogTrack = Recording & { id: string; album?: string };
export type Manifest = { id: string; name: string; version?: string; allowDownloads?: boolean; checkValidLossless?: boolean; settings?: { key: string; default?: string; options?: { value: string; label?: string }[] }[] };
export function manifestOf(value: unknown): Manifest {
  const v = value as Manifest;
  if (!v || typeof v.id !== 'string' || !v.id || typeof v.name !== 'string' || !v.name) throw new Error('Invalid addon manifest');
  if (v.settings && !Array.isArray(v.settings)) throw new Error('Invalid addon settings');
  return v;
}
const normal = (s: string) => s.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/\b(official|audio|video|lyrics)\b/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
export function matches(wanted: Recording, candidate: CatalogTrack): boolean {
  if (!candidate || typeof candidate.id !== 'string' || typeof candidate.title !== 'string' || typeof candidate.artist !== 'string' || !Number.isFinite(candidate.duration)) return false;
  if (!wanted.duration || candidate.duration <= 0 || Math.abs(wanted.duration - candidate.duration) > 3) return false;
  const artists = (s: string) => s.split(/,| & | feat\.? | featuring | ft\.? /i).map(normal).filter(Boolean);
  const credit = artists(candidate.artist);
  const requested = artists(wanted.artist);
  return normal(wanted.title) === normal(candidate.title) && requested.length > 0 && requested.every(a => credit.includes(a));
}
export function qualityParams(manifest: Manifest): Record<string, string> {
  const params: Record<string, string> = {};
  for (const setting of manifest.settings ?? []) {
    if (typeof setting.key !== 'string') continue;
    if (typeof setting.default === 'string') params[setting.key] = setting.default;
    if (setting.key === 'quality') {
      const values = (setting.options ?? []).map(x => x.value).filter(x => typeof x === 'string');
      const selected = ['hi_res_lossless', 'HI_RES_LOSSLESS', 'hires', 'hires_lossless', 'lossless', 'LOSSLESS', 'flac', 'best', 'max'].find(q => values.includes(q));
      if (selected) params.quality = selected;
    }
  }
  return params;
}
export function flacInfo(bytes: Uint8Array): { bits: number; rate: number; duration: number } {
  if (bytes.length < 42 || String.fromCharCode(...bytes.slice(0, 4)) !== 'fLaC' || (bytes[4]! & 127) !== 0) throw new Error('The source did not return a FLAC file');
  const rate = (bytes[18]! << 12) | (bytes[19]! << 4) | (bytes[20]! >> 4);
  const bits = (((bytes[20]! & 1) << 4) | (bytes[21]! >> 4)) + 1;
  const samples = (bytes[21]! & 15) * 2 ** 32 + bytes[22]! * 2 ** 24 + bytes[23]! * 2 ** 16 + bytes[24]! * 256 + bytes[25]!;
  if (!rate || !samples) throw new Error('FLAC header has no duration');
  return { bits, rate, duration: samples / rate };
}

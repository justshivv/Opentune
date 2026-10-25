/** A signed data document, never a script or a replacement for device verification. */
export const sectionIds = ['speed-dial', 'quick-picks', 'mixes', 'spotify-mixes', 'featured-playlists', 'recommendations'] as const;
export type SectionId = typeof sectionIds[number];
export type ConfigData = {
  sections: { id: SectionId; enabled: boolean; title: string }[];
  banners: { id: string; enabled: boolean; title: string; body: string; action: string; url: string; platforms: string[] }[];
  playlists: { id: string; title: string; browseId: string; artwork: string }[];
  sources: { id: string; name: string; url: string; enabled: boolean }[] | null;
  links: { github: string; discord: string; web: string; tv: string };
  network: { timeoutMs: number; retries: number }; communityPrompts: boolean;
};
export type AppConfig = { revision: number; expiresAt: number; data: ConfigData };
export const defaults = (): AppConfig => ({ revision: 0, expiresAt: Infinity, data: {
  sections: sectionIds.map(id => ({ id, enabled: true, title: '' })), banners: [], playlists: [], sources: null,
  links: { github: 'https://github.com/justshivv/Opentune', discord: 'https://discord.gg/6udrCVh5n', web: 'https://justshivv.github.io/Opentune/player/', tv: 'https://justshivv.github.io/Opentune/' },
  network: { timeoutMs: 8000, retries: 2 }, communityPrompts: true,
} });
function check(value: unknown): asserts value { if (!value) throw new Error('Invalid configuration'); }
function text(value: unknown, max: number, blank = false): asserts value is string { check(typeof value === 'string' && value.length <= max && (blank || value.trim().length > 0)); }
function url(value: unknown) { text(value, 2048); const u = new URL(value); check(u.protocol === 'https:' && !u.username && !u.password && !u.hash && u.hostname.includes('.') && !/^[\d.]+$/.test(u.hostname) && !u.hostname.includes(':') && !/(^|\.)(localhost|local|internal)$/.test(u.hostname)); return u; }
function list(value: unknown, max: number): asserts value is Record<string, unknown>[] { check(Array.isArray(value) && value.length <= max && value.every(x => x && typeof x === 'object')); }
function ids(value: Record<string, unknown>[]) { check(value.every(x => typeof x.id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(x.id)) && new Set(value.map(x => x.id)).size === value.length); }
export function validateData(value: unknown): ConfigData {
  check(value && typeof value === 'object'); const d = value as Record<string, unknown>;
  list(d.sections, 6); ids(d.sections); check(d.sections.length === 6 && d.sections.some(x => x.enabled));
  for (const s of d.sections) { check(sectionIds.includes(s.id as SectionId) && typeof s.enabled === 'boolean'); text(s.title, 80, true); }
  list(d.banners, 3); ids(d.banners);
  for (const b of d.banners) { check(typeof b.enabled === 'boolean'); text(b.title, 100); text(b.body, 300, true); text(b.action, 40); url(b.url); check(Array.isArray(b.platforms) && b.platforms.length <= 3 && b.platforms.every(p => ['android', 'tv', 'web'].includes(p))); }
  list(d.playlists, 12); ids(d.playlists);
  for (const p of d.playlists) { text(p.title, 100); check(typeof p.browseId === 'string' && /^VL[A-Za-z0-9_-]{8,150}$/.test(p.browseId)); text(p.artwork, 2048, true); if (p.artwork) url(p.artwork); }
  if (d.sources !== null) { list(d.sources, 20); ids(d.sources); for (const s of d.sources) { text(s.name, 80); url(s.url); check(typeof s.enabled === 'boolean'); } }
  check(d.links && typeof d.links === 'object'); const links = d.links as Record<string, unknown>;
  check(url(links.github).hostname === 'github.com' && ['discord.gg', 'discord.com'].includes(url(links.discord).hostname)); url(links.web); url(links.tv);
  check(d.network && typeof d.network === 'object'); const n = d.network as Record<string, unknown>;
  check(Number.isInteger(n.timeoutMs) && Number(n.timeoutMs) >= 4000 && Number(n.timeoutMs) <= 20000 && Number.isInteger(n.retries) && Number(n.retries) >= 0 && Number(n.retries) <= 2 && typeof d.communityPrompts === 'boolean');
  return d as ConfigData;
}
function decode(value: string): Uint8Array<ArrayBuffer> { return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0)); }
export async function verifyConfig(envelope: string, publicKey: string, minimumRevision = 0, now = Date.now()): Promise<AppConfig> {
  check(envelope.length <= 100000 && publicKey.length <= 1024);
  const signed = JSON.parse(envelope) as { payload: string; signature: string };
  text(signed.payload, 100000); text(signed.signature, 100);
  const key = await crypto.subtle.importKey('spki', decode(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  check(await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, decode(signed.signature), new TextEncoder().encode(signed.payload)));
  const doc = JSON.parse(new TextDecoder().decode(decode(signed.payload))) as Record<string, unknown>;
  check(doc.schemaVersion === 1 && Number.isSafeInteger(doc.revision) && Number(doc.revision) > 0 && Number(doc.revision) >= minimumRevision);
  check(Number.isSafeInteger(doc.issuedAt) && Number.isSafeInteger(doc.expiresAt) && Number(doc.issuedAt) <= now + 600000 && Number(doc.expiresAt) > now && Number(doc.expiresAt) > Number(doc.issuedAt) && Number(doc.expiresAt) - Number(doc.issuedAt) <= 7 * 86400000);
  return { revision: Number(doc.revision), expiresAt: Number(doc.expiresAt), data: validateData(doc.data) };
}

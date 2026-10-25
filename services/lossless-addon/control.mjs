import { createHash, createHmac, generateKeyPairSync, randomBytes, sign, timingSafeEqual } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync, chmodSync } from 'node:fs';
import { join } from 'node:path';

export const SECTION_IDS = ['speed-dial', 'quick-picks', 'mixes', 'spotify-mixes', 'featured-playlists', 'recommendations'];
export const defaults = () => ({
  sections: SECTION_IDS.map(id => ({ id, enabled: true, title: '' })),
  banners: [], playlists: [], sources: null,
  links: { github: 'https://github.com/justshivv/Opentune', discord: 'https://discord.gg/6udrCVh5n', web: 'https://justshivv.github.io/Opentune/player/', tv: 'https://justshivv.github.io/Opentune/' },
  network: { timeoutMs: 8000, retries: 2 }, communityPrompts: true,
});
function requireValue(ok, message) { if (!ok) throw new Error(message); }
const text = (value, max, name, empty = false) => {
  requireValue(typeof value === 'string' && value.length <= max && (empty || value.trim().length > 0), `Invalid ${name}`);
  return value.trim();
};
function https(value) {
  text(value, 2048, 'URL');
  const u = new URL(value);
  requireValue(u.protocol === 'https:' && !u.username && !u.password && !u.hash && u.hostname.includes('.') &&
    !/^[\d.]+$/.test(u.hostname) && !u.hostname.includes(':') && !/(^|\.)(localhost|local|internal)$/.test(u.hostname), 'Use a public HTTPS URL');
  return u.href;
}
function array(value, max, name) { requireValue(Array.isArray(value) && value.length <= max, `Invalid ${name}`); return value; }
function enabled(value) { requireValue(typeof value === 'boolean', 'An enabled switch must be true or false'); return value; }
function id(value) { requireValue(typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value), 'Invalid item id'); return value; }
function unique(items) { requireValue(new Set(items.map(x => x.id)).size === items.length, 'Duplicate item id'); return items; }
export function validateConfig(input) {
  requireValue(input && typeof input === 'object' && !Array.isArray(input), 'Invalid configuration');
  requireValue(Object.keys(input).every(k => Object.keys(defaults()).includes(k)), 'Unknown configuration field');
  const sections = unique(array(input.sections, 6, 'sections').map(x => {
    requireValue(SECTION_IDS.includes(x.id), 'Unsupported home section');
    return { id: x.id, enabled: enabled(x.enabled), title: text(x.title, 80, 'section title', true) };
  }));
  requireValue(sections.length === SECTION_IDS.length && sections.some(x => x.enabled), 'Include every supported section and enable at least one');
  const banners = unique(array(input.banners, 3, 'banners').map(x => ({ id: id(x.id), enabled: enabled(x.enabled),
    title: text(x.title, 100, 'banner title'), body: text(x.body, 300, 'banner text', true),
    action: text(x.action, 40, 'button label'), url: https(x.url),
    platforms: array(x.platforms, 3, 'platforms').map(p => { requireValue(['android', 'tv', 'web'].includes(p), 'Invalid platform'); return p; }),
  })));
  const playlists = unique(array(input.playlists, 12, 'playlists').map(x => {
    requireValue(/^VL[A-Za-z0-9_-]{8,150}$/.test(x.browseId), 'Playlist browseId must start with VL');
    return { id: id(x.id), title: text(x.title, 100, 'playlist title'), browseId: x.browseId, artwork: x.artwork ? https(x.artwork) : '' };
  }));
  const sources = input.sources === null ? null : unique(array(input.sources, 20, 'sources').map(x => ({
    id: id(x.id), name: text(x.name, 80, 'source name'), url: https(x.url), enabled: enabled(x.enabled),
  })));
  const links = Object.fromEntries(['github', 'discord', 'web', 'tv'].map(k => [k, https(input.links?.[k])]));
  requireValue(new URL(links.github).hostname === 'github.com', 'GitHub link must use github.com');
  requireValue(['discord.gg', 'discord.com'].includes(new URL(links.discord).hostname), 'Discord link must use discord.gg or discord.com');
  const network = input.network;
  requireValue(Number.isInteger(network?.timeoutMs) && network.timeoutMs >= 4000 && network.timeoutMs <= 20000, 'Timeout must be 4000–20000 ms');
  requireValue(Number.isInteger(network.retries) && network.retries >= 0 && network.retries <= 2, 'Retries must be 0–2');
  return { sections, banners, playlists, sources, links, network: { timeoutMs: network.timeoutMs, retries: network.retries }, communityPrompts: enabled(input.communityPrompts) };
}

export class ConfigStore {
  constructor(directory) {
    const file = join(directory, 'control.sqlite');
    this.db = new DatabaseSync(file); chmodSync(file, 0o600);
    this.db.exec('PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS keys (id INTEGER PRIMARY KEY, privateKey TEXT, publicKey TEXT, sessionSecret TEXT); CREATE TABLE IF NOT EXISTS configs (revision INTEGER PRIMARY KEY AUTOINCREMENT, createdAt INTEGER NOT NULL, data TEXT NOT NULL);');
    this.keys = this.db.prepare('SELECT * FROM keys WHERE id=1').get();
    if (!this.keys) {
      const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      this.keys = { privateKey: pair.privateKey.export({ type: 'pkcs8', format: 'pem' }), publicKey: pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), sessionSecret: randomBytes(32).toString('hex') };
      this.db.prepare('INSERT INTO keys VALUES(1,?,?,?)').run(this.keys.privateKey, this.keys.publicKey, this.keys.sessionSecret);
    }
  }
  publicKey() { return { algorithm: 'ES256', publicKey: this.keys.publicKey }; }
  current() { const row = this.db.prepare('SELECT * FROM configs ORDER BY revision DESC LIMIT 1').get(); return row ? { ...row, data: JSON.parse(row.data) } : { revision: 0, createdAt: 0, data: defaults() }; }
  history() { return this.db.prepare('SELECT revision,createdAt FROM configs ORDER BY revision DESC LIMIT 20').all(); }
  publish(data, expectedRevision) {
    const clean = validateConfig(data);
    requireValue(Buffer.byteLength(JSON.stringify(clean)) <= 60000, 'Invalid configuration: exceeds 60 KB');
    this.db.exec('BEGIN IMMEDIATE');
    try {
      if (expectedRevision !== this.current().revision) throw new Error('Configuration changed. Reload before publishing.');
      this.db.prepare('INSERT INTO configs(createdAt,data) VALUES(?,?)').run(Date.now(), JSON.stringify(clean));
      const result = this.current(); this.db.exec('COMMIT'); return result;
    } catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }
  rollback(revision, expectedRevision) {
    const row = this.db.prepare('SELECT data FROM configs WHERE revision=?').get(revision);
    requireValue(!!row, 'Revision not found');
    return this.publish(JSON.parse(row.data), expectedRevision);
  }
  envelope(now = Date.now()) {
    const current = this.current();
    if (!current.revision) return null;
    const issuedAt = Math.floor(now / 300000) * 300000;
    if (this.cached?.revision === current.revision && this.cached?.issuedAt === issuedAt) return this.cached.envelope;
    const payload = Buffer.from(JSON.stringify({ schemaVersion: 1, revision: current.revision, issuedAt, expiresAt: issuedAt + 7 * 86400000, data: current.data })).toString('base64url');
    const signature = sign('sha256', Buffer.from(payload), { key: this.keys.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url');
    const envelope = { payload, signature };
    this.cached = { revision: current.revision, issuedAt, envelope };
    return envelope;
  }
  close() { this.db.close(); }
}

const privateHeaders = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'" };
const same = (a, b) => timingSafeEqual(createHash('sha256').update(String(a)).digest(), createHash('sha256').update(String(b)).digest());
export function createControl(store, env = process.env) {
  const attempts = new Map();
  const credentials = () => typeof env.CONTROL_ADMIN_USERNAME === 'string' && env.CONTROL_ADMIN_USERNAME.length >= 3 &&
    typeof env.CONTROL_ADMIN_PASSWORD === 'string' && env.CONTROL_ADMIN_PASSWORD.length >= 16;
  const mac = value => createHmac('sha256', store.keys.sessionSecret).update(JSON.stringify([env.CONTROL_ADMIN_USERNAME, env.CONTROL_ADMIN_PASSWORD, value])).digest('base64url');
  const session = request => {
    const token = request.headers.get('Authorization')?.replace(/^Bearer /, '') || '';
    if (!credentials() || token.length > 1000) return false;
    const [payload, signature, extra] = token.split('.');
    if (!payload || !signature || extra || !same(signature, mac(payload))) return false;
    try { const exp = JSON.parse(Buffer.from(payload, 'base64url')).exp; return Number.isInteger(exp) && exp > Date.now() && exp <= Date.now() + 4 * 3600000; } catch { return false; }
  };
  const json = (body, status = 200, headers = {}) => Response.json(body, { status, headers: { ...privateHeaders, ...headers } });
  return async request => {
    const url = new URL(request.url), path = url.pathname;
    if (!path.startsWith('/control') && !path.startsWith('/v1/app-config')) return null;
    if (['/v1/app-config', '/v1/app-config/key'].includes(path) && request.method === 'GET') {
      const origin = request.headers.get('Origin');
      const cors = (env.WEB_ORIGINS || 'https://justshivv.github.io').split(',').includes(origin) ? { 'Access-Control-Allow-Origin': origin } : {};
      const data = path.endsWith('/key') ? store.publicKey() : store.envelope();
      return json(data || { error: 'No configuration published' }, data ? 200 : 404, { ...cors, Vary: 'Origin', 'Cache-Control': data ? 'public, max-age=300' : 'no-store' });
    }
    const asset = { '/control/': ['control.html', 'text/html'], '/control/control.js': ['control.js', 'text/javascript'], '/control/control.css': ['control.css', 'text/css'] }[path];
    if (asset && request.method === 'GET') return new Response(readFileSync(new URL('./' + asset[0], import.meta.url)), { headers: { ...privateHeaders, 'Content-Type': asset[1] + '; charset=utf-8' } });
    if (path === '/control') return new Response(null, { status: 302, headers: { Location: '/control/' } });
    if (request.method !== 'GET' && request.headers.get('Origin') && request.headers.get('Origin') !== url.origin) return json({ error: 'Untrusted origin' }, 403);
    try {
      if (path === '/control/login' && request.method === 'POST') {
        if (!credentials()) return json({ error: 'Set CONTROL_ADMIN_USERNAME and CONTROL_ADMIN_PASSWORD on the server first.' }, 503);
        const ip = request.headers.get('CF-Connecting-IP') || 'unknown', now = Date.now();
        for (const [key, value] of attempts) if (now - value.at > 60000) attempts.delete(key);
        const attempt = attempts.get(ip) || { at: now, count: 0 };
        if (attempt.count >= 5 || attempts.size >= 2000 && !attempts.has(ip)) return json({ error: 'Wait a minute before trying again.' }, 429);
        attempt.count++; attempts.set(ip, attempt);
        const body = await request.json();
        if (!same(body.username, env.CONTROL_ADMIN_USERNAME) || !same(body.password, env.CONTROL_ADMIN_PASSWORD)) return json({ error: 'Incorrect username or password' }, 401);
        const payload = Buffer.from(JSON.stringify({ exp: now + 4 * 3600000 })).toString('base64url');
        return json({ token: payload + '.' + mac(payload) });
      }
      if (!session(request)) return json({ error: 'Sign in required' }, 401);
      if (path === '/control/config' && request.method === 'GET') return json({ ...store.current(), history: store.history() });
      if (path === '/control/config' && request.method === 'POST') {
        const body = await request.json(); return json(store.publish(body.data, body.expectedRevision));
      }
      if (path === '/control/rollback' && request.method === 'POST') {
        const body = await request.json(); requireValue(Number.isSafeInteger(body.revision), 'Invalid revision');
        return json(store.rollback(body.revision, body.expectedRevision));
      }
      return json({ error: 'Not found' }, 404);
    } catch (e) {
      // Only our validation messages, never database internals or signed source URLs.
      const message = String(e.message);
      const safe = /^(Invalid |Use a public|Unknown configuration|Unsupported home|An enabled|Duplicate item|Include every|Playlist browseId|GitHub link|Discord link|Timeout must|Retries must|Configuration changed|Revision not found)/.test(message);
      return json({ error: safe ? message : 'Invalid request' }, message.startsWith('Configuration changed') ? 409 : 400);
    }
  };
}

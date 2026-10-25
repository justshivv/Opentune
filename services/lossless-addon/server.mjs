// The OpenTune lossless add-on on an ordinary server, for a VPS rather than
// Cloudflare. worker.mjs runs unchanged; this file stands in for what
// Cloudflare gave it: the Durable Object that keeps device sessions (here a
// SQLite file), the request limiter (here in memory) and the HTTP server.
// Caddy in front of it handles HTTPS; this listens on 127.0.0.1 only.
import http from 'node:http';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import worker, { AddonAuthState } from './worker.mjs';
import { ConfigStore, createControl } from './control.mjs';

const env = {
  VERIFICATION_MODE: process.env.VERIFICATION_MODE || 'strict',
  WEB_ORIGINS: process.env.WEB_ORIGINS || 'https://justshivv.github.io',
  APP_CERT_SHA256: process.env.APP_CERT_SHA256 || '',
  PAUSED: process.env.PAUSED || 'false',
};
const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const PUBLIC_HOST = process.env.PUBLIC_HOST || 'localhost';
const DATA_DIR = process.env.DATA_DIR || './data';
const LIMIT = Number(process.env.REQUESTS_PER_MINUTE || 240);
const MAX_BODY = 100_000;

// ---- Device sessions, challenges and browser pairings (Durable Object stand-in) ----
mkdirSync(DATA_DIR, { recursive: true });
const configStore = new ConfigStore(DATA_DIR);
const control = createControl(configStore);
const db = new DatabaseSync(join(DATA_DIR, 'auth.sqlite'));
db.exec(`PRAGMA journal_mode=WAL;
  CREATE TABLE IF NOT EXISTS state (name TEXT PRIMARY KEY, data TEXT NOT NULL, alarm INTEGER);
  CREATE INDEX IF NOT EXISTS state_alarm ON state(alarm);`);
const readRow = db.prepare('SELECT data FROM state WHERE name = ?');
const writeRow = db.prepare(`INSERT INTO state(name, data) VALUES(?, ?)
  ON CONFLICT(name) DO UPDATE SET data = excluded.data`);
const alarmRow = db.prepare('UPDATE state SET alarm = ? WHERE name = ?');
const dropRow = db.prepare('DELETE FROM state WHERE name = ?');
const dropExpired = db.prepare('DELETE FROM state WHERE alarm IS NOT NULL AND alarm <= ?');

/** One object's storage, as Durable Objects offer it: keys inside one row. */
function storageFor(name) {
  const load = () => { const row = readRow.get(name); return row ? JSON.parse(row.data) : {}; };
  return {
    async get(key) { return load()[key]; },
    async put(key, value) { const data = load(); data[key] = value; writeRow.run(name, JSON.stringify(data)); },
    async deleteAll() { dropRow.run(name); },
    async setAlarm(at) { alarmRow.run(Math.floor(Number(at)), name); },
  };
}
// Changes to one object run one at a time, as blockConcurrencyWhile promises.
const queues = new Map();
function serially(name, fn) {
  const next = (queues.get(name) || Promise.resolve()).then(fn);
  const settled = next.catch(() => {});
  queues.set(name, settled);
  settled.then(() => { if (queues.get(name) === settled) queues.delete(name); });
  return next;
}
env.AUTH = {
  idFromName: name => name,
  get: name => ({
    fetch: (url, init) => new AddonAuthState({
      storage: storageFor(name),
      blockConcurrencyWhile: fn => serially(name, fn),
    }).fetch(new Request(url, init)),
  }),
};
// What Durable Object alarms did: expired entries go.
setInterval(() => { try { dropExpired.run(Date.now()); } catch (e) { console.error('cleanup failed', e); } }, 60_000).unref();

// ---- Requests per address per minute (rate limiter stand-in) ----
const windows = new Map();
env.REQUEST_LIMITER = {
  async limit({ key }) {
    const now = Date.now(), w = windows.get(key);
    if (!w || now - w.start >= 60_000) { windows.set(key, { start: now, count: 1 }); return { success: true }; }
    w.count++;
    return { success: w.count <= LIMIT };
  },
};
setInterval(() => {
  const now = Date.now();
  for (const [key, w] of windows) if (now - w.start >= 60_000) windows.delete(key);
}, 60_000).unref();

// ---- HTTP ----
/** The caller's address: Caddy puts the one it saw last in X-Forwarded-For. */
function clientAddress(req) {
  const peer = req.socket.remoteAddress || '';
  const local = peer === '127.0.0.1' || peer === '::1' || peer === '::ffff:127.0.0.1';
  const forwarded = req.headers['x-forwarded-for'];
  if (local && typeof forwarded === 'string' && forwarded.trim()) return forwarded.split(',').pop().trim();
  return peer;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = []; let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY) { reject(new Error('Body too large')); req.destroy(); return; }
      parts.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(parts)));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (value === undefined || name.startsWith(':')) continue;
      // Cloudflare's own header is set here, never taken from the caller.
      if (name === 'cf-connecting-ip') continue;
      headers.set(name, Array.isArray(value) ? value.join(', ') : value);
    }
    headers.set('CF-Connecting-IP', clientAddress(req));
    const method = req.method || 'GET';
    const body = method === 'GET' || method === 'HEAD' ? undefined : await readBody(req);
    const request = new Request(`https://${PUBLIC_HOST}${req.url || '/'}`, { method, headers, body });
    const response = await control(request) || await worker.fetch(request, env);
    const out = Buffer.from(await response.arrayBuffer());
    const outHeaders = {};
    response.headers.forEach((value, name) => { outHeaders[name] = value; });
    outHeaders['content-length'] = String(out.length);
    res.writeHead(response.status, outHeaders);
    res.end(method === 'HEAD' ? undefined : out);
  } catch (e) {
    const tooLarge = e?.message === 'Body too large';
    if (!res.headersSent) res.writeHead(tooLarge ? 413 : 500, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: tooLarge ? 'Body too large' : 'Server error' }));
    if (!tooLarge) console.error(e);
  }
});
server.requestTimeout = 60_000;
server.headersTimeout = 15_000;
server.listen(PORT, HOST, () => {
  console.log(`OpenTune add-on on http://${HOST}:${PORT} for https://${PUBLIC_HOST}` +
    ` (verification ${env.VERIFICATION_MODE}${env.PAUSED === 'true' ? ', paused' : ''})`);
  if (!/^[a-f0-9]{64}$/.test(env.APP_CERT_SHA256)) console.warn('APP_CERT_SHA256 is not set: no device can sign in.');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.close(); configStore.close(); db.close(); process.exit(0); });

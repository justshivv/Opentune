import { createPublicKey, randomBytes, verify } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { googleTrust, sha256, verifyAttestation } from './attestation.mjs';

const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Accept, Content-Type, Authorization, X-OpenTune-Time, X-OpenTune-Nonce, X-OpenTune-Signature',
  'Access-Control-Expose-Headers': 'Retry-After', 'Cache-Control': 'no-store', 'Content-Type': 'application/json', 'X-Content-Type-Options': 'nosniff' };
export const reply = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: CORS });
const token = () => randomBytes(32).toString('base64url');
const denied = () => reply({ error: 'Verify this device with the current official OpenTune app. Unsupported devices cannot use this source.' }, 401);
export const canonical = (session, time, nonce, method, path, body = '') => ['OpenTune-Addon-v1', session, time, nonce, method, path, sha256(body)].join('\n');
function publicKey(pem) {
  const key = createPublicKey(pem);
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw new Error('Invalid key');
  return key;
}
function stub(env, name) { return env.AUTH.get(env.AUTH.idFromName(name)); }
async function state(env, name, op, value = {}) {
  const r = await stub(env, name).fetch('https://state/' + op, { method: 'POST', body: JSON.stringify(value) });
  return r.json();
}
/** All changes to one challenge/session/pair are serialized, including nonce consumption. */
export class AddonAuthState {
  constructor(ctx) { this.ctx = ctx; }
  async alarm() { await this.ctx.storage.deleteAll(); }
  async fetch(request) {
    return this.ctx.blockConcurrencyWhile(async () => {
      const op = new URL(request.url).pathname.slice(1), input = await request.json(), now = Date.now();
      const store = this.ctx.storage; let item = await store.get('item');
      if (op === 'create') {
        if (item) return reply({ ok: false });
        await store.put('item', input); await store.setAlarm(input.expires); return reply({ ok: true });
      }
      if (!item || item.expires <= now) return reply({ ok: false });
      if (op === 'consume') { await store.deleteAll(); return reply({ ok: true }); }
      if (op === 'check') {
        const { session, time, nonce, method, path, body, signature } = input;
        if (!item.key || !TOKEN.test(session || '') || !/^[0-9]{10,13}$/.test(time || '') || Math.abs(now - Number(time)) > 60_000 ||
          !/^[A-Za-z0-9_-]{22,64}$/.test(nonce || '') || typeof signature !== 'string' || signature.length > 128) return reply({ ok: false });
        const nonces = (item.nonces || []).filter(n => n.at > now - 120_000);
        if (nonces.some(n => n.value === nonce) || nonces.length >= 480) return reply({ ok: false });
        let valid = false;
        try { valid = verify('sha256', Buffer.from(canonical(session, time, nonce, method, path, body)),
          { key: publicKey(item.key), dsaEncoding: item.kind === 'web' ? 'ieee-p1363' : 'der' }, Buffer.from(signature, 'base64url')); } catch {}
        if (!valid) return reply({ ok: false });
        item.nonces = [...nonces, { value: nonce, at: now }]; await store.put('item', item);
        return reply({ ok: true, kind: item.kind, expires: item.expires });
      }
      if (op === 'info' && item.pollHash) return reply({ ok: true, origin: item.origin, code: item.code, expires: item.expires, approved: !!item.session });
      if (op === 'approve' && item.pollHash && !item.session) {
        item.session = input.session; item.sessionExpires = input.sessionExpires;
        await store.put('item', item); return reply({ ok: true, key: item.key });
      }
      if (op === 'poll' && item.pollHash && input.pollHash === item.pollHash) return reply({ ok: true, session: item.session || null, expires: item.sessionExpires, code: item.code });
      return reply({ ok: false });
    });
  }
}
async function authenticated(request, env, body) {
  const session = request.headers.get('Authorization')?.replace(/^Bearer /, '');
  if (!TOKEN.test(session || '')) return null;
  const url = new URL(request.url);
  const checked = await state(env, 'session:' + sha256(session), 'check', { session, body,
    time: request.headers.get('X-OpenTune-Time'), nonce: request.headers.get('X-OpenTune-Nonce'),
    signature: request.headers.get('X-OpenTune-Signature'), method: request.method, path: url.pathname + url.search });
  return checked.ok ? checked : null;
}
async function readBody(request) {
  if (!request.body) return '';
  const reader = request.body.getReader(); let size = 0, chunks = [];
  try { for (;;) { const { value, done } = await reader.read(); if (done) break; size += value.length; if (size > 80_000) throw new Error('Body too large'); chunks.push(Buffer.from(value)); } }
  finally { await reader.cancel().catch(() => {}); }
  return Buffer.concat(chunks).toString('utf8');
}
export function withAppVerification(handler, { trust = googleTrust, attest = verifyAttestation } = {}) {
  return async (request, env) => {
    const url = new URL(request.url), path = url.pathname;
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (path === '/auth/policy' && request.method === 'GET') return reply({ mode: env.VERIFICATION_MODE === 'staged' ? 'staged' : 'strict', native: 'hardware-attestation', web: 'verified-app-approval' });
    // Public installation metadata is not a way to fetch the catalogue or audio.
    const publicRoute = request.method === 'GET' && (path.endsWith('/manifest.json') || path.endsWith('/health') || path === '/');
    if (publicRoute) return handler(request);
    if (!env.AUTH) return reply({ error: 'Device verification is unavailable' }, 503);
    try {
      if (path === '/auth/challenge' && request.method === 'GET') {
        const challenge = token(); await state(env, 'challenge:' + challenge, 'create', { expires: Date.now() + 120_000 });
        return reply({ challenge, serverTime: Date.now() });
      }
      const body = request.method === 'POST' ? await readBody(request) : '';
      if (path === '/auth/enroll' && request.method === 'POST') {
        const input = JSON.parse(body);
        const signers = (env.APP_CERT_SHA256 || '').toLowerCase().split(',').filter(s => /^[a-f0-9]{64}$/.test(s));
        if (!signers.length) return reply({ error: 'Official signing certificate is not configured' }, 503);
        if (!TOKEN.test(input.challenge || '')) return denied();
        // Only genuine fresh challenges may trigger certificate/trust processing.
        const used = await state(env, 'challenge:' + input.challenge, 'consume'); if (!used.ok) return denied();
        const key = attest(input, await trust(), signers), session = token(), expires = Date.now() + 7 * 86400_000;
        await state(env, 'session:' + sha256(session), 'create', { key, kind: 'native', expires });
        return reply({ session, expires, serverTime: Date.now() });
      }
      if (path === '/auth/web/start' && request.method === 'POST') {
        const origin = request.headers.get('Origin');
        if (!(env.WEB_ORIGINS || 'https://justshivv.github.io').split(',').includes(origin)) return denied();
        const input = JSON.parse(body), key = publicKey({ key: input.key, format: 'jwk' }).export({ type: 'spki', format: 'pem' }).toString();
        const id = token(), poll = token(), code = randomBytes(4).toString('hex').toUpperCase(), expires = Date.now() + 300_000;
        await state(env, 'pair:' + id, 'create', { key, pollHash: sha256(poll), origin, code, expires });
        return reply({ id, poll, code, expires, link: 'opentune://addon-approve?id=' + id });
      }
      if (path === '/auth/web/status' && request.method === 'POST') {
        const input = JSON.parse(body); if (!TOKEN.test(input.id || '') || !TOKEN.test(input.poll || '')) return denied();
        const result = await state(env, 'pair:' + input.id, 'poll', { pollHash: sha256(input.poll) });
        return result.ok ? reply({ ...result, serverTime: Date.now() }) : denied();
      }
      const auth = await authenticated(request, env, body);
      if (!auth) {
        // Staging exists solely to distribute/test compatible clients first.
        // An explicit strict deployment removes this compatibility window.
        if (env.VERIFICATION_MODE === 'staged' && request.method === 'GET' && !path.startsWith('/auth/')) return handler(request);
        return denied();
      }
      if (path.startsWith('/auth/web/')) {
        if (auth.kind !== 'native') return denied();
        const id = request.method === 'POST' ? JSON.parse(body).id : url.searchParams.get('id');
        if (!TOKEN.test(id || '')) return denied();
        if (path === '/auth/web/info' && request.method === 'GET') return reply(await state(env, 'pair:' + id, 'info'));
        if (path === '/auth/web/approve' && request.method === 'POST') {
          const session = token(), expires = Math.min(auth.expires, Date.now() + 12 * 3600_000);
          const result = await state(env, 'pair:' + id, 'approve', { session, sessionExpires: expires });
          if (!result.ok) return denied();
          await state(env, 'session:' + sha256(session), 'create', { kind: 'web', key: result.key, expires });
          return reply({ ok: true });
        }
        return denied();
      }
      if (path.startsWith('/auth/')) return reply({ error: 'Not found' }, 404);
      const result = await handler(request), headers = new Headers(result.headers);
      Object.entries(CORS).forEach(([k, v]) => headers.set(k, v));
      return new Response(result.body, { status: result.status, headers });
    } catch { return reply({ error: 'Verification failed. Retry in the official OpenTune app on a supported, locked device.' }, 403); }
  };
}

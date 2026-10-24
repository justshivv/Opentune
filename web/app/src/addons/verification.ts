export const OFFICIAL_ADDON = 'https://addon-opentune.rethinknow.in';
type Session = { key: CryptoKey; session: string; expires: number; offset: number };
export type Pairing = { id: string; poll: string; code: string; link: string; expires: number; key: CryptoKey };
const bytes = (s: string) => new TextEncoder().encode(s);
const b64 = (b: ArrayBuffer | Uint8Array<ArrayBuffer>) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const hash = async (s: string) => [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes(s)))].map(x => x.toString(16).padStart(2, '0')).join('');
async function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('opentune-addon-device', 1);
    request.onupgradeneeded = () => { request.result.createObjectStore('session'); };
    request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
  });
}
async function stored(write?: Session | null): Promise<Session | undefined> {
  const db = await database();
  try {
    return await new Promise((resolve, reject) => {
      const tx = db.transaction('session', write === undefined ? 'readonly' : 'readwrite'), object = tx.objectStore('session');
      const r = write === undefined ? object.get('current') : write === null ? object.delete('current') : object.put(write, 'current');
      let value: Session | undefined;
      r.onsuccess = () => { if (write === undefined) value = r.result as Session | undefined; };
      tx.oncomplete = () => resolve(value); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
    });
  } finally { db.close(); }
}
async function call(path: string, data: object): Promise<Record<string, unknown>> {
  const r = await fetch(OFFICIAL_ADDON + path, { method: 'POST', body: JSON.stringify(data), headers: { 'Content-Type': 'application/json' },
    credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(15_000) });
  const result = await r.json() as Record<string, unknown>;
  if (!r.ok) throw new Error(typeof result.error === 'string' ? result.error : 'Verification failed');
  return result;
}
export async function startPairing(): Promise<Pairing> {
  // The browser key cannot be exported. A copied session token cannot sign requests.
  const keys = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const publicKey = await crypto.subtle.exportKey('jwk', keys.publicKey);
  const result = await call('/auth/web/start', { key: publicKey });
  if (typeof result.id !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.id) || typeof result.poll !== 'string' || typeof result.code !== 'string' || typeof result.expires !== 'number') throw new Error('Invalid browser verification response');
  return { id: result.id, poll: result.poll, code: result.code, expires: result.expires, link: 'opentune://addon-approve?id=' + result.id, key: keys.privateKey };
}
export async function pollPairing(pair: Pairing): Promise<boolean> {
  if (Date.now() >= pair.expires) throw new Error('Request expired. Start verification again.');
  const result = await call('/auth/web/status', { id: pair.id, poll: pair.poll });
  if (!result.session) return false;
  if (typeof result.session !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(result.session) || typeof result.expires !== 'number' || typeof result.serverTime !== 'number') throw new Error('Invalid verification response');
  await stored({ key: pair.key, session: result.session, expires: result.expires, offset: result.serverTime - Date.now() });
  return true;
}
export async function verifiedUntil(): Promise<number> {
  const value = await stored(); return value && value.expires > Date.now() + value.offset ? value.expires : 0;
}
export async function disconnectVerification(): Promise<void> { await stored(null); }
export function isOfficialAddon(url: string): boolean {
  try { return new URL(url).origin === OFFICIAL_ADDON; } catch { return false; }
}
export function needsVerification(url: string): boolean {
  const u = new URL(url); return isOfficialAddon(url) && u.pathname !== '/' && !u.pathname.endsWith('/manifest.json') && !u.pathname.endsWith('/health');
}
export async function verificationHeaders(url: string): Promise<Record<string, string>> {
  if (!needsVerification(url)) return {};
  const value = await stored();
  if (!value || value.expires <= Date.now() + value.offset) throw new Error('Open Settings › Audio sources and verify this browser with the official OpenTune app');
  const time = String(Date.now() + value.offset), nonce = b64(crypto.getRandomValues(new Uint8Array(24))), u = new URL(url);
  const message = ['OpenTune-Addon-v1', value.session, time, nonce, 'GET', u.pathname + u.search, await hash('')].join('\n');
  const signature = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, value.key, bytes(message));
  return { Authorization: 'Bearer ' + value.session, 'X-OpenTune-Time': time, 'X-OpenTune-Nonce': nonce, 'X-OpenTune-Signature': b64(signature) };
}

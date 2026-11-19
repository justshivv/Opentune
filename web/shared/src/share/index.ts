/**
 * "Sign in with your phone" for the web player's integrations (the phone's WebShare.kt).
 *
 * The extension makes a one-use P-256 key pair and keeps the private half in its own
 * memory; the page shows the public half in a QR code. The phone, once the person
 * approves there, agrees a key with it (ECDH, then HKDF-SHA256) and seals the sign-ins it
 * was allowed to share. The sealed packet travels over public Nostr relays as an
 * ephemeral event, and the page hands it to the extension unopened: only the extension
 * can open it. The extension answers with a sealed list of what it kept.
 */

/** The Nostr event kind the exchange travels as: ephemeral, so relays pass it on and keep nothing. */
export const SHARE_KIND = 21422;
/** How long a code is good for, as a TV's. */
export const SHARE_TTL_MS = 120_000;

const INFO = 'OpenTune share v1';
const enc = new TextEncoder();

export function toBase64Url(bytes: Uint8Array): string {
  let text = '';
  for (const b of bytes) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64Url(text: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) throw new Error('Not base64url');
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

/** The code the phone scans: `opentune://pair/web#base64url("w1|expires|publicKey|take")`. */
export function shareLink(publicKey: string, expires: number, take: readonly string[]): string {
  return `opentune://pair/web#${toBase64Url(enc.encode(`w1|${expires}|${publicKey}|${take.join(',')}`))}`;
}

/** What the relays index the exchange by; a hash, so it says nothing about the key. */
export async function shareTag(publicKey: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(`opentune/share/${publicKey}`)));
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('').slice(0, 32);
}

/** The AES-GCM key both ends arrive at: ECDH's x, then HKDF with both public keys (extension's first) as salt. */
export async function shareKey(privateKey: CryptoKey, theirs: Uint8Array, extensionPublic: Uint8Array, phonePublic: Uint8Array): Promise<CryptoKey> {
  if (theirs.length !== 65 || theirs[0] !== 4) throw new Error('Not a P-256 public key');
  const other = await crypto.subtle.importKey('raw', theirs as BufferSource, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const bits = await crypto.subtle.deriveBits({ name: 'ECDH', public: other }, privateKey, 256);
  const ikm = await crypto.subtle.importKey('raw', bits, 'HKDF', false, ['deriveKey']);
  const salt = new Uint8Array(extensionPublic.length + phonePublic.length);
  salt.set(extensionPublic);
  salt.set(phonePublic, extensionPublic.length);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt, info: enc.encode(INFO) },
    ikm,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/** nonce (12) || ciphertext and tag, with "OpenTune share v1/<purpose>" as associated data. */
export async function shareSeal(key: CryptoKey, plain: Uint8Array, purpose: string): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(`${INFO}/${purpose}`) }, key, plain as BufferSource),
  );
  const all = new Uint8Array(12 + sealed.length);
  all.set(iv);
  all.set(sealed, 12);
  return all;
}

/** Throws unless [packet] was sealed under [key] for [purpose]. */
export async function shareOpen(key: CryptoKey, packet: Uint8Array, purpose: string): Promise<Uint8Array> {
  if (packet.length < 29 || packet.length > 65536) throw new Error('Not a sealed packet');
  return new Uint8Array(
    await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: packet.slice(0, 12), additionalData: enc.encode(`${INFO}/${purpose}`) },
      key,
      packet.slice(12),
    ),
  );
}

/** The phone's event: its one-use public key [k] and the sealed sign-ins [s], both base64url. */
export interface SharePacket {
  k: string;
  s: string;
}

export function sharePacketOf(content: string): SharePacket | null {
  try {
    const o = JSON.parse(content) as Partial<SharePacket>;
    return typeof o.k === 'string' && typeof o.s === 'string' && o.k.length <= 128 && o.s.length <= 32768 ? { k: o.k, s: o.s } : null;
  } catch {
    return null;
  }
}

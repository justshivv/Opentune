import { aesKey, gcmOpen, gcmSeal, toHex } from '@/together/crypto';

const enc = new TextEncoder();
export const WEB_PLAYER = 'https://justshivv.github.io/Opentune/player/';
const ENCODED = /^[A-Za-z0-9_-]{43}$/;

export const toBase64Url = (b: Uint8Array) => btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
export function fromBase64Url(s: string): Uint8Array {
  const padded = s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

/** HKDF-SHA256 (RFC 5869) with an empty salt, as ConnectGroup.hkdf on the phone. */
export async function hkdf(ikm: Uint8Array, info: string, length: number): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey('raw', ikm as BufferSource, 'HKDF', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(), info: enc.encode(info) }, base, length * 8);
  return new Uint8Array(bits);
}

/**
 * A person's own devices, as ConnectGroup.kt: one secret, held by each of
 * them, names the group on the relays and encrypts what it says there.
 */
export class ConnectGroup {
  private constructor(
    readonly secret: Uint8Array,
    readonly tag: string,
    private readonly key: CryptoKey,
  ) {}

  static async of(secret: Uint8Array): Promise<ConnectGroup> {
    if (secret.length !== 32) throw new Error('A group secret is 32 bytes');
    const tag = toHex(await hkdf(secret, 'opentune/connect/tag', 16));
    return new ConnectGroup(secret, tag, await aesKey(await hkdf(secret, 'opentune/connect/key', 32)));
  }

  static generate(): Promise<ConnectGroup> {
    return ConnectGroup.of(crypto.getRandomValues(new Uint8Array(32)));
  }

  /** The bare secret from a link or the text itself; null for anything else. */
  static parse(text: string | null | undefined): string | null {
    const value = text?.trim();
    if (!value) return null;
    let raw: string;
    if (value.startsWith('opentune://connect/#')) raw = value.slice('opentune://connect/#'.length);
    else if (value.startsWith(WEB_PLAYER)) raw = /[?&#](?:join|connect)=([A-Za-z0-9_-]{43})/.exec(value)?.[1] ?? '';
    else if (value.includes('://')) return null;
    else raw = value;
    return ENCODED.test(raw) ? raw : null;
  }

  static async fromText(text: string | null | undefined): Promise<ConnectGroup | null> {
    const raw = ConnectGroup.parse(text);
    return raw ? ConnectGroup.of(fromBase64Url(raw)) : null;
  }

  get encoded(): string {
    return toBase64Url(this.secret);
  }
  get appLink(): string {
    return `opentune://connect/#${this.encoded}`;
  }
  get webLink(): string {
    return `${WEB_PLAYER}#/settings/devices?join=${this.encoded}`;
  }

  seal(plain: string): Promise<string> {
    return gcmSeal(this.key, this.tag, plain);
  }
  open(sealed: string): Promise<string | null> {
    return gcmOpen(this.key, this.tag, sealed);
  }
}

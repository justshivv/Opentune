import { schnorr, secp256k1 } from '@noble/curves/secp256k1.js';

/*
 * The Android app's room crypto (RoomCode.kt, Schnorr.kt), byte for byte, so
 * a browser and a phone meet in the same room. A 12-character code names the
 * room; the relays' tag and the lobby key both come from it. Inside, messages
 * travel under a random room key the host hands to each person it lets in.
 */

/** No 0/O or 1/I, so a code read aloud or off a screen can't be misread. */
export const ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
export const CODE_LENGTH = 12;
const ITERATIONS = 40_000;
const enc = new TextEncoder();
const dec = new TextDecoder();

export const toHex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
export function fromHex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = Number.parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}
const toBase64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
const fromBase64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export async function sha256(data: Uint8Array | string): Promise<Uint8Array> {
  const bytes = typeof data === 'string' ? enc.encode(data) : data;
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource));
}

/** AES-GCM: base64(iv || ciphertext), with [tag] as associated data. */
async function gcmSeal(key: CryptoKey, tag: string, plain: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: enc.encode(tag) }, key, enc.encode(plain)));
  const all = new Uint8Array(12 + sealed.length);
  all.set(iv);
  all.set(sealed, 12);
  return toBase64(all);
}

async function gcmOpen(key: CryptoKey, tag: string, sealed: string): Promise<string | null> {
  try {
    const all = fromBase64(sealed);
    if (all.length < 12 + 16) return null;
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.slice(0, 12), additionalData: enc.encode(tag) }, key, all.slice(12));
    return dec.decode(plain);
  } catch {
    return null;
  }
}

const aesKey = (raw: Uint8Array) => crypto.subtle.importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);

export class RoomCode {
  private constructor(
    readonly raw: string,
    /** What relays index the room's events by; reveals nothing about the code. */
    readonly tag: string,
    private readonly key: CryptoKey,
  ) {}

  /** "K7QX-M2PA-9DTE" */
  get pretty(): string {
    return this.raw.match(/.{4}/g)!.join('-');
  }
  /** The website's invite page, which opens the room in the app or here. */
  get link(): string {
    return `https://justshivv.github.io/Opentune/together/#code=${this.raw}`;
  }

  seal(plain: string) {
    return gcmSeal(this.key, this.tag, plain);
  }
  open(sealed: string) {
    return gcmOpen(this.key, this.tag, sealed);
  }

  /** Derives the tag and the lobby key: PBKDF2, stretched so the code can't be guessed quickly from the tag. */
  static async of(raw: string): Promise<RoomCode> {
    const tag = toHex(await sha256(`opentune/together/tag/${raw}`)).slice(0, 32);
    const base = await crypto.subtle.importKey('raw', enc.encode(raw), 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits(
      { name: 'PBKDF2', hash: 'SHA-256', salt: enc.encode('opentune/together/key'), iterations: ITERATIONS },
      base,
      256,
    );
    return new RoomCode(raw, tag, await aesKey(new Uint8Array(bits)));
  }

  static generate(): string {
    const pick = crypto.getRandomValues(new Uint32Array(CODE_LENGTH));
    return Array.from(pick, (n) => ALPHABET[n % ALPHABET.length]).join('');
  }

  /** A code typed or pasted in any form ("k7qx m2pa-9dte", or inside an invite link); null if it isn't one. */
  static parse(text: string | null | undefined): string | null {
    if (!text) return null;
    let value = text.trim();
    const link = value.match(/^(?:https:\/\/justshivv\.github\.io\/Opentune\/together\/?#code=|opentune:\/\/together\/)([A-Za-z0-9-]+)/i);
    if (link) value = link[1]!;
    else if (/^[a-z]+:\/\//i.test(value)) return null;
    if (!/^[A-Za-z0-9\s-]+$/.test(value)) return null;
    const cleaned = value.toUpperCase().replace(/[^A-Z0-9]/g, '');
    return cleaned.length === CODE_LENGTH && [...cleaned].every((c) => ALPHABET.includes(c)) ? cleaned : null;
  }
}

/** The key a room's own messages travel under once someone is let in. */
export class RoomKey {
  private constructor(
    private readonly bytes: Uint8Array,
    private readonly tag: string,
    private readonly key: CryptoKey,
  ) {}

  static async random(tag: string): Promise<RoomKey> {
    const bytes = crypto.getRandomValues(new Uint8Array(32));
    return new RoomKey(bytes, tag, await aesKey(bytes));
  }

  seal(plain: string) {
    return gcmSeal(this.key, this.tag, plain);
  }
  open(sealed: string) {
    return gcmOpen(this.key, this.tag, sealed);
  }

  /** This key, sealed so only the holder of the private key matching [theirPublic] can open it. */
  async wrapFor(myPrivate: Uint8Array, theirPublic: string): Promise<string | null> {
    const shared = sharedSecret(myPrivate, theirPublic);
    if (!shared) return null;
    return gcmSeal(await admitKey(shared), this.tag, toBase64(this.bytes));
  }

  /** A key [wrapFor] sealed for us, from the host whose public key is [theirPublic]. */
  static async unwrap(wrapped: string, myPrivate: Uint8Array, theirPublic: string, tag: string): Promise<RoomKey | null> {
    const shared = sharedSecret(myPrivate, theirPublic);
    if (!shared) return null;
    const plain = await gcmOpen(await admitKey(shared), tag, wrapped);
    if (!plain) return null;
    try {
      const bytes = fromBase64(plain);
      return bytes.length === 32 ? new RoomKey(bytes, tag, await aesKey(bytes)) : null;
    } catch {
      return null;
    }
  }
}

async function admitKey(shared: Uint8Array): Promise<CryptoKey> {
  const label = enc.encode('opentune/together/admit');
  const both = new Uint8Array(label.length + shared.length);
  both.set(label);
  both.set(shared, label.length);
  return aesKey(await sha256(both));
}

/**
 * The x coordinate of [privateKey] times the point whose x is [publicKeyHex]
 * (ECDH on secp256k1), as Schnorr.sharedSecret: both sides get the same 32
 * bytes, whichever y each key's point really has.
 */
export function sharedSecret(privateKey: Uint8Array, publicKeyHex: string): Uint8Array | null {
  try {
    if (publicKeyHex.length !== 64) return null;
    const point = secp256k1.getSharedSecret(privateKey, fromHex(`02${publicKeyHex}`), true);
    return point.slice(1, 33);
  } catch {
    return null;
  }
}

export const newPrivateKey = () => secp256k1.utils.randomSecretKey();
export const publicKeyOf = (privateKey: Uint8Array) => toHex(schnorr.getPublicKey(privateKey));
export const signId = (id: Uint8Array, privateKey: Uint8Array) => toHex(schnorr.sign(id, privateKey));

import type { DiscordState } from '@opentune/shared/bridge';
import { fromBase64Url, SHARE_TTL_MS, shareKey, shareOpen, shareSeal, toBase64Url } from '@opentune/shared/share';
import { AVAILABLE, DISCORD_ORIGINS, discordState, keepToken } from './discord';

/**
 * "Sign in with your phone" for Discord (the phone's WebShare.kt). The web page shows a
 * code with a one-use public key made here; the private half stays in this extension's
 * session storage (memory only, never on disk, unreadable by web pages) until the code is
 * used or runs out. The phone's sealed packet reaches here through the page unopened, so
 * the token is seen only here, as when it's read from a discord.com tab.
 */

const OFFER_KEY = 'discord.phoneOffer';
const TOKEN = /^[A-Za-z0-9._-]{20,512}$/;
const enc = new TextEncoder();
const dec = new TextDecoder();

interface Held {
  privateKey: JsonWebKey;
  publicKey: string;
  expires: number;
}

const refused = (message: string) => Object.assign(new Error(message), { code: 'forbidden' });

const access = () => browser.permissions.contains({ origins: DISCORD_ORIGINS }).catch(() => false);

/** A fresh code's key; making one forgets any earlier code. */
export async function phoneOffer(): Promise<{ publicKey: string; expires: number; access: boolean }> {
  if (!AVAILABLE) throw refused('This build of the extension leaves Discord out.');
  const pair = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const publicKey = toBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)));
  const held: Held = { privateKey: await crypto.subtle.exportKey('jwk', pair.privateKey), publicKey, expires: Date.now() + SHARE_TTL_MS };
  await browser.storage.session.set({ [OFFER_KEY]: held });
  return { publicKey, expires: held.expires, access: await access() };
}

/**
 * Opens what the phone sealed for the current code and keeps its Discord account. Anything
 * not sealed for this code is refused and the code stays, for the real one; the first that
 * opens uses the code up.
 */
export async function phoneAccept(phoneKey: string, sealed: string): Promise<{ state: DiscordState; answer: string; took: string[] }> {
  const held = (await browser.storage.session.get(OFFER_KEY))[OFFER_KEY] as Held | undefined;
  if (!held || held.expires <= Date.now()) throw refused('That code has run out. Make a new one.');
  const privateKey = await crypto.subtle.importKey('jwk', held.privateKey, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const extension = fromBase64Url(held.publicKey);
  let key: CryptoKey;
  let plain: Uint8Array;
  try {
    const phone = fromBase64Url(phoneKey);
    key = await shareKey(privateKey, phone, extension, phone);
    plain = await shareOpen(key, fromBase64Url(sealed), 'extras');
  } catch {
    throw refused('That wasn’t sealed for this code.');
  }
  await browser.storage.session.remove(OFFER_KEY);
  let token: unknown;
  try {
    token = (JSON.parse(dec.decode(plain)) as { discord?: { token?: unknown } }).discord?.token;
  } catch {
    token = null;
  } finally {
    plain.fill(0);
  }
  let took: string[] = [];
  let state: DiscordState;
  if (typeof token === 'string' && TOKEN.test(token) && (await access())) {
    try {
      state = await keepToken(token);
      took = ['discord'];
    } catch {
      state = await discordState();
    }
  } else {
    state = await discordState();
  }
  const answer = toBase64Url(await shareSeal(key, enc.encode(took.join(',')), 'ack'));
  return { state, answer, took };
}

/** The connect page in its "allow discord.com" form: the browser asks only after a click there. */
export async function openAccessPage(): Promise<void> {
  await browser.tabs.create({ url: browser.runtime.getURL('/discord.html?for=phone') });
}

import type { Bridge, BridgeMethod, ParamsOf, ResultOf } from '@opentune/shared/bridge';
import { fromBase64Url, SHARE_KIND, shareKey, shareOpen, shareSeal, shareTag, toBase64Url } from '@opentune/shared/share';
import { describe, expect, it, vi } from 'vitest';
import type { NostrEvent } from '@/together/nostr';
import type { TransportFactory } from '@/together/session';
import { ANSWER_TIMES, startPhoneSignIn, type PhoneSignInState } from './phoneSignIn';

const enc = new TextEncoder();
const dec = new TextDecoder();

// Made with Node's own ECDH, HKDF and AES-GCM (not WebCrypto); core's WebShareTest checks the same vector.
const VECTOR = {
  extensionPrivate: {
    kty: 'EC',
    crv: 'P-256',
    d: 'Hx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8fHx8',
    x: 'a_hdX-hFmLEMphmewJzNfTXc1xlf3R3Hc3oMZwBu8lE',
    y: 'uVLyXuwd7tH8oZGDiZBGY1fTFK37fpdguNmvyPOala0',
  } satisfies JsonWebKey,
  extensionPublic: 'BGv4XV_oRZixDKYZnsCczX013NcZX90dx3N6DGcAbvJRuVLyXuwd7tH8oZGDiZBGY1fTFK37fpdguNmvyPOala0',
  phonePublic: 'BAObhS22IkCKvlihjA8FZjGmykss_uwZiq4lAXytCdTo4gi2FuDcV3Wl2EB3XTja_UZ22jQQAhXovoV77SukrDA',
  packet: 'AAECAwQFBgcICQoL9IFTbjdwCa2uB2cP0NdrBfDh8Fa786PIOf-cfDoGDfN8UpSFnOP49TxUCF2udYz91gmSprhG_CAOce1Ea4O4ncck83sdR0Przc1Gjg',
  plain: '{"discord":{"token":"mfa.TestTokenOnly_notReal-1234567890"}}',
};

const ecdh = { name: 'ECDH', namedCurve: 'P-256' } as const;

describe('share crypto', () => {
  it('opens what the phone sealed, as Node and the phone make it', async () => {
    const privateKey = await crypto.subtle.importKey('jwk', VECTOR.extensionPrivate, ecdh, false, ['deriveBits']);
    const ext = fromBase64Url(VECTOR.extensionPublic);
    const phone = fromBase64Url(VECTOR.phonePublic);
    const key = await shareKey(privateKey, phone, ext, phone);
    expect(dec.decode(await shareOpen(key, fromBase64Url(VECTOR.packet), 'extras'))).toBe(VECTOR.plain);
    await expect(shareOpen(key, fromBase64Url(VECTOR.packet), 'ack')).rejects.toThrow();
    const tampered = fromBase64Url(VECTOR.packet);
    tampered[20]! ^= 1;
    await expect(shareOpen(key, tampered, 'extras')).rejects.toThrow();
  });
});

/** The extension's half, as lib/discordPhone.ts does it, behind a bridge. */
async function fakeExtension(onAccept: () => void = () => undefined) {
  const pair = await crypto.subtle.generateKey(ecdh, true, ['deriveBits']);
  const publicKey = toBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)));
  const accepted: string[] = [];
  const bridge = {
    async call<M extends BridgeMethod>(method: M, params: ParamsOf<M>): Promise<ResultOf<M>> {
      if (method === 'discord.phoneOffer') return { publicKey, expires: Date.now() + 120_000, access: true } as ResultOf<M>;
      if (method !== 'discord.phoneAccept') throw new Error(method);
      const { phoneKey, sealed } = params as ParamsOf<'discord.phoneAccept'>;
      const phone = fromBase64Url(phoneKey);
      const key = await shareKey(pair.privateKey, phone, fromBase64Url(publicKey), phone);
      const plain = JSON.parse(dec.decode(await shareOpen(key, fromBase64Url(sealed), 'extras'))) as { discord: { token: string } };
      accepted.push(plain.discord.token);
      // The real one says Discord is connected (discord.state) before it answers this call.
      onAccept();
      const answer = toBase64Url(await shareSeal(key, enc.encode('discord'), 'ack'));
      const state = { available: true, connected: true, enabled: true, user: { name: 'Wren Calloway', username: 'wrencalloway', avatarUrl: null }, gateway: 'off', problem: null };
      return { state, answer, took: ['discord'] } as ResultOf<M>;
    },
  } as unknown as Bridge;
  return { bridge, publicKey, accepted };
}

describe('startPhoneSignIn', () => {
  it('shows a code, hands the phone’s packet to the extension unopened and answers the phone', async () => {
    const { bridge, publicKey, accepted } = await fakeExtension();
    let deliver: ((e: NostrEvent) => void) | null = null;
    let filter: Record<string, unknown> = {};
    const published: NostrEvent[] = [];
    const transport: TransportFactory = (f, onEvent) => {
      filter = f;
      deliver = onEvent;
      return { start: () => undefined, publish: (e) => (published.push(e), true), close: () => undefined };
    };
    const states: PhoneSignInState[] = [];
    const stop = startPhoneSignIn(bridge, (s) => states.push(s), transport);
    await vi.waitFor(() => expect(states.at(-1)?.step).toBe('code'));
    const tag = await shareTag(publicKey);
    expect(filter['#t']).toEqual([tag]);
    expect((states.at(-1) as { link: string }).link.startsWith('opentune://pair/web#')).toBe(true);

    // The phone's side (WebShare.kt).
    const phone = await crypto.subtle.generateKey(ecdh, true, ['deriveBits']);
    const phonePublic = new Uint8Array(await crypto.subtle.exportKey('raw', phone.publicKey));
    const key = await shareKey(phone.privateKey, fromBase64Url(publicKey), fromBase64Url(publicKey), phonePublic);
    const sealed = await shareSeal(key, enc.encode('{"discord":{"token":"mfa.NotARealToken_0123456789-abcdef"}}'), 'extras');
    const event = (content: string, id: string): NostrEvent => ({ id, pubkey: '00', created_at: 0, kind: SHARE_KIND, tags: [['t', tag]], content, sig: '00' });
    // Something not sealed for this code first: refused, and the real one still gets through.
    deliver!(event(JSON.stringify({ k: toBase64Url(phonePublic), s: toBase64Url(sealed.slice(0, 40)) }), 'bogus'));
    deliver!(event(JSON.stringify({ k: toBase64Url(phonePublic), s: toBase64Url(sealed) }), 'real'));

    await vi.waitFor(() => expect(published.length).toBeGreaterThan(0));
    expect(states.at(-1)).toEqual({ step: 'done', name: 'Wren Calloway' });
    expect(accepted).toEqual(['mfa.NotARealToken_0123456789-abcdef']);
    const reply = published[0]!;
    expect(reply.tags).toEqual([['t', tag]]);
    expect(reply.content).not.toContain('NotARealToken');
    const a = (JSON.parse(reply.content) as { a: string }).a;
    expect(dec.decode(await shareOpen(key, fromBase64Url(a), 'ack'))).toBe('discord');
    stop();
  });

  it('still answers the phone when the page stops it as Discord connects', async () => {
    // The Discord page shows the code only while Discord isn't connected: the extension's
    // "connected" reaches it during phoneAccept, and it unmounts (and stops) at once.
    let stop: () => void = () => undefined;
    const { bridge, publicKey } = await fakeExtension(() => stop());
    let deliver: ((e: NostrEvent) => void) | null = null;
    const published: NostrEvent[] = [];
    let closed = 0;
    const transport: TransportFactory = (_f, onEvent) => {
      deliver = onEvent;
      return { start: () => undefined, publish: (e) => (closed ? false : (published.push(e), true)), close: () => void closed++ };
    };
    const states: PhoneSignInState[] = [];
    stop = startPhoneSignIn(bridge, (s) => states.push(s), transport, 5);
    await vi.waitFor(() => expect(states.at(-1)?.step).toBe('code'));
    const tag = await shareTag(publicKey);

    const phone = await crypto.subtle.generateKey(ecdh, true, ['deriveBits']);
    const phonePublic = new Uint8Array(await crypto.subtle.exportKey('raw', phone.publicKey));
    const key = await shareKey(phone.privateKey, fromBase64Url(publicKey), fromBase64Url(publicKey), phonePublic);
    const sealed = await shareSeal(key, enc.encode('{"discord":{"token":"mfa.NotARealToken_0123456789-abcdef"}}'), 'extras');
    deliver!({ id: 'real', pubkey: '00', created_at: 0, kind: SHARE_KIND, tags: [['t', tag]], content: JSON.stringify({ k: toBase64Url(phonePublic), s: toBase64Url(sealed) }), sig: '00' });

    // Sent every time, then the relays are let go.
    await vi.waitFor(() => expect(closed).toBe(1));
    expect(published).toHaveLength(ANSWER_TIMES);
    const a = (JSON.parse(published[0]!.content) as { a: string }).a;
    expect(dec.decode(await shareOpen(key, fromBase64Url(a), 'ack'))).toBe('discord');
    expect(published.every((e) => !e.content.includes('NotARealToken') && e.tags[0]![1] === tag)).toBe(true);
    // Stopped, it showed nothing more.
    expect(states.at(-1)?.step).toBe('code');
  });

  it('lets the relays go at once when stopped before any sign-in came', async () => {
    const { bridge } = await fakeExtension();
    let closed = 0;
    const transport: TransportFactory = () => ({ start: () => undefined, publish: () => true, close: () => void closed++ });
    const states: PhoneSignInState[] = [];
    const stop = startPhoneSignIn(bridge, (s) => states.push(s), transport);
    await vi.waitFor(() => expect(states.at(-1)?.step).toBe('code'));
    stop();
    expect(closed).toBe(1);
  });
});

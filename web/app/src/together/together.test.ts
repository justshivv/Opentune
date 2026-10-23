import { schnorr } from '@noble/curves/secp256k1.js';
import { describe, expect, it } from 'vitest';
import { fromHex, newPrivateKey, publicKeyOf, RoomCode, RoomKey, sha256, sharedSecret, toHex } from './crypto';
import { signedEvent, type NostrEvent } from './nostr';
import { RoomSession, type RoomPlayer, type RoomTrack, type RoomView, type TransportFactory } from './session';

describe('room code, as the Android app makes it', () => {
  // Made in Python with hashlib and cryptography, the same calls as RoomCode.kt's javax.crypto:
  // sha256 for the tag, PBKDF2-HMAC-SHA256 (40,000 rounds) for the key, AES-GCM with the tag as AAD.
  const raw = 'K7QXM2PA9DTE';
  const sealedByReference = 'AAECAwQFBgcICQoLr+cnvq3jy9G0za8UGeVbKJ2Ex6m4J8Mazcub6DOU10qMSbr54eiwc/L4UGU=';

  it('derives the same tag and opens a message sealed the app’s way', async () => {
    const code = await RoomCode.of(raw);
    expect(code.tag).toBe('4d4a7a8a49af34837ab15ef879389ba5');
    expect(await code.open(sealedByReference)).toBe('{"t":"hello","name":"Phone"}');
    expect(await code.open(await code.seal('back again'))).toBe('back again');
    expect(await (await RoomCode.of('23456789ABCD')).open(sealedByReference)).toBeNull();
  });

  it('reads a code in any form people paste it', () => {
    expect(RoomCode.parse('k7qx m2pa-9dte')).toBe(raw);
    expect(RoomCode.parse(`https://justshivv.github.io/Opentune/together/#code=${raw}`)).toBe(raw);
    expect(RoomCode.parse(`opentune://together/${raw}`)).toBe(raw);
    expect(RoomCode.parse('K7QX-M2PA-9DT0')).toBeNull(); // 0 isn't in the alphabet
    expect(RoomCode.parse('https://example.com/K7QXM2PA9DTE')).toBeNull();
    expect(RoomCode.parse(RoomCode.generate())).toHaveLength(12);
  });
});

describe('keys', () => {
  it('agrees on one shared secret from either side', () => {
    const a = newPrivateKey();
    const b = newPrivateKey();
    const ab = sharedSecret(a, publicKeyOf(b));
    expect(ab).toHaveLength(32);
    expect(toHex(ab!)).toBe(toHex(sharedSecret(b, publicKeyOf(a))!));
  });

  it('hands the room key to one guest only', async () => {
    const host = newPrivateKey();
    const guest = newPrivateKey();
    const other = newPrivateKey();
    const key = await RoomKey.random('tag');
    const wrapped = (await key.wrapFor(host, publicKeyOf(guest)))!;
    const opened = await RoomKey.unwrap(wrapped, guest, publicKeyOf(host), 'tag');
    expect(await opened!.open(await key.seal('in the room'))).toBe('in the room');
    expect(await RoomKey.unwrap(wrapped, other, publicKeyOf(host), 'tag')).toBeNull();
  });

  it('signs events relays accept (NIP-01 id, BIP-340 signature)', async () => {
    const key = newPrivateKey();
    const content = 'quotes " back\\slash \n\t\b\f \u0001 and 🎵';
    const e = await signedEvent(key, 21420, [['t', 'abc']], content);
    // NIP-01's own escaping, written out as the app's NostrEvent.serialize does it.
    const quote = (s: string) => `"${[...s].map((c) => ({ '"': '\\"', '\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t', '\b': '\\b', '\f': '\\f' } as Record<string, string>)[c] ?? (c < ' ' ? `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}` : c)).join('')}"`;
    const serialized = `[0,"${e.pubkey}",${e.created_at},21420,[["t","abc"]],${quote(content)}]`;
    expect(e.id).toBe(toHex(await sha256(serialized)));
    expect(schnorr.verify(fromHex(e.sig), fromHex(e.id), fromHex(e.pubkey))).toBe(true);
  });
});

/** A relay that hands every event to everyone, a moment later. */
function bus(): TransportFactory {
  const listeners = new Set<(e: NostrEvent) => void>();
  return (_filter, onEvent, onConnected) => ({
    start: () => { listeners.add(onEvent); setTimeout(() => onConnected(1), 0); },
    publish: (e) => { listeners.forEach((l) => setTimeout(() => l(e), 5)); return true; },
    close: () => listeners.delete(onEvent),
  });
}

function fakePlayer(track: RoomTrack | null = null) {
  const s = { track, upcoming: [] as RoomTrack[], playing: false, pos: 0, loads: 0 };
  const player: RoomPlayer = {
    currentId: () => s.track?.id ?? null,
    current: () => s.track,
    upcoming: () => s.upcoming,
    upcomingIds: () => s.upcoming.map((t) => t.id),
    playing: () => s.playing,
    buffering: () => false,
    positionMs: () => s.pos,
    setRate: () => undefined,
    play: () => { s.playing = true; },
    pause: () => { s.playing = false; },
    seekTo: (ms) => { s.pos = ms; },
    load: (tracks, startMs, play) => { s.loads++; s.track = tracks[0] ?? null; s.upcoming = tracks.slice(1); s.pos = startMs; s.playing = play; },
    replaceUpcoming: (tracks) => { s.upcoming = tracks; },
    addToQueue: (t) => { s.upcoming = [...s.upcoming, t]; },
    playNow: (t) => { s.track = t; },
    skipNext: () => undefined,
    skipPrevious: () => undefined,
  };
  return { s, player };
}

const until = async (check: () => boolean, ms = 8_000) => {
  const end = Date.now() + ms;
  while (!check()) {
    if (Date.now() > end) throw new Error('timed out');
    await new Promise((r) => setTimeout(r, 50));
  }
};

describe('a room', () => {
  it('lets a guest knock, be let in and follow the host’s song', async () => {
    const code = await RoomCode.of(RoomCode.generate());
    const transport = bus();
    const song = { id: 'dQw4w9WgXcQ', title: 'Song', artist: 'Artist', thumb: null, length: '3:33', album: null };
    const host = fakePlayer(song);
    host.s.playing = true;
    host.s.pos = 42_000;
    const guest = fakePlayer();
    let hostView: RoomView | null = null;
    let guestView: RoomView | null = null;
    const hosting = new RoomSession(code, 'Host', true, host.player, (v) => (hostView = v), () => undefined, true, transport);
    const joining = new RoomSession(code, 'Guest', false, guest.player, (v) => (guestView = v), () => undefined, true, transport);
    await hosting.start();
    await joining.start();

    await until(() => (hostView?.requests.length ?? 0) > 0 && guestView?.phase === 'waiting');
    hosting.approve(hostView!.requests[0]!.id);

    await until(() => guestView?.phase === 'live' && guest.s.track?.id === song.id);
    expect(guest.s.playing).toBe(true);
    expect(guest.s.pos).toBeGreaterThan(41_000);
    expect(hostView!.members.map((m) => m.name)).toEqual(['Host', 'Guest']);

    // A guest without control can't skip; its pick goes to the host's queue.
    expect(joining.intercept({ kind: 'next' })).toBe(true);
    joining.suggest({ ...song, id: 'aaaaaaaaaaa', title: 'Picked' }, false);
    await until(() => host.s.upcoming.some((t) => t.title === 'Picked'));

    joining.say('hi');
    await until(() => hostView!.chat.some((l) => l.text === 'hi'));

    hosting.stop(true);
    await until(() => guestView?.phase === 'ended');
    joining.stop(false);
  }, 20_000);
});

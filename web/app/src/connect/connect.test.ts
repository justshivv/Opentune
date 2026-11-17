import { describe, expect, it } from 'vitest';
import { toHex } from '@/together/crypto';
import type { NostrEvent } from '@/together/nostr';
import type { TransportFactory } from '@/together/session';
import { ConnectGroup, fromBase64Url, hkdf } from './group';
import { decode, encode, fit, MAX_QUEUE, MAX_SEALED, type CTrack } from './messages';
import { ConnectSession, type ConnectPlayer, type ConnectView, type Snapshot, type Timings } from './session';
import vectors from './vectors.json';

describe('group key, as the phone makes it', () => {
  it('matches the RFC and the shared vectors', async () => {
    expect(toHex(await hkdf(new Uint8Array(22).fill(0x0b), '', 42))).toBe(
      '8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8',
    );
    const group = await ConnectGroup.of(fromBase64Url(vectors.secret));
    expect(group.encoded).toBe(vectors.secret);
    expect(group.tag).toBe(vectors.tag);
    expect(await group.open(vectors.sealed)).toBe(vectors.plain);
    expect(await group.open(await group.seal('back'))).toBe('back');
    expect(await (await ConnectGroup.generate()).open(vectors.sealed)).toBeNull();
  });

  it('reads links and the bare secret', async () => {
    const g = await ConnectGroup.generate();
    expect(ConnectGroup.parse(g.appLink)).toBe(g.encoded);
    expect(ConnectGroup.parse(g.webLink)).toBe(g.encoded);
    expect(ConnectGroup.parse(` ${g.encoded} `)).toBe(g.encoded);
    expect(ConnectGroup.parse(`https://example.com/#/settings/devices?join=${g.encoded}`)).toBeNull();
    expect(ConnectGroup.parse(g.encoded.slice(1))).toBeNull();
  });
});

describe('messages, as the phone writes them', () => {
  it('reads the pinned format and drops strangers', () => {
    expect(decode('{"type":"command","from":"a","seq":1,"at":2,"to":"b","id":"c","action":"seek","pos":5000}')).toEqual({
      type: 'command', from: 'a', seq: 1, at: 2, to: 'b', id: 'c', action: 'seek', pos: 5000,
    });
    expect(decode('{"type":"dance","from":"a","seq":1,"at":2}')).toBeNull();
    expect(decode('not json')).toBeNull();
    expect(encode({ type: 'ack', from: 'a', seq: 1, at: 2, to: 'b', id: 'c' })).toBe('{"type":"ack","from":"a","seq":1,"at":2,"to":"b","id":"c"}');
  });

  it('cuts a long queue to fit', async () => {
    const group = await ConnectGroup.generate();
    const long: CTrack = { id: 'dQw4w9WgXcQ', title: 'x'.repeat(300), artist: 'y'.repeat(300) };
    const sealed = await fit({ type: 'state', from: 'a', seq: 1, at: 2, track: long, playing: true, queue: Array(1000).fill(long) }, group);
    expect(sealed.length).toBeLessThanOrEqual(MAX_SEALED);
    const back = decode((await group.open(sealed))!);
    expect(back?.type === 'state' && back.queue!.length).toBeGreaterThan(0);
    expect(back?.type === 'state' && back.queue!.length).toBeLessThanOrEqual(MAX_QUEUE);
  });
});

// ---- Sessions against each other ---------------------------------------------------------------

function bus(): TransportFactory {
  const listeners = new Set<(e: NostrEvent) => void>();
  return (_filter, onEvent, onConnected) => ({
    start: () => { listeners.add(onEvent); setTimeout(() => onConnected(1), 0); },
    publish: (e) => { listeners.forEach((l) => setTimeout(() => l(e), 2)); return true; },
    close: () => listeners.delete(onEvent),
  });
}

const fast: Timings = { presenceMs: 200, goneMs: 700, stateMs: 100, commandMs: 400, transferMs: 400, tickMs: 20 };
const track = (c: string): CTrack => ({ id: c.repeat(11), title: `Song ${c}`, artist: 'Artist' });

function fakePlayer(playable = true) {
  const s = { track: null as CTrack | null, queue: [] as CTrack[], playing: false, pos: 0, volume: 1, calls: [] as string[] };
  const player: ConnectPlayer = {
    snapshot: (): Snapshot | null => (s.track ? { track: s.track, private: false, playing: s.playing, pos: s.pos, queue: s.queue, volume: s.volume, shuffle: false, repeat: 'off' } : null),
    play: () => { s.calls.push('play'); s.playing = true; },
    pause: () => { s.calls.push('pause'); s.playing = false; },
    next: () => { s.calls.push('next'); },
    previous: () => { s.calls.push('previous'); },
    seekTo: (ms) => { s.calls.push(`seek ${ms}`); s.pos = ms; },
    setVolume: (v) => { s.calls.push(`volume ${v}`); s.volume = v; },
    playTrack: (t) => { s.track = t; s.playing = true; },
    addToQueue: (ts) => { s.queue = [...s.queue, ...ts]; },
    load: (queue, index, pos, playing) => {
      s.calls.push(`load ${queue.length}`);
      if (!playable) return false;
      s.track = queue[index]!; s.queue = queue.slice(index + 1); s.pos = pos; s.playing = playing;
      return true;
    },
  };
  return { s, player };
}

async function eventually(what: string, check: () => boolean, ms = 3_000): Promise<void> {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (check()) return;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error(`never: ${what}`);
}

async function devices(...names: [string, string, boolean?][]) {
  const group = await ConnectGroup.generate();
  const transport = bus();
  return names.map(([id, name, playable]) => {
    const { s, player } = fakePlayer(playable ?? true);
    const d = { id, s, view: null as ConnectView | null, notices: [] as string[], movedTo: [] as string[], session: null as unknown as ConnectSession };
    d.session = new ConnectSession(group, id, name, 'web', player, (v) => { d.view = v; }, (t, n) => d.notices.push(t.replace('{0}', n)), fast, transport, undefined, (secret) => d.movedTo.push(secret));
    d.session.start();
    return d;
  });
}

const play = (d: Awaited<ReturnType<typeof devices>>[number], t: CTrack, pos = 0) => {
  d.s.track = t; d.s.playing = true; d.s.pos = pos;
  d.session.localChanged();
};

describe('sessions', () => {
  it('find each other, follow the one playing and act on it', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    await eventually('see each other', () => a!.view?.devices.length === 1 && b!.view?.devices.length === 1);
    play(a!, track('x'));
    await eventually('b shows a', () => b!.view?.activeId === 'a' && b!.view.devices[0]!.state?.track?.id === track('x').id);
    expect(b!.session.intercept('pause')).toBe(true);
    await eventually('a paused', () => a!.s.calls.includes('pause'));
    await eventually('answered', () => b!.view!.pending.length === 0);
    expect(b!.notices).toEqual([]);
    expect(a!.session.intercept('pause')).toBe(false);
    a!.session.stop(); b!.session.stop();
  });

  it('hears a restarted device again', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    // b has said a lot before it goes, more than a new b could catch up with by counting from 0.
    (b!.session as unknown as { seq: number }).seq = 1e9;
    b!.session.rename('Chrome');
    await eventually('see each other', () => a!.view?.devices.length === 1);
    await new Promise((r) => setTimeout(r, 300));
    b!.session.stop();
    await eventually('a forgets b', () => a!.view!.devices.length === 0);
    await new Promise((r) => setTimeout(r, 20));
    const again = new ConnectSession(b!.session.group, 'b', 'Chrome', 'web', fakePlayer(true).player, () => {}, () => {}, fast, (b!.session as unknown as { transport: never }).transport);
    again.start();
    await eventually('a sees the new b', () => a!.view!.devices.length === 1);
    a!.session.stop(); again.stop();
  });

  it('takes the others along when a device joins another group', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    await eventually('see each other', () => a!.view?.devices.length === 1 && b!.view?.devices.length === 1);
    b!.session.stop('the-new-secret');
    await eventually('a follows b', () => a!.movedTo.join() === 'the-new-secret' && a!.view!.devices.length === 0);
    a!.session.stop();
  });

  it('names a device that does not answer, and forgets it', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    play(a!, track('x'));
    await eventually('b controls a', () => b!.view?.activeId === 'a');
    // a stops hearing anything, without saying goodbye.
    (a!.session as unknown as { pool: { close(): void } }).pool.close();
    b!.session.intercept('next');
    await eventually('notice', () => b!.notices.includes("Phone isn't answering"));
    a!.session.stop(); b!.session.stop();
  });

  it('moves playback with its queue and position, and back', async () => {
    const [a, b, c] = await devices(['a', 'Phone'], ['b', 'Chrome'], ['c', 'Laptop']);
    await eventually('all here', () => c!.view?.devices.length === 2);
    a!.s.queue = [track('y')];
    play(a!, track('x'), 60_000);
    await eventually('c sees a', () => c!.view?.activeId === 'a');
    a!.session.transferTo('b');
    await eventually('b plays', () => b!.s.playing && b!.s.track?.id === track('x').id);
    expect(b!.s.queue).toEqual([track('y')]);
    expect(b!.s.pos).toBeGreaterThanOrEqual(60_000);
    await eventually('a stops', () => !a!.s.playing);
    await eventually('c sees b', () => c!.view?.activeId === 'b');
    c!.session.transferTo('c');
    await eventually('c plays, b stops', () => c!.s.playing && !b!.s.playing);
    [a, b, c].forEach((d) => d!.session.stop());
  });

  it('leaves playback where it was when the target cannot play it', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome', false]);
    await eventually('here', () => a!.view?.devices.length === 1);
    play(a!, track('x'));
    a!.session.transferTo('b');
    await eventually('told', () => a!.notices.includes("Can't play this on Chrome"));
    expect(a!.s.playing).toBe(true);
    a!.session.stop(); b!.session.stop();
  });

  it('quiets the other device when something starts here', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    play(a!, track('x'));
    await eventually('b sees a playing', () => b!.view?.devices[0]?.state?.playing === true);
    play(b!, track('z'));
    await eventually('a paused', () => !a!.s.playing);
    await eventually('both show b', () => a!.view?.activeId === 'b' && b!.view?.activeId === 'b');
    a!.session.stop(); b!.session.stop();
  });

  it('ignores old and own messages', async () => {
    const [a] = await devices(['a', 'Phone']);
    a!.session.handle({ type: 'presence', from: 'b', seq: 5, at: 0, name: 'New', form: 'web' });
    a!.session.handle({ type: 'presence', from: 'b', seq: 4, at: 0, name: 'Old', form: 'web' });
    a!.session.handle({ type: 'presence', from: 'a', seq: 9, at: 0, name: 'Me', form: 'web' });
    expect(a!.view?.devices.map((d) => d.name)).toEqual(['New']);
    a!.session.stop();
  });
});

describe('the group kept in the YouTube account', () => {
  it('finds its playlist and reads the secret back', async () => {
    const { description, pick, secretIn } = await import('./accountSync');
    const g = await ConnectGroup.generate();
    expect(secretIn(JSON.stringify({ header: { description: { runs: [{ text: description(g) }] } } }))).toBe(g.encoded);
    expect(secretIn('{"header":{"description":"just a playlist"}}')).toBeNull();
    const item = (title: string, id: string) => ({ musicTwoRowItemRenderer: { title: { runs: [{ text: title, navigationEndpoint: { browseEndpoint: { browseId: id } } }] } } });
    expect(pick({ contents: [item('OpenTune Connect fans', 'VLPL9'), item('OpenTune Connect', 'VLPL123')] })).toBe('VLPL123');
    expect(pick({ contents: [item('Liked Music', 'VLLM')] })).toBeNull();
  });
});

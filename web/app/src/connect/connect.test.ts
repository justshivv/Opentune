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

/** Relays in memory. Like real ones, nothing goes out until a transport has connected, [connectMs] after it starts. */
function bus(connectMs = 0): TransportFactory {
  const listeners = new Set<(e: NostrEvent) => void>();
  return (_filter, onEvent, onConnected) => {
    let open = false;
    return {
      start: () => { listeners.add(onEvent); setTimeout(() => { open = true; onConnected(1); }, connectMs); },
      publish: (e) => { if (!open) return false; listeners.forEach((l) => setTimeout(() => l(e), 2)); return true; },
      close: () => listeners.delete(onEvent),
    };
  };
}

const fast: Timings = { presenceMs: 200, goneMs: 700, stateMs: 100, commandMs: 400, transferMs: 400, tickMs: 20 };
const track = (c: string): CTrack => ({ id: c.repeat(11), title: `Song ${c}`, artist: 'Artist' });

function fakePlayer(playable = true) {
  const s = { track: null as CTrack | null, queue: [] as CTrack[], playing: false, pos: 0, volume: 1, calls: [] as string[], needsTap: false };
  const player: ConnectPlayer = {
    snapshot: (): Snapshot | null => (s.track ? { track: s.track, private: false, playing: s.playing, pos: s.pos, queue: s.queue, volume: s.volume, shuffle: false, repeat: 'off' } : null),
    play: () => { s.calls.push('play'); s.playing = true; },
    pause: () => { s.calls.push('pause'); s.playing = false; },
    next: () => { s.calls.push('next'); },
    previous: () => { s.calls.push('previous'); },
    seekTo: (ms) => { s.calls.push(`seek ${ms}`); s.pos = ms; },
    setVolume: (v) => { s.calls.push(`volume ${v}`); s.volume = v; },
    setShuffle: (on) => { s.calls.push(`shuffle ${on}`); },
    setRepeat: (mode) => { s.calls.push(`repeat ${mode}`); },
    needsTap: () => s.needsTap,
    playTrack: (t) => { s.track = t; s.playing = true; },
    addToQueue: (ts) => { s.queue = [...s.queue, ...ts]; },
    playQueued: (at, id) => {
      const i = s.queue[at]?.id === id ? at : s.queue.findIndex((t) => t.id === id);
      if (i < 0) return false;
      s.calls.push(`jump ${i}`);
      s.track = s.queue[i]!;
      s.queue = s.queue.slice(i + 1);
      return true;
    },
    removeQueued: (at, id) => {
      const i = s.queue[at]?.id === id ? at : s.queue.findIndex((t) => t.id === id);
      if (i < 0) return false;
      s.calls.push(`remove ${i}`);
      s.queue = s.queue.filter((_, j) => j !== i);
      return true;
    },
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
  return devicesWith(fast, bus(), ...names);
}

async function devicesWith(timings: Timings, transport: TransportFactory, ...names: [string, string, boolean?][]) {
  const group = await ConnectGroup.generate();
  return names.map(([id, name, playable]) => {
    const { s, player } = fakePlayer(playable ?? true);
    const d = { id, s, view: null as ConnectView | null, notices: [] as string[], movedTo: [] as string[], session: null as unknown as ConnectSession };
    d.session = new ConnectSession(group, id, name, 'web', player, (v) => { d.view = v; }, (t, n) => d.notices.push(t.replace('{0}', n)), timings, transport, undefined, (secret) => d.movedTo.push(secret));
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

  it('says it is here once a relay connects, not only on the next round', async () => {
    const [a, b] = await devicesWith({ ...fast, presenceMs: 60_000 }, bus(50), ['a', 'Phone'], ['b', 'Chrome']);
    await eventually('see each other', () => a!.view?.devices.length === 1 && b!.view?.devices.length === 1, 1_000);
    a!.session.stop(); b!.session.stop();
  });

  it('moves paused music, and it stays where it was moved', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    await eventually('see each other', () => a!.view?.devices.length === 1 && b!.view?.devices.length === 1);
    play(a!, track('x'));
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    a!.s.playing = false;
    a!.session.localChanged();
    await new Promise((r) => setTimeout(r, 50));
    b!.session.transferTo('b');
    await eventually('b has it', () => b!.s.track?.id === track('x').id && !b!.s.playing);
    // a, told to pause (it already is), says so again: that doesn't take the music back.
    await new Promise((r) => setTimeout(r, 100));
    a!.session.localChanged();
    await new Promise((r) => setTimeout(r, 200));
    expect(a!.view?.activeId).toBe('b');
    expect(b!.view?.activeId).toBe('b');
    a!.session.stop(); b!.session.stop();
  });

  it('keeps a pause on screen over a report from before it landed', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    play(a!, track('x'));
    await eventually('b controls a', () => b!.view?.activeId === 'a' && !!b!.view.devices[0]?.state?.playing);
    b!.session.intercept('pause');
    b!.session.handle({ type: 'state', from: 'a', seq: Number.MAX_SAFE_INTEGER - 1, at: Date.now(), track: track('x'), playing: true });
    expect(b!.view!.devices[0]!.state?.playing).toBe(false);
    a!.session.stop(); b!.session.stop();
  });

  it('keeps shuffle and repeat on screen over a report from before they landed', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    play(a!, track('x'));
    await eventually('b controls a', () => b!.view?.activeId === 'a');
    b!.session.intercept('shuffle', 0, 1);
    b!.session.intercept('repeat', 0, 0, 'all');
    b!.session.handle({ type: 'state', from: 'a', seq: Number.MAX_SAFE_INTEGER - 1, at: Date.now(), track: track('x'), playing: true });
    expect(b!.view!.devices[0]!.state?.shuffle).toBe(true);
    expect(b!.view!.devices[0]!.state?.repeat).toBe('all');
    a!.session.stop(); b!.session.stop();
  });

  it('sends shuffle and repeat to the device playing', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    play(a!, track('x'));
    await eventually('b controls a', () => b!.view?.activeId === 'a');
    b!.session.intercept('repeat', 0, 0, 'all');
    b!.session.intercept('shuffle', 0, 1);
    await eventually('a set', () => a!.s.calls.includes('repeat all') && a!.s.calls.includes('shuffle true'));
    a!.session.stop(); b!.session.stop();
  });

  it('changes the Up Next of another device', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    a!.s.queue = [track('p'), track('q'), track('r')];
    play(a!, track('x'));
    await eventually('b sees the queue', () => b!.view?.devices[0]?.state?.queue?.length === 3);
    b!.session.removeOn('a', 0, track('p'));
    await eventually('p gone', () => a!.s.queue.map((t) => t.id[0]).join('') === 'qr');
    // b may still show p first; r is entry 2 there, and is found by its id.
    b!.session.jumpOn('a', 2, track('r'));
    await eventually('r plays', () => a!.s.track?.id[0] === 'r');
    a!.session.stop(); b!.session.stop();
  });

  it('tells a device that missed a merge where the others went', async () => {
    const transport = bus();
    const old = await ConnectGroup.generate();
    const merged = await ConnectGroup.generate();
    const make = (id: string, g: ConnectGroup, former: ConnectGroup[] = []) => {
      const { player } = fakePlayer();
      const d = { movedTo: [] as string[], session: null as unknown as ConnectSession };
      d.session = new ConnectSession(g, id, id, 'web', player, () => {}, () => {}, fast, transport, undefined, (secret) => d.movedTo.push(secret), former);
      d.session.start();
      return d;
    };
    // a moved from old to merged while c was off; c comes back still in old.
    const a = make('a', merged, [old]);
    const c = make('c', old);
    await eventually('c is told', () => c.movedTo.length === 1 && c.movedTo[0] === merged.encoded);
    const b = make('b', merged);
    await new Promise((r) => setTimeout(r, 200));
    expect(b.movedTo).toEqual([]);
    a.session.stop(); b.session.stop(); c.session.stop();
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

  it('keeps the music where it is when the target tab cannot start sound yet', async () => {
    const [a, b] = await devices(['a', 'Phone'], ['b', 'Chrome']);
    b!.s.needsTap = true;
    play(a!, track('x'));
    await eventually('b sees a', () => b!.view?.activeId === 'a' && a!.view?.devices.length === 1);
    a!.session.transferTo('b');
    await eventually('a told why', () => a!.notices.includes('Click in Chrome first, then try again'));
    expect(a!.s.playing).toBe(true);
    expect(b!.s.calls).not.toContain('load 1');
    a!.session.stop(); b!.session.stop();
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

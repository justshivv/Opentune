import { describe, expect, it } from 'vitest';
import { toHex } from '@/together/crypto';
import type { NostrEvent } from '@/together/nostr';
import type { TransportFactory } from '@/together/session';
import { ConnectGroup, fromBase64Url, hkdf } from './group';
import { decode, encode, fit, MAX_QUEUE, MAX_SEALED, type ConnectMsg, type CTrack } from './messages';
import { ConnectSession, meant, positionAt, type ConnectPlayer, type ConnectView, type Snapshot, type Timings } from './session';
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

/**
 * How relays treat each message: the delays its copies arrive after. Empty
 * drops it, two copies duplicate it, a longer delay than the next message's
 * reorders them.
 */
type Rule = (m: ConnectMsg) => number[];

/**
 * Relays in memory. Like real ones, nothing goes out until a transport has connected, [connectMs] after it starts.
 * With [chaos], each message is opened with its group and delivered as chaos.rule says.
 */
function bus(connectMs = 0, chaos?: { group?: ConnectGroup; rule: Rule }): TransportFactory {
  const listeners = new Set<(e: NostrEvent) => void>();
  const deliver = (e: NostrEvent) => {
    const group = chaos?.group;
    if (!group) return listeners.forEach((l) => setTimeout(() => l(e), 2));
    void group.open(e.content).then((plain) => {
      const m = plain ? decode(plain) : null;
      for (const d of m ? chaos.rule(m) : [0]) listeners.forEach((l) => setTimeout(() => l(e), 2 + d));
    });
  };
  return (_filter, onEvent, onConnected) => {
    let open = false;
    return {
      start: () => { listeners.add(onEvent); setTimeout(() => { open = true; onConnected(1); }, connectMs); },
      publish: (e) => { if (!open) return false; deliver(e); return true; },
      close: () => listeners.delete(onEvent),
    };
  };
}

const fast: Timings = { presenceMs: 200, goneMs: 700, stateMs: 100, commandMs: 400, transferMs: 400, tickMs: 20 };
const track = (c: string): CTrack => ({ id: c.repeat(11), title: `Song ${c}`, artist: 'Artist' });

function fakePlayer(playable = true) {
  // [stalled]: meant to play, but stalled to load and not moving.
  const s = { track: null as CTrack | null, queue: [] as CTrack[], playing: false, stalled: false, pos: 0, volume: 1, calls: [] as string[], needsTap: false };
  const player: ConnectPlayer = {
    snapshot: (): Snapshot | null =>
      s.track ? { track: s.track, private: false, playing: s.playing && !s.stalled, wants: s.playing, pos: s.pos, queue: s.queue, volume: s.volume, shuffle: false, repeat: 'off' } : null,
    play: () => { s.calls.push('play'); s.playing = true; },
    pause: () => { s.calls.push('pause'); s.playing = false; },
    next: () => { s.calls.push('next'); s.track = s.queue[0] ?? null; s.queue = s.queue.slice(1); s.pos = 0; },
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

const play = (d: Pick<Awaited<ReturnType<typeof devices>>[number], 's' | 'session'>, t: CTrack, pos = 0) => {
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

// ---- Handovers over relays that delay, reorder, duplicate and drop ---------------------------

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Devices on one chaotic bus; each with its clock [skew] ms off. Set [chaos.rule] to change how the relays behave. */
async function chaotic(...specs: { id: string; name: string; skew?: number }[]) {
  const group = await ConnectGroup.generate();
  const chaos: { group: ConnectGroup; rule: Rule } = { group, rule: () => [0] };
  const transport = bus(0, chaos);
  const make = (id: string, name: string, skew = 0, took = 0, player = fakePlayer()) => {
    const d = { id, s: player.s, player, view: null as ConnectView | null, notices: [] as string[], clock: () => Date.now() + skew, session: null as unknown as ConnectSession };
    d.session = new ConnectSession(group, id, name, 'web', player.player, (v) => { d.view = v; }, (t, n) => d.notices.push(t.replace('{0}', n)), fast, transport, d.clock, () => {}, [], took);
    d.session.start();
    return d;
  };
  const devs = specs.map((s) => make(s.id, s.name, s.skew));
  await eventually('all here', () => devs.every((d) => d.view?.devices.length === devs.length - 1));
  type Dev = (typeof devs)[number];
  /** Connect starting over on [d] (a group joined, the switch flipped), its player left as it is. */
  const restart = (d: Dev): Dev => {
    const took = d.session.tookAt;
    d.session.stop();
    return make(d.id, d.id, 0, took, d.player);
  };
  return { devs, chaos, restart, stop: () => devs.forEach((d) => d.session.stop()) };
}

describe('handovers over real-world relays', () => {
  const x = track('x');
  const y = track('y');

  it('takes an answer overtaken by a later report', async () => {
    const { devs: [a, b], chaos, stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    play(a!, x, 60_000);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    // b's answer to the handover takes a slow relay; what b says after it gets there first.
    chaos.rule = (m) => (m.type === 'ack' ? [150] : [0]);
    a!.session.transferTo('b');
    await eventually('b plays, a stops', () => b!.s.playing && !a!.s.playing);
    await wait(fast.transferMs + 200);
    expect(a!.notices).toEqual([]);
    stop();
  });

  it('does not take a repeated or late handover again', async () => {
    const [b] = await devices(['b', 'Chrome']);
    const now = Date.now();
    const t = { type: 'transfer' as const, from: 'a', seq: 10, at: now, to: 'b', id: 't1', queue: [x], pos: 1_000 };
    b!.session.handle(t);
    b!.session.handle(t); // a second relay's copy
    b!.session.handle({ type: 'command', from: 'a', seq: 12, at: now, to: 'b', id: 'c1', action: 'pause' });
    // An older handover, held up on its way, arriving after the pause that followed it.
    b!.session.handle({ type: 'transfer', from: 'a', seq: 11, at: now, to: 'b', id: 't0', queue: [y], pos: 5_000 });
    expect(b!.s.calls.filter((c) => c.startsWith('load'))).toHaveLength(1);
    expect(b!.s.track?.id).toBe(x.id);
    expect(b!.s.playing).toBe(false);
    b!.session.stop();
  });

  it('starts a handover where it was, whatever the clocks say', async () => {
    // The browser's clock runs 20 s ahead of the phone's.
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome', skew: 20_000 });
    play(a!, x, 60_000);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    a!.session.transferTo('b');
    await eventually('b plays', () => b!.s.playing && b!.s.track?.id === x.id);
    expect(b!.s.pos).toBeGreaterThanOrEqual(60_000);
    expect(b!.s.pos).toBeLessThanOrEqual(61_000);
    stop();
  });

  it("learns the other device's clock over the relays", async () => {
    const { devs: [a, b], chaos, stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome', skew: 20_000 });
    // Every message takes 100 ms to arrive.
    chaos.rule = () => [100];
    await eventually("b knows a's clock", () => b!.session.clockOffset('a') !== null);
    const offset = b!.session.clockOffset('a')!;
    expect(offset).toBeGreaterThanOrEqual(-20_100);
    expect(offset).toBeLessThanOrEqual(-19_900);
    play(a!, x, 60_000);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    a!.session.transferTo('b');
    await eventually('b plays', () => b!.s.playing && b!.s.track?.id === x.id);
    // Started on from where a was when it said so, the relay's 100 ms included.
    expect(b!.s.pos).toBeGreaterThanOrEqual(60_030);
    expect(b!.s.pos).toBeLessThanOrEqual(60_300);
    stop();
  });

  it('keeps the place of paused music handed over', async () => {
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome', skew: 5_000 });
    play(a!, x, 30_000);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    a!.s.playing = false;
    a!.session.localChanged();
    await wait(50);
    a!.session.transferTo('b');
    await eventually('b has it', () => b!.s.track?.id === x.id);
    expect(b!.s.pos).toBe(30_000);
    expect(b!.s.playing).toBe(false);
    stop();
  });

  it("judges who took the music last on each device's own clock", async () => {
    // The phone's clock runs a minute ahead.
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone', skew: 60_000 }, { id: 'b', name: 'Chrome' });
    play(a!, x);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    a!.s.playing = false;
    a!.session.localChanged();
    await wait(50);
    b!.session.transferTo('b');
    await eventually('b has it', () => b!.s.track?.id === x.id);
    await eventually('both show b', () => a!.view?.activeId === 'b' && b!.view?.activeId === 'b');
    stop();
  });

  it('keeps a device stalled to load as the one playing', async () => {
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    play(a!, x);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    await wait(30);
    // b has a song of its own loaded, paused; then a stalls to load.
    b!.s.track = y;
    b!.session.localChanged();
    a!.s.stalled = true;
    a!.session.localChanged();
    await wait(200);
    expect(a!.view?.activeId).toBe('a');
    expect(b!.view?.activeId).toBe('a');
    expect(meant(b!.view!.devices[0]!.state)).toBe(true);
    expect(b!.view!.devices[0]!.state?.playing).toBe(false);
    stop();
  });

  it('does not take the music back when a paused device starts Connect again', async () => {
    const { devs: [a, b], restart, stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    a!.s.track = x;
    a!.session.localChanged();
    await wait(30);
    play(b!, y);
    await eventually('a shows b', () => a!.view?.activeId === 'b');
    b!.s.playing = false;
    b!.session.localChanged();
    await wait(50);
    const again = restart(a!);
    await wait(300);
    await eventually('they meet again', () => again.view?.devices.length === 1 && b!.view?.devices.length === 1);
    expect(b!.view?.activeId).toBe('b');
    expect(again.view?.activeId).toBe('b');
    again.session.stop();
    stop();
  });

  it('leaves one device playing when the answer and the pause are lost', async () => {
    const { devs: [a, b], chaos, stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    play(a!, x);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    // Nothing b sends to a in particular gets through: only what b says it plays.
    chaos.rule = (m) => (m.from === 'b' && (m.type === 'ack' || m.type === 'command') ? [] : [0]);
    a!.session.transferTo('b');
    await eventually('b plays', () => b!.s.playing);
    await eventually('a stops', () => !a!.s.playing);
    await eventually('both show b', () => a!.view?.activeId === 'b' && b!.view?.activeId === 'b');
    await wait(fast.transferMs + 100);
    expect(a!.notices.filter((n) => n.startsWith("Can't play"))).toEqual([]);
    stop();
  });

  it('leaves one device playing over a relay that duplicates and reorders', async () => {
    const { devs: [a, b], chaos, stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    play(a!, x, 60_000);
    await eventually('b sees a', () => b!.view?.activeId === 'a');
    // Every message twice, the second copy late; answers, commands and handovers slower than reports.
    chaos.rule = (m) => (m.type === 'ack' || m.type === 'command' || m.type === 'transfer' ? [60, 250] : [0, 200]);
    a!.session.transferTo('b');
    await eventually('b plays, a stops', () => b!.s.playing && !a!.s.playing);
    await wait(600);
    expect(b!.s.playing).toBe(true);
    expect(a!.s.playing).toBe(false);
    expect(b!.s.calls.filter((c) => c.startsWith('load'))).toHaveLength(1);
    expect(a!.s.calls).not.toContain('play');
    expect(a!.view?.activeId).toBe('b');
    expect(b!.view?.activeId).toBe('b');
    stop();
  });

  it('keeps a seek on screen over a report from before it landed', async () => {
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    play(a!, x, 10_000);
    await eventually('b controls a', () => b!.view?.activeId === 'a');
    b!.session.intercept('seek', 42_000);
    // a's report, sent before the seek reached it.
    b!.session.handle({ type: 'state', from: 'a', seq: Number.MAX_SAFE_INTEGER - 1, at: Date.now(), track: x, playing: true, pos: 10_000 });
    const shown = positionAt(b!.view!.devices[0]!, Date.now());
    expect(shown).toBeGreaterThanOrEqual(42_000);
    expect(shown).toBeLessThanOrEqual(43_000);
    await eventually('a seeked', () => a!.s.calls.includes('seek 42000'));
    stop();
  });

  it('shows the next song from its start at once', async () => {
    const { devs: [a, b], stop } = await chaotic({ id: 'a', name: 'Phone' }, { id: 'b', name: 'Chrome' });
    a!.s.queue = [y];
    play(a!, x, 100_000);
    await eventually('b controls a', () => b!.view?.activeId === 'a' && b!.view.devices[0]!.state?.track?.id === x.id);
    b!.session.intercept('next');
    const shown = () => { const d = b!.view!.devices[0]!; return { id: d.state?.track?.id, at: positionAt(d, Date.now()) }; };
    expect(shown().id).toBe(y.id);
    expect(shown().at).toBeLessThan(1_000);
    // a's report from before the skip landed: neither the old song nor its place come back.
    b!.session.handle({ type: 'state', from: 'a', seq: Number.MAX_SAFE_INTEGER - 1, at: Date.now(), track: x, playing: true, pos: 100_000, queue: [y] });
    expect(shown().id).toBe(y.id);
    expect(shown().at).toBeLessThan(1_000);
    await eventually('a skipped', () => a!.s.track?.id === y.id);
    stop();
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

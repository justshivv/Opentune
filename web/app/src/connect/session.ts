import { newPrivateKey, publicKeyOf } from '@/together/crypto';
import { RelayPool, signedEvent, type NostrEvent } from '@/together/nostr';
import { RELAYS, type Transport, type TransportFactory } from '@/together/session';
import type { ConnectGroup } from './group';
import { decode, fit, MAX_QUEUE, type Ack, type Command, type ConnectMsg, type CTrack, type StateMsg } from './messages';

export const KIND = 21421;
export const NOT_ANSWERING = "{0} isn't answering";
export const CANT_PLAY = "Can't play this on {0}";
export const NEEDS_TAP = 'Click in {0} first, then try again';

/** What Connect needs from this device's player; ConnectPlayer on the phone. */
export interface ConnectPlayer {
  snapshot(): Snapshot | null;
  play(): void;
  pause(): void;
  next(): void;
  previous(): void;
  seekTo(ms: number): void;
  setVolume(v: number): void;
  setShuffle(on: boolean): void;
  /** "off", "all" or "one". */
  setRepeat(mode: string): void;
  playTrack(t: CTrack): void;
  addToQueue(ts: CTrack[]): void;
  /**
   * Plays, or takes out, entry [at] of the queue this device last told
   * (Snapshot.queue) when it's still [id]; otherwise the first one that is.
   * False when there's none.
   */
  playQueued(at: number, id: string): boolean;
  removeQueued(at: number, id: string): boolean;
  /** Moves Up Next's entry [from] ([id]) to [to]; false when it isn't there. Optional: added after the others. */
  moveQueued?(from: number, to: number, id: string): boolean;
  /** Loads [queue] from [index] at [pos] ms; false when none of it can play here. */
  load(queue: CTrack[], index: number, pos: number, playing: boolean): boolean;
  /** True while this device can't start sound by itself (a browser tab nobody has clicked in yet). */
  needsTap?(): boolean;
}

export interface Snapshot {
  track: CTrack | null;
  private: boolean;
  /** Moving: the clock other devices run on for this one. Not while it stalls to load. */
  playing: boolean;
  /** Meant to play, stalled or not: starting to play is judged by this, so a stall ending isn't a start. */
  wants?: boolean;
  pos: number;
  /** What comes after [track]. */
  queue: CTrack[];
  volume: number;
  shuffle: boolean;
  repeat: string;
  /** How it sounds here, in this device's words; null when it can't say. */
  quality?: string | null;
}

export interface Device {
  id: string;
  name: string;
  form: string;
  lastSeen: number;
  state: StateMsg | null;
  /** When, on this device's clock, [state] was true: the moment it was sent, as near as the clocks' difference is known. */
  receivedAt: number;
  /** When it took the music (see StateMsg.since), on this device's clock. */
  since: number;
}

/**
 * Meant to play, stalled to load or not: who has the music is judged by this,
 * and play/pause buttons for the device go by it. A device too old to say is
 * taken at its `playing`.
 */
export const meant = (s: StateMsg | null | undefined): boolean => !!(s?.wants ?? s?.playing);

/** Where its song is now, counting on from its last state while it moves. */
export const positionAt = (d: Device, now: number) =>
  d.state ? (d.state.playing ? (d.state.pos ?? 0) + Math.max(0, now - d.receivedAt) : (d.state.pos ?? 0)) : 0;
const hasSomething = (d: Device) => !!d.state && (!!d.state.track || !!d.state.private);

export interface ConnectView {
  myId: string;
  myName: string;
  relays: number;
  devices: Device[];
  activeId: string | null;
  pending: string[];
}

export const controllingOf = (v: ConnectView | null): Device | null =>
  v && v.activeId && v.activeId !== v.myId ? (v.devices.find((d) => d.id === v.activeId) ?? null) : null;

export interface Timings {
  presenceMs: number;
  goneMs: number;
  stateMs: number;
  commandMs: number;
  transferMs: number;
  tickMs: number;
}
/** How long a play or pause pressed here outlasts reports from before it landed. */
const GUESS_MS = 1_500;
/** The same for a place pressed (a seek, a skip): the command goes, the player settles, its report comes back. */
const PLACE_MS = 3_000;
/** How far off a pressed place a report can be and still be after it. */
const PLACE_SLACK_MS = 2_000;
/** A message's trip is never counted longer than this: one held up longer is late, not news. */
const MAX_TRANSIT_MS = 5_000;
const MAX_RTT_MS = 20_000;
/** A clock measured on a slower trip replaces the best one once that's this old. */
const OFFSET_KEEP_MS = 10 * 60_000;
const PING_TRIES = 3;
const PING_RETRY_MS = 2_000;
/** How much later another device has to have taken the music for this one to stop for it. */
const YIELD_MS = 1_500;
/** A handover's target taking the music this long before it was sent still counts as taking it. */
const TOOK_SLACK_MS = 2_000;
/**
 * Pressed here for a device a moment ago: play/pause, shuffle and repeat
 * until [until]; a place in a song (a seek, a skip, and the song skipped to
 * as [track]) until [placeUntil], set at [placeAt].
 */
interface Guess {
  until: number;
  playing?: boolean;
  shuffle?: boolean;
  repeat?: string;
  placeUntil?: number;
  placeAt?: number;
  pos?: number;
  track?: string;
}
/** How often a device left in a former group is told again where the others went. */
const FORWARD_MS = 60_000;
const DEFAULT_TIMINGS: Timings = { presenceMs: 30_000, goneMs: 75_000, stateMs: 5_000, commandMs: 5_000, transferMs: 8_000, tickMs: 1_000 };

/** What's meant here: wants, or moving for a player that can't say. */
const wants = (s: Snapshot | null | undefined): boolean => !!(s?.wants ?? s?.playing);
const has = (s: Snapshot | null | undefined): boolean => !!s && (!!s.track || s.private);

const relays: TransportFactory =(filter, onEvent, onConnected) => new RelayPool(RELAYS, filter, onEvent, onConnected);
const newId = () => crypto.randomUUID().replace(/-/g, '').slice(0, 12);

interface Pending {
  to: string;
  timer: ReturnType<typeof setTimeout>;
  onAck: (a: Ack) => void;
}

/**
 * One device's part in its group, a port of ConnectSession.kt: says it's
 * here, tells the others what it plays, follows what they play, and passes
 * commands and handovers between them.
 */
export class ConnectSession {
  private readonly key = newPrivateKey();
  private readonly myPub = publicKeyOf(this.key);
  private pool: Transport | null = null;
  private seq = 0;
  private readonly devices = new Map<string, Device>();
  // Relays deliver late, out of order and more than once. Each message is taken once; a report or
  // a presence only over an older one of its kind, and a command or handover for this device only
  // over older ones, so a late copy never undoes what came after it. An answer counts whenever it
  // comes: what followed it can't make it stale.
  private readonly seen = new Set<string>();
  private readonly lastSeq = new Map<string, number>();
  private readonly lastState = new Map<string, number>();
  private readonly lastPresence = new Map<string, number>();
  private readonly lastControl = new Map<string, number>();
  /** The seq of each device's goodbye: anything it said before that is over. */
  private readonly leftAt = new Map<string, number>();
  /** Each other device's clock against this one's (theirs minus mine), from the quickest round trip over the relays. */
  private readonly offsets = new Map<string, { offset: number; rtt: number; at: number }>();
  private readonly pinged = new Map<string, { at: number; unanswered: number }>();
  private readonly pending = new Map<string, Pending>();
  /** Pressed here for a device a moment ago: until when, and what it was. */
  private readonly guesses = new Map<string, Guess>();
  private mine: Snapshot | null = null;
  private mineAt = 0;
  private lastStateSent = 0;
  private lastPresenceSent = 0;
  private ticker: ReturnType<typeof setInterval> | null = null;
  private sending: Promise<void> = Promise.resolve();
  private reading: Promise<void> = Promise.resolve();
  private stopped = false;
  /** When each device left behind in a former group was last told to follow. */
  private readonly forwarded = new Map<string, number>();
  view: ConnectView;

  constructor(
    readonly group: ConnectGroup,
    readonly myId: string,
    private myName: string,
    private readonly form: string,
    private readonly player: ConnectPlayer,
    private readonly publish: (view: ConnectView) => void,
    /** A message for the person: an English template and the device name for its {0}. */
    private readonly notice: (template: string, name: string) => void,
    private readonly timings: Timings = DEFAULT_TIMINGS,
    private readonly transport: TransportFactory = relays,
    private readonly clock: () => number = Date.now,
    /** Another device here moved to the group with this secret: this one follows. */
    private readonly moved: (secret: string) => void = () => {},
    /** Groups this device was in before: a device still there (it was off when they were merged) is told where everyone went. */
    private readonly former: ConnectGroup[] = [],
    /**
     * When this browser last took the music, on its clock, as an earlier
     * session here left it (tookAt); 0 when there was none. Connect starting
     * over doesn't count as taking it.
     */
    private readonly took = 0,
  ) {
    this.view = { myId, myName, relays: 0, devices: [], activeId: null, pending: [] };
  }

  /** When this device took the music, for the next session here to carry on from. */
  get tookAt(): number {
    return this.mineAt;
  }

  /** Theirs minus mine, once a round trip has measured it; for tests. */
  clockOffset(id: string): number | null {
    return this.offsets.get(id)?.offset ?? null;
  }

  /** How long ago, on this device's clock, [msg] was sent; 0 while the sender's clock isn't known. */
  private transit(msg: ConnectMsg, now: number): number {
    const o = this.offsets.get(msg.from);
    return o ? Math.min(Math.max(now - (msg.at - o.offset), 0), MAX_TRANSIT_MS) : 0;
  }

  start(): void {
    // Numbered from the clock, so a restarted device's messages still count as newer than its last ones.
    this.seq = Math.max(this.seq, this.clock());
    const filter = { kinds: [KIND], '#t': [this.group, ...this.former].map((g) => g.tag), since: Math.floor(this.clock() / 1000) - 10 };
    this.pool = this.transport(filter, (e) => this.onEvent(e), (n) => {
      // What was said with no relay connected (at start, or after losing them all) went nowhere: said again.
      const reached = this.view.relays === 0 && n > 0;
      this.update({ relays: n });
      if (reached && !this.stopped) {
        this.sayPresence();
        this.sayState();
      }
    });
    this.pool.start();
    this.ticker = setInterval(() => this.tick(), this.timings.tickMs);
    this.mine = this.player.snapshot();
    // Connect starting over isn't taking the music: when it was taken here is carried on.
    // Only a device starting from nothing (the page opened afresh) counts its song from now.
    if (has(this.mine)) this.mineAt = this.took > 0 ? this.took : this.clock();
    this.refresh();
    this.sayPresence();
    this.sayState();
  }

  /** Leaves the group; [next] (another group's secret) takes the devices here along. */
  stop(next?: string): void {
    if (this.stopped) return;
    this.send({ type: 'leave', from: this.myId, seq: ++this.seq, at: this.clock(), ...(next ? { next } : {}) });
    this.stopped = true;
    if (this.ticker) clearInterval(this.ticker);
    for (const p of this.pending.values()) clearTimeout(p.timer);
    const pool = this.pool;
    void this.sending.then(() => setTimeout(() => pool?.close(), 300));
  }

  rename(name: string): void {
    this.myName = name;
    this.update({ myName: name });
    this.sayPresence();
  }

  /** This device's player changed: tell the others, and quiet whichever other one was playing. */
  localChanged(): void {
    const before = this.mine;
    const now = this.player.snapshot();
    this.mine = now;
    if (!now) return this.refresh();
    const started = wants(now) && !wants(before);
    const newSong = now.track?.id !== before?.track?.id || now.private !== before?.private;
    // Taking the music: starting it, or a new song while it's meant to play. Pausing leaves it
    // where it was taken, and so does a song put on paused (a queue restored, a list edited).
    if (started || (newSong && wants(now)) || (this.mineAt === 0 && has(now))) this.mineAt = this.clock();
    // Stalled or not: a device still meant to play would otherwise start up again.
    if (started) for (const d of this.devices.values()) if (meant(d.state)) this.command(d.id, 'pause', { quiet: true });
    this.sayState();
    this.refresh();
  }

  /** Notes a change pressed here for [id], on top of the ones still waiting to show in its reports. */
  private guess(id: string, change: Pick<Guess, 'playing' | 'shuffle' | 'repeat'>): void {
    const now = this.clock();
    const before = this.guesses.get(id) ?? { until: 0 };
    const live = now < before.until;
    this.guesses.set(id, {
      ...before,
      ...(live ? {} : { playing: undefined, shuffle: undefined, repeat: undefined }),
      ...change,
      until: now + GUESS_MS,
    });
  }

  /** Notes a place pressed here for [id]: [pos] in [track] (undefined: the song it's on). */
  private guessPlace(id: string, pos: number, track?: string): void {
    const now = this.clock();
    this.guesses.set(id, { ...(this.guesses.get(id) ?? { until: 0 }), placeUntil: now + PLACE_MS, placeAt: now, pos, track });
  }

  /** A change shown at once that [target] didn't make: back to what it said last. */
  private undoGuess(target: Device, shown: StateMsg | null): void {
    this.guesses.delete(target.id);
    const d = this.devices.get(target.id);
    if (shown && d) this.setDevice({ ...d, state: shown, receivedAt: target.receivedAt });
  }

  /** A button pressed here while another device plays: sends it there. True when it went. */
  intercept(action: string, pos = 0, value = 0, mode?: string): boolean {
    const target = controllingOf(this.view);
    if (!target) return false;
    const shown = target.state;
    if (shown) {
      const now = this.clock();
      const next = shown.queue?.[0];
      const guess: StateMsg | null =
        action === 'play' ? { ...shown, playing: true, wants: true, pos: positionAt(target, now) }
        : action === 'pause' ? { ...shown, playing: false, wants: false, pos: positionAt(target, now) }
        : action === 'seek' ? { ...shown, pos }
        // A skip shows where it lands at once, standing until the song there starts: the last
        // song's place, counted on against the new one, ran ahead until a report came.
        : action === 'next' ? (next ? { ...shown, track: next, queue: shown.queue!.slice(1), pos: 0, playing: false } : null)
        : action === 'previous' ? { ...shown, pos: 0, playing: false }
        : action === 'volume' ? { ...shown, volume: value }
        : action === 'shuffle' ? { ...shown, shuffle: value > 0.5 }
        : action === 'repeat' ? { ...shown, repeat: mode ?? shown.repeat }
        : null;
      if (guess) this.setDevice({ ...target, state: guess, receivedAt: now });
      if (guess && (action === 'play' || action === 'pause')) this.guess(target.id, { playing: meant(guess) });
      if (guess && action === 'shuffle') this.guess(target.id, { shuffle: !!guess.shuffle });
      if (guess && action === 'repeat') this.guess(target.id, { repeat: guess.repeat ?? 'off' });
      if (guess && action === 'seek') this.guessPlace(target.id, pos);
      if (guess && action === 'next') this.guessPlace(target.id, 0, next?.id);
      if (guess && action === 'previous') this.guessPlace(target.id, 0);
    }
    this.command(target.id, action, {
      pos,
      value,
      ...(mode ? { mode } : {}),
      onAck: (ack) => {
        if (!ack.ok) this.undoGuess(target, shown);
      },
    });
    return true;
  }

  /** [track] starting from its beginning on [deviceId], shown at once: the queue up to it goes. */
  private showStarting(deviceId: string, track: CTrack): Device | null {
    const target = this.devices.get(deviceId);
    const shown = target?.state;
    if (!target || !shown) return null;
    const queue = shown.queue ?? [];
    const at = queue.findIndex((t) => t.id === track.id);
    this.setDevice({ ...target, state: { ...shown, track, queue: at >= 0 ? queue.slice(at + 1) : queue, pos: 0, playing: false }, receivedAt: this.clock() });
    this.guessPlace(deviceId, 0, track.id);
    return target;
  }

  playTrackOn(deviceId: string, track: CTrack): void {
    const before = this.showStarting(deviceId, track);
    this.command(deviceId, 'playTrack', { track, onAck: (ack) => { if (!ack.ok && before) this.undoGuess(before, before.state); } });
  }

  addToQueueOn(deviceId: string, tracks: CTrack[]): void {
    this.command(deviceId, 'addToQueue', { tracks });
  }

  /** Plays entry [at] of [deviceId]'s Up Next ([track], so a queue changed meanwhile still finds it). */
  jumpOn(deviceId: string, at: number, track: CTrack): void {
    const before = this.showStarting(deviceId, track);
    this.command(deviceId, 'jump', { pos: at, track, onAck: (ack) => { if (!ack.ok && before) this.undoGuess(before, before.state); } });
  }

  /** [deviceId]'s Up Next shown changed at once by [edit], until it reports; back as it was if it refuses. */
  private editQueueShown(deviceId: string, edit: (queue: CTrack[]) => CTrack[] | null): { target: Device; shown: StateMsg } | null {
    const target = this.devices.get(deviceId);
    const shown = target?.state;
    if (!target || !shown) return null;
    const queue = edit(shown.queue ?? []);
    if (!queue) return null;
    this.setDevice({ ...target, state: { ...shown, queue }, receivedAt: target.receivedAt });
    return { target, shown };
  }

  /** Takes entry [at] ([track]) out of [deviceId]'s Up Next. */
  removeOn(deviceId: string, at: number, track: CTrack, onRefused?: (reason: string | null) => void): void {
    const before = this.editQueueShown(deviceId, (queue) => (queue[at]?.id === track.id ? queue.filter((_, i) => i !== at) : null));
    this.command(deviceId, 'remove', {
      pos: at,
      track,
      onAck: (ack) => {
        if (ack.ok) return;
        if (before) this.undoGuess(before.target, before.shown);
        onRefused?.(ack.reason ?? null);
      },
    });
  }

  /**
   * Moves entry [from] ([track]) of [deviceId]'s Up Next to [to]: shown at
   * once, put back if the device refuses. `move` carries [from] in `pos` and
   * [to] in `value`; a device too old to know it answers the ack with ok
   * false, as for any action it doesn't know, and nothing else changes.
   */
  moveOn(deviceId: string, from: number, to: number, track: CTrack, onRefused?: (reason: string | null) => void): void {
    const before = this.editQueueShown(deviceId, (queue) => {
      if (queue[from]?.id !== track.id || to < 0 || to >= queue.length || to === from) return null;
      const next = [...queue];
      const [item] = next.splice(from, 1);
      next.splice(to, 0, item!);
      return next;
    });
    this.command(deviceId, 'move', {
      pos: from,
      value: to,
      track,
      onAck: (ack) => {
        if (ack.ok) return;
        if (before) this.undoGuess(before.target, before.shown);
        onRefused?.(ack.reason ?? null);
      },
    });
  }

  /** Moves playback to [deviceId]; this device's own id pulls it here. */
  transferTo(deviceId: string): void {
    const activeId = this.view.activeId;
    if (!activeId || deviceId === activeId) return;
    if (activeId === this.myId) {
      const snap = this.player.snapshot();
      if (!snap?.track) return;
      const track = snap.track;
      const id = newId();
      const sentAt = this.clock();
      // Meant to play goes over as playing: a stall here at that moment isn't a pause.
      this.send({ type: 'transfer', from: this.myId, seq: ++this.seq, at: sentAt, to: deviceId, id, queue: [track, ...snap.queue], index: 0, pos: Math.round(snap.pos), playing: wants(snap) });
      this.expect(id, deviceId, this.timings.transferMs, (ack) => {
        if (ack.ok) this.player.pause();
        else this.refused(ack, deviceId);
      }, () => this.tookOver(deviceId, track, sentAt));
      return;
    }
    const source = this.devices.get(activeId);
    const state = source?.state;
    if (!source || !state) return;
    if (!state.track) return this.notice(CANT_PLAY, this.nameOf(deviceId));
    const track = state.track;
    const queue = [track, ...(state.queue ?? [])];
    const now = this.clock();
    const pos = Math.round(positionAt(source, now));
    const playing = meant(state);
    if (deviceId === this.myId) {
      if (this.player.load(queue, 0, pos, playing)) {
        this.command(source.id, 'pause', { quiet: true });
        this.mine = null;
        this.mineAt = now;
        this.localChanged();
      } else this.notice(CANT_PLAY, this.myName);
      return;
    }
    const id = newId();
    this.send({ type: 'transfer', from: this.myId, seq: ++this.seq, at: now, to: deviceId, id, queue, index: 0, pos, playing });
    this.expect(id, deviceId, this.timings.transferMs, (ack) => {
      if (ack.ok) this.command(source.id, 'pause', { quiet: true });
      else this.refused(ack, deviceId);
    }, () => this.tookOver(deviceId, track, now));
  }

  /** Whether [deviceId] says it has [track] and took it after [after]: a handover it took, its answer lost. */
  private tookOver(deviceId: string, track: CTrack, after: number): boolean {
    const d = this.devices.get(deviceId);
    return !!d && d.state?.track?.id === track.id && d.since >= after - TOOK_SLACK_MS;
  }

  /** Why [deviceId] didn't take the music, for the person. */
  private refused(ack: { reason?: string | null }, deviceId: string): void {
    this.notice(ack.reason === 'needs-tap' ? NEEDS_TAP : CANT_PLAY, this.nameOf(deviceId));
  }

  // ---- In ------------------------------------------------------------------------------------

  private onEvent(event: NostrEvent): void {
    if (event.pubkey === this.myPub) return;
    // Opened in the order they came, as the phone does.
    this.reading = this.reading.then(async () => {
      const plain = await this.group.open(event.content);
      const msg = plain ? decode(plain) : null;
      if (msg) {
        if (!this.stopped) this.handle(msg);
        return;
      }
      for (const old of this.former) {
        const before = await old.open(event.content);
        const heard = before ? decode(before) : null;
        if (heard) return this.forward(heard, old);
      }
    });
  }

  /** Whether [msg] is newer than the last of its kind from its sender in [last]; noted if so. */
  private newer(last: Map<string, number>, msg: ConnectMsg): boolean {
    const before = last.get(msg.from);
    if (before !== undefined && msg.seq <= before) return false;
    last.set(msg.from, msg.seq);
    return true;
  }

  handle(msg: ConnectMsg): void {
    if (msg.from === this.myId) return;
    // Another relay's copy, or one heard again after a relay came back.
    const key = `${msg.from}/${msg.seq}`;
    if (this.seen.has(key)) return;
    this.seen.add(key);
    if (this.seen.size > 2000) this.seen.delete(this.seen.values().next().value!);
    // Said before the device said goodbye: over.
    const left = this.leftAt.get(msg.from);
    if (left !== undefined && msg.seq <= left) return;
    const latest = this.lastSeq.get(msg.from) ?? -Infinity;
    this.lastSeq.set(msg.from, Math.max(latest, msg.seq));
    const now = this.clock();
    const known = this.devices.get(msg.from);
    switch (msg.type) {
      case 'presence': {
        if (!this.newer(this.lastPresence, msg)) return this.touch(msg.from, now);
        const base: Device = known ?? { id: msg.from, name: msg.name, form: msg.form, lastSeen: now, state: null, receivedAt: 0, since: 0 };
        this.setDevice({ ...base, name: msg.name, form: msg.form, lastSeen: now });
        // A device just arrived: tell it about this one straight away, and learn its clock.
        if (!known) {
          this.sayPresence();
          this.sayState(true);
          this.ping(msg.from, now);
        }
        return;
      }
      case 'state':
        return this.onState(msg, known, now);
      case 'leave':
        // A goodbye from before something newer it said (a restart's hello overtaking it) is stale.
        // That hello found this device still known, so nothing went back: the restarted one
        // hasn't heard of it, and a paused one won't say its state again unasked.
        if (msg.seq < latest) {
          if (known) {
            this.sayPresence();
            this.sayState(true);
          }
          return;
        }
        this.leftAt.set(msg.from, msg.seq);
        this.devices.delete(msg.from);
        this.offsets.delete(msg.from);
        this.pinged.delete(msg.from);
        this.refresh();
        if (typeof msg.next === 'string') this.moved(msg.next);
        return;
      case 'command': {
        this.touch(msg.from, now);
        if (msg.to !== this.myId || !this.newer(this.lastControl, msg)) return;
        const ok = this.apply(msg);
        this.send({ type: 'ack', from: this.myId, seq: ++this.seq, at: now, to: msg.from, id: msg.id, ok, ...(ok ? {} : { reason: 'unplayable' }) });
        // Let the player settle before saying what it does now.
        setTimeout(() => this.localChanged(), 50);
        return;
      }
      case 'transfer': {
        this.touch(msg.from, now);
        // A copy of one taken already, or one older than a command that came after it, isn't taken again.
        if (msg.to !== this.myId || !this.newer(this.lastControl, msg)) return;
        const playing = msg.playing ?? true;
        // Playing, it has gone on for as long as the message took, measured on one clock (the sender's, turned
        // into this one's); the two clocks' difference, seconds at times, is no part of it. Paused, it stays put.
        const late = playing ? this.transit(msg, now) : 0;
        // Taken only if it can sound here: otherwise the music stays where it is.
        const reason = playing && this.player.needsTap?.() ? 'needs-tap' : null;
        const ok = !reason && this.player.load(msg.queue ?? [], msg.index ?? 0, (msg.pos ?? 0) + late, playing);
        this.send({ type: 'ack', from: this.myId, seq: ++this.seq, at: now, to: msg.from, id: msg.id, ok, ...(ok ? {} : { reason: reason ?? 'unplayable' }) });
        if (ok) {
          this.mine = null;
          this.mineAt = this.clock();
          setTimeout(() => this.localChanged(), 50);
        }
        return;
      }
      case 'ack': {
        this.touch(msg.from, now);
        if (msg.to !== this.myId) return;
        const p = this.pending.get(msg.id);
        if (!p) return;
        this.pending.delete(msg.id);
        clearTimeout(p.timer);
        this.refresh();
        p.onAck({ ...msg, ok: msg.ok !== false });
        return;
      }
      case 'ping':
        this.touch(msg.from, now);
        if (msg.to === this.myId) this.send({ type: 'pong', from: this.myId, seq: ++this.seq, at: now, to: msg.from, c: msg.c, h: now });
        return;
      case 'pong':
        this.touch(msg.from, now);
        if (msg.to === this.myId) this.clockHeard(msg.from, msg.c, msg.h, now);
        return;
      default:
        this.touch((msg as ConnectMsg).from, now);
    }
  }

  private onState(msg: StateMsg, known: Device | undefined, now: number): void {
    if (!this.newer(this.lastState, msg)) return this.touch(msg.from, now);
    let st = msg;
    // Something pressed here a moment ago: a report from before it landed doesn't undo it on screen.
    const g0 = this.guesses.get(msg.from);
    if (g0) {
      let g: Guess = { ...g0 };
      if (now >= g.until) g = { ...g, playing: undefined, shuffle: undefined, repeat: undefined };
      if (now >= (g.placeUntil ?? 0)) g = { ...g, pos: undefined, track: undefined };
      if (g.playing !== undefined && meant(msg) !== g.playing) return this.touch(msg.from, now);
      if (g.pos !== undefined) {
        // The place pressed (a seek, a skip): a report elsewhere in the song, or on another one, is from before.
        const off = (g.track !== undefined && msg.track?.id !== g.track) || Math.abs((msg.pos ?? 0) - g.pos) > PLACE_SLACK_MS + (now - (g.placeAt ?? now));
        if (off) return this.touch(msg.from, now);
      }
      const confirmed = (g.shuffle === undefined || !!msg.shuffle === g.shuffle) && (g.repeat === undefined || (msg.repeat ?? 'off') === g.repeat);
      if (confirmed) this.guesses.delete(msg.from);
      else {
        st = { ...msg, ...(g.shuffle !== undefined ? { shuffle: g.shuffle } : {}), ...(g.repeat !== undefined ? { repeat: g.repeat } : {}) };
        this.guesses.set(msg.from, { ...g, playing: undefined, pos: undefined, track: undefined });
      }
    }
    const base: Device = known ?? { id: msg.from, name: '', form: '', lastSeen: now, state: null, receivedAt: 0, since: 0 };
    // When it was said, on this clock: its position counts on from then.
    const said = now - this.transit(msg, now);
    // When it took the music, as long before it said so as its own clock says: the two clocks'
    // difference (seconds, at times) doesn't decide which device took it last.
    const took = base.state?.track?.id !== st.track?.id || (meant(st) && !meant(base.state));
    const since = typeof st.since === 'number' ? said - Math.max(0, st.at - st.since) : took ? now : base.since;
    const d: Device = { ...base, lastSeen: now, state: st, receivedAt: said, since };
    this.setDevice(d);
    if (!known) this.sayPresence();
    this.yieldTo(d);
  }

  /**
   * Two devices meant to play at once (a pause command or a handover's answer
   * lost on the way): the one that took the music last keeps it, and this one,
   * if it took it earlier, stops.
   */
  private yieldTo(d: Device): void {
    if (!meant(d.state) || !hasSomething(d) || d.since - this.mineAt < YIELD_MS) return;
    const here = this.player.snapshot();
    if (!wants(here) || !has(here)) return;
    this.player.pause();
    setTimeout(() => this.localChanged(), 50);
  }

  private ping(id: string, now: number): void {
    const before = this.pinged.get(id);
    this.pinged.set(id, { at: now, unanswered: (before?.unanswered ?? 0) + 1 });
    this.send({ type: 'ping', from: this.myId, seq: ++this.seq, at: now, to: id, c: now });
  }

  private clockHeard(from: string, c: number, h: number, now: number): void {
    const rtt = now - c;
    if (!(rtt >= 0 && rtt <= MAX_RTT_MS)) return;
    const p = this.pinged.get(from);
    if (p) this.pinged.set(from, { at: p.at, unanswered: 0 });
    // The answer was made about halfway through the round trip.
    const sample = { offset: h - (c + now) / 2, rtt, at: now };
    const best = this.offsets.get(from);
    if (!best || rtt <= best.rtt || now - best.at > OFFSET_KEEP_MS) this.offsets.set(from, sample);
  }

  private apply(c: Command): boolean {
    switch (c.action) {
      case 'play': this.player.play(); return true;
      case 'pause': this.player.pause(); return true;
      case 'next': this.player.next(); return true;
      case 'previous': this.player.previous(); return true;
      case 'seek': this.player.seekTo(c.pos ?? 0); return true;
      case 'volume': this.player.setVolume(c.value ?? 1); return true;
      case 'shuffle': this.player.setShuffle((c.value ?? 0) > 0.5); return true;
      case 'repeat':
        if (c.mode !== 'off' && c.mode !== 'all' && c.mode !== 'one') return false;
        this.player.setRepeat(c.mode);
        return true;
      case 'playTrack': if (!c.track) return false; this.player.playTrack(c.track); return true;
      case 'addToQueue': this.player.addToQueue(c.tracks ?? []); return true;
      case 'jump': return !!c.track && this.player.playQueued(c.pos ?? 0, c.track.id);
      case 'remove': return !!c.track && this.player.removeQueued(c.pos ?? 0, c.track.id);
      case 'move': return !!c.track && !!this.player.moveQueued?.(c.pos ?? 0, Math.round(c.value ?? 0), c.track.id);
      default: return false;
    }
  }

  // ---- Out -----------------------------------------------------------------------------------

  private command(
    to: string,
    action: string,
    o: { pos?: number; value?: number; track?: CTrack; tracks?: CTrack[]; mode?: string; quiet?: boolean; onAck?: (a: Ack) => void } = {},
  ): void {
    const id = newId();
    this.send({
      type: 'command', from: this.myId, seq: ++this.seq, at: this.clock(), to, id, action,
      ...(o.pos ? { pos: Math.round(o.pos) } : {}),
      ...(o.value ? { value: o.value } : {}),
      ...(o.track ? { track: o.track } : {}),
      ...(o.mode ? { mode: o.mode } : {}),
      ...(o.tracks?.length ? { tracks: o.tracks } : {}),
    });
    if (!o.quiet) this.expect(id, to, this.timings.commandMs, o.onAck ?? (() => {}));
  }

  /** Waits for the answer to [id]; says so if none comes in [ms], unless [answered] says the device did it all the same (its answer lost). */
  private expect(id: string, to: string, ms: number, onAck: (a: Ack) => void, answered: () => boolean = () => false): void {
    const timer = setTimeout(() => {
      if (!this.pending.delete(id)) return;
      this.refresh();
      if (answered()) return onAck({ type: 'ack', from: to, seq: 0, at: this.clock(), to: this.myId, id, ok: true });
      this.notice(NOT_ANSWERING, this.nameOf(to));
      onAck({ type: 'ack', from: to, seq: 0, at: this.clock(), to: this.myId, id, ok: false, reason: 'timeout' });
    }, ms);
    this.pending.set(id, { to, timer, onAck });
    this.refresh();
  }

  private tick(): void {
    const now = this.clock();
    let gone = false;
    for (const [id, d] of this.devices) {
      if (now - d.lastSeen <= this.timings.goneMs) continue;
      this.devices.delete(id);
      this.offsets.delete(id);
      this.pinged.delete(id);
      gone = true;
    }
    if (gone) this.refresh();
    if (now - this.lastPresenceSent >= this.timings.presenceMs) this.sayPresence();
    if (this.mine?.playing && now - this.lastStateSent >= this.timings.stateMs) this.sayState();
    // Each device's clock, measured again now and then; a few quick tries for one not yet measured
    // (a device too old to answer is left at the slow pace after those).
    for (const d of this.devices.values()) {
      const p = this.pinged.get(d.id);
      const quick = !this.offsets.has(d.id) && (p?.unanswered ?? 0) < PING_TRIES;
      const wait = quick ? Math.min(this.timings.presenceMs, PING_RETRY_MS) : this.timings.presenceMs;
      if (!p || now - p.at >= wait) this.ping(d.id, now);
    }
  }

  private sayPresence(): void {
    this.lastPresenceSent = this.clock();
    this.send({ type: 'presence', from: this.myId, seq: ++this.seq, at: this.clock(), name: this.myName, form: this.form, active: this.view.activeId === this.myId });
  }

  private sayState(force = false): void {
    const s = this.player.snapshot();
    if (!s) return;
    this.mine = s;
    if (!s.track && !s.private && !force) return;
    this.lastStateSent = this.clock();
    this.send({
      type: 'state', from: this.myId, seq: ++this.seq, at: this.clock(),
      track: s.track, private: s.private, playing: s.playing, pos: Math.round(s.pos), queue: s.queue.slice(0, MAX_QUEUE),
      volume: s.volume, shuffle: s.shuffle, repeat: s.repeat,
      ...(this.mineAt > 0 ? { since: this.mineAt } : {}),
      ...(s.quality ? { quality: s.quality } : {}),
      wants: wants(s),
    });
  }

  /** A device heard in a former group: told, at most once a minute, to come over to this one. */
  private forward(msg: ConnectMsg, old: ConnectGroup): void {
    if (this.stopped || msg.from === this.myId || msg.type === 'leave') return;
    const key = `${old.tag}/${msg.from}`;
    const now = this.clock();
    if (now - (this.forwarded.get(key) ?? 0) < FORWARD_MS) return;
    this.forwarded.set(key, now);
    this.send({ type: 'leave', from: this.myId, seq: ++this.seq, at: now, next: this.group.encoded }, old);
  }

  private send(m: ConnectMsg, to: ConnectGroup = this.group): void {
    if (this.stopped) return;
    this.sending = this.sending.then(async () => {
      try {
        const event = await signedEvent(this.key, KIND, [['t', to.tag]], await fit(m, to));
        this.pool?.publish(event);
      } catch {
        // Best effort, like the rest of the relays' traffic.
      }
    });
  }

  // ---- The view ------------------------------------------------------------------------------

  private touch(id: string, now: number): void {
    const d = this.devices.get(id);
    if (d) this.devices.set(id, { ...d, lastSeen: now });
  }

  private setDevice(d: Device): void {
    this.devices.set(d.id, d);
    this.refresh();
  }

  private update(part: Partial<ConnectView>): void {
    this.view = { ...this.view, ...part };
    this.publish(this.view);
  }

  private refresh(): void {
    const others = [...this.devices.values()];
    this.update({ devices: others, activeId: this.active(others), pending: [...this.pending.keys()] });
  }

  /**
   * Whichever device is meant to play (the latest to take the music, if
   * several), stalled to load or not; else the one that took it last. A stall
   * isn't a pause: judged by moving, a device loading a song it was just
   * handed lost the music to the one that handed it over, and the buttons
   * pressed meanwhile went there.
   */
  private active(others: Device[]): string | null {
    const m = this.mine;
    const meHas = has(m);
    const newest = (list: Device[]) => list.reduce<Device | null>((a, b) => (!a || b.since > a.since ? b : a), null);
    const playing = newest(others.filter((d) => hasSomething(d) && meant(d.state)));
    if (meHas && wants(m) && (!playing || this.mineAt >= playing.since)) return this.myId;
    if (playing) return playing.id;
    const latest = newest(others.filter(hasSomething));
    if (meHas && (!latest || this.mineAt >= latest.since)) return this.myId;
    return latest?.id ?? null;
  }

  private nameOf(id: string): string {
    if (id === this.myId) return this.myName;
    return this.devices.get(id)?.name || 'The other device';
  }
}

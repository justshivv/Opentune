import { newPrivateKey, publicKeyOf } from '@/together/crypto';
import { RelayPool, signedEvent, type NostrEvent } from '@/together/nostr';
import { RELAYS, type Transport, type TransportFactory } from '@/together/session';
import type { ConnectGroup } from './group';
import { decode, fit, MAX_QUEUE, type Ack, type Command, type ConnectMsg, type CTrack, type StateMsg } from './messages';

export const KIND = 21421;
export const NOT_ANSWERING = "{0} isn't answering";
export const CANT_PLAY = "Can't play this on {0}";

/** What Connect needs from this device's player; ConnectPlayer on the phone. */
export interface ConnectPlayer {
  snapshot(): Snapshot | null;
  play(): void;
  pause(): void;
  next(): void;
  previous(): void;
  seekTo(ms: number): void;
  setVolume(v: number): void;
  playTrack(t: CTrack): void;
  addToQueue(ts: CTrack[]): void;
  /** Loads [queue] from [index] at [pos] ms; false when none of it can play here. */
  load(queue: CTrack[], index: number, pos: number, playing: boolean): boolean;
}

export interface Snapshot {
  track: CTrack | null;
  private: boolean;
  playing: boolean;
  pos: number;
  /** What comes after [track]. */
  queue: CTrack[];
  volume: number;
  shuffle: boolean;
  repeat: string;
}

export interface Device {
  id: string;
  name: string;
  form: string;
  lastSeen: number;
  state: StateMsg | null;
  /** This device's clock when [state] came. */
  receivedAt: number;
}

export const positionAt = (d: Device, now: number) => (d.state ? (d.state.playing ? (d.state.pos ?? 0) + (now - d.receivedAt) : (d.state.pos ?? 0)) : 0);
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
const DEFAULT_TIMINGS: Timings = { presenceMs: 30_000, goneMs: 75_000, stateMs: 5_000, commandMs: 5_000, transferMs: 8_000, tickMs: 1_000 };

const relays: TransportFactory = (filter, onEvent, onConnected) => new RelayPool(RELAYS, filter, onEvent, onConnected);
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
  private readonly lastSeq = new Map<string, number>();
  private readonly devices = new Map<string, Device>();
  private readonly pending = new Map<string, Pending>();
  private mine: Snapshot | null = null;
  private mineAt = 0;
  private lastStateSent = 0;
  private lastPresenceSent = 0;
  private ticker: ReturnType<typeof setInterval> | null = null;
  private sending: Promise<void> = Promise.resolve();
  private reading: Promise<void> = Promise.resolve();
  private stopped = false;
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
  ) {
    this.view = { myId, myName, relays: 0, devices: [], activeId: null, pending: [] };
  }

  start(): void {
    // Numbered from the clock, so a restarted device's messages still count as newer than its last ones.
    this.seq = Math.max(this.seq, this.clock());
    const filter = { kinds: [KIND], '#t': [this.group.tag], since: Math.floor(this.clock() / 1000) - 10 };
    this.pool = this.transport(filter, (e) => this.onEvent(e), (n) => this.update({ relays: n }));
    this.pool.start();
    this.ticker = setInterval(() => this.tick(), this.timings.tickMs);
    this.mine = this.player.snapshot();
    if (this.mine && (this.mine.track || this.mine.private)) this.mineAt = this.clock();
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
    const started = now.playing && !before?.playing;
    if (now.track?.id !== before?.track?.id || now.playing !== before?.playing || now.private !== before?.private) this.mineAt = this.clock();
    if (started) for (const d of this.devices.values()) if (d.state?.playing) this.command(d.id, 'pause', { quiet: true });
    this.sayState();
    this.refresh();
  }

  /** A button pressed here while another device plays: sends it there. True when it went. */
  intercept(action: string, pos = 0, value = 0): boolean {
    const target = controllingOf(this.view);
    if (!target) return false;
    const shown = target.state;
    if (shown) {
      const now = this.clock();
      const guess: StateMsg | null =
        action === 'play' ? { ...shown, playing: true, pos: positionAt(target, now) }
        : action === 'pause' ? { ...shown, playing: false, pos: positionAt(target, now) }
        : action === 'seek' ? { ...shown, pos }
        : action === 'volume' ? { ...shown, volume: value }
        : null;
      if (guess) this.setDevice({ ...target, state: guess, receivedAt: now });
    }
    this.command(target.id, action, {
      pos,
      value,
      onAck: (ack) => {
        if (!ack.ok && shown) this.setDevice({ ...target, state: shown });
      },
    });
    return true;
  }

  playTrackOn(deviceId: string, track: CTrack): void {
    this.command(deviceId, 'playTrack', { track });
  }

  addToQueueOn(deviceId: string, tracks: CTrack[]): void {
    this.command(deviceId, 'addToQueue', { tracks });
  }

  /** Moves playback to [deviceId]; this device's own id pulls it here. */
  transferTo(deviceId: string): void {
    const activeId = this.view.activeId;
    if (!activeId || deviceId === activeId) return;
    if (activeId === this.myId) {
      const snap = this.player.snapshot();
      if (!snap?.track) return;
      const id = newId();
      this.send({ type: 'transfer', from: this.myId, seq: ++this.seq, at: this.clock(), to: deviceId, id, queue: [snap.track, ...snap.queue], index: 0, pos: Math.round(snap.pos), playing: snap.playing });
      this.expect(id, deviceId, this.timings.transferMs, (ack) => {
        if (ack.ok) this.player.pause();
        else this.notice(CANT_PLAY, this.nameOf(deviceId));
      });
      return;
    }
    const source = this.devices.get(activeId);
    const state = source?.state;
    if (!source || !state) return;
    if (!state.track) return this.notice(CANT_PLAY, this.nameOf(deviceId));
    const queue = [state.track, ...(state.queue ?? [])];
    const pos = Math.round(positionAt(source, this.clock()));
    const playing = !!state.playing;
    if (deviceId === this.myId) {
      if (this.player.load(queue, 0, pos, playing)) {
        this.command(source.id, 'pause', { quiet: true });
        this.localChanged();
      } else this.notice(CANT_PLAY, this.myName);
      return;
    }
    const id = newId();
    this.send({ type: 'transfer', from: this.myId, seq: ++this.seq, at: this.clock(), to: deviceId, id, queue, index: 0, pos, playing });
    this.expect(id, deviceId, this.timings.transferMs, (ack) => {
      if (ack.ok) this.command(source.id, 'pause', { quiet: true });
      else this.notice(CANT_PLAY, this.nameOf(deviceId));
    });
  }

  // ---- In ------------------------------------------------------------------------------------

  private onEvent(event: NostrEvent): void {
    if (event.pubkey === this.myPub) return;
    // Opened in the order they came, as the phone does.
    this.reading = this.reading.then(async () => {
      const plain = await this.group.open(event.content);
      const msg = plain ? decode(plain) : null;
      if (msg && !this.stopped) this.handle(msg);
    });
  }

  handle(msg: ConnectMsg): void {
    if (msg.from === this.myId) return;
    const last = this.lastSeq.get(msg.from);
    if (last !== undefined && msg.seq <= last) return;
    this.lastSeq.set(msg.from, msg.seq);
    const now = this.clock();
    const known = this.devices.get(msg.from);
    switch (msg.type) {
      case 'presence': {
        const base = known ?? { id: msg.from, name: msg.name, form: msg.form, lastSeen: now, state: null, receivedAt: 0 };
        this.setDevice({ ...base, name: msg.name, form: msg.form, lastSeen: now });
        if (!known) {
          this.sayPresence();
          this.sayState(true);
        }
        return;
      }
      case 'state': {
        const base = known ?? { id: msg.from, name: '', form: '', lastSeen: now, state: null, receivedAt: 0 };
        this.setDevice({ ...base, lastSeen: now, state: msg, receivedAt: now });
        if (!known) this.sayPresence();
        return;
      }
      case 'leave':
        this.devices.delete(msg.from);
        this.refresh();
        if (typeof msg.next === 'string') this.moved(msg.next);
        return;
      case 'command': {
        this.touch(msg.from, now);
        if (msg.to !== this.myId) return;
        const ok = this.apply(msg);
        this.send({ type: 'ack', from: this.myId, seq: ++this.seq, at: now, to: msg.from, id: msg.id, ok, ...(ok ? {} : { reason: 'unplayable' }) });
        // Let the player settle before saying what it does now.
        setTimeout(() => this.localChanged(), 50);
        return;
      }
      case 'transfer': {
        this.touch(msg.from, now);
        if (msg.to !== this.myId) return;
        const late = Math.min(Math.max(now - msg.at, 0), 10_000);
        const ok = this.player.load(msg.queue ?? [], msg.index ?? 0, (msg.pos ?? 0) + late, msg.playing ?? true);
        this.send({ type: 'ack', from: this.myId, seq: ++this.seq, at: now, to: msg.from, id: msg.id, ok, ...(ok ? {} : { reason: 'unplayable' }) });
        if (ok) {
          this.mine = null;
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
      default:
        this.touch(msg.from, now);
    }
  }

  private apply(c: Command): boolean {
    switch (c.action) {
      case 'play': this.player.play(); return true;
      case 'pause': this.player.pause(); return true;
      case 'next': this.player.next(); return true;
      case 'previous': this.player.previous(); return true;
      case 'seek': this.player.seekTo(c.pos ?? 0); return true;
      case 'volume': this.player.setVolume(c.value ?? 1); return true;
      case 'playTrack': if (!c.track) return false; this.player.playTrack(c.track); return true;
      case 'addToQueue': this.player.addToQueue(c.tracks ?? []); return true;
      default: return false;
    }
  }

  // ---- Out -----------------------------------------------------------------------------------

  private command(
    to: string,
    action: string,
    o: { pos?: number; value?: number; track?: CTrack; tracks?: CTrack[]; quiet?: boolean; onAck?: (a: Ack) => void } = {},
  ): void {
    const id = newId();
    this.send({
      type: 'command', from: this.myId, seq: ++this.seq, at: this.clock(), to, id, action,
      ...(o.pos ? { pos: Math.round(o.pos) } : {}),
      ...(o.value ? { value: o.value } : {}),
      ...(o.track ? { track: o.track } : {}),
      ...(o.tracks?.length ? { tracks: o.tracks } : {}),
    });
    if (!o.quiet) this.expect(id, to, this.timings.commandMs, o.onAck ?? (() => {}));
  }

  private expect(id: string, to: string, ms: number, onAck: (a: Ack) => void): void {
    const timer = setTimeout(() => {
      if (!this.pending.delete(id)) return;
      this.refresh();
      this.notice(NOT_ANSWERING, this.nameOf(to));
      onAck({ type: 'ack', from: to, seq: 0, at: this.clock(), to: this.myId, id, ok: false, reason: 'timeout' });
    }, ms);
    this.pending.set(id, { to, timer, onAck });
    this.refresh();
  }

  private tick(): void {
    const now = this.clock();
    let gone = false;
    for (const [id, d] of this.devices) if (now - d.lastSeen > this.timings.goneMs) { this.devices.delete(id); gone = true; }
    if (gone) this.refresh();
    if (now - this.lastPresenceSent >= this.timings.presenceMs) this.sayPresence();
    if (this.mine?.playing && now - this.lastStateSent >= this.timings.stateMs) this.sayState();
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
    });
  }

  private send(m: ConnectMsg): void {
    if (this.stopped) return;
    this.sending = this.sending.then(async () => {
      try {
        const event = await signedEvent(this.key, KIND, [['t', this.group.tag]], await fit(m, this.group));
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

  /** Whichever device plays (the latest to start, if several); else the one that last had something loaded. */
  private active(others: Device[]): string | null {
    const m = this.mine;
    const meHas = !!m && (!!m.track || m.private);
    if (meHas && m!.playing) return this.myId;
    const newest = (list: Device[]) => list.reduce<Device | null>((a, b) => (!a || b.receivedAt > a.receivedAt ? b : a), null);
    const playing = newest(others.filter((d) => hasSomething(d) && d.state!.playing));
    if (playing) return playing.id;
    const latest = newest(others.filter(hasSomething));
    if (meHas && (!latest || this.mineAt >= latest.receivedAt)) return this.myId;
    return latest?.id ?? null;
  }

  private nameOf(id: string): string {
    if (id === this.myId) return this.myName;
    return this.devices.get(id)?.name || 'The other device';
  }
}

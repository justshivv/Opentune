import { newPrivateKey, publicKeyOf, RoomCode, RoomKey } from './crypto';
import { RelayPool, signedEvent, type NostrEvent } from './nostr';

/*
 * One device's part in one room: RoomSession.kt from the Android app, so a
 * browser can host phones or follow a phone. Every device fetches its own
 * audio; only "what, where, playing or not" travels, sealed with the room's
 * keys and signed with a key made for this session. Voice clips are sent by
 * the app only; here they show as a line in the chat.
 */

export const KIND = 21420;
export const RELAYS = [
  'wss://relay.primal.net',
  'wss://relay.snort.social',
  'wss://relay.damus.io',
  'wss://nos.lol',
  'wss://nostr.mom',
  'wss://relay.nostr.net',
  'wss://nostr-pub.wellorder.net',
  'wss://offchain.pub',
];

/** A song as a room passes it around: enough for another device to play it. */
export interface RoomTrack {
  id: string;
  title: string;
  artist: string;
  thumb?: string | null;
  length?: string | null;
  album?: string | null;
}
export interface Member {
  id: string;
  name: string;
  host?: boolean;
}

type State = {
  t: 'state'; seq: number; host: string; track?: RoomTrack | null; private?: boolean; playing?: boolean;
  pos?: number; at?: number; next?: RoomTrack[]; members?: Member[]; open?: boolean;
};
type Msg =
  | { t: 'hello'; name: string }
  | { t: 'here'; name: string }
  | { t: 'bye' }
  | State
  | { t: 'end' }
  | { t: 'ping'; c: number }
  | { t: 'pong'; c: number; h: number; to: string }
  | { t: 'ask'; action: string; pos?: number; track?: RoomTrack | null }
  | { t: 'chat'; text: string }
  | { t: 'pending'; to: string; host: string }
  | { t: 'admit'; to: string; key: string; epoch: number; host: string }
  | { t: 'deny'; to: string; removed?: boolean; host?: string }
  | { t: 'voice'; id: string; seq: number; last?: boolean; live?: boolean };

/** Whether [m] travels in the lobby, under the room code, rather than under the room key. */
const inLobby = (m: Msg) => ['hello', 'bye', 'pending', 'admit', 'deny', 'end'].includes(m.t);

export type Phase = 'connecting' | 'finding' | 'waiting' | 'live' | 'lost' | 'ended';
export interface ChatLine {
  name: string;
  text: string;
  mine?: boolean;
  system?: boolean;
  at: number;
}
export interface RoomView {
  code: string;
  hosting: boolean;
  myName: string;
  myId: string;
  phase: Phase;
  relays: number;
  members: Member[];
  hostName: string | null;
  open: boolean;
  chat: ChatLine[];
  track: RoomTrack | null;
  hostPrivate: boolean;
  holding: boolean;
  endedReason: string | null;
  requests: Member[];
  askToJoin: boolean;
}

/** What a room needs from the player. */
export interface RoomPlayer {
  currentId(): string | null;
  upcomingIds(): string[];
  playing(): boolean;
  buffering(): boolean;
  positionMs(): number;
  setRate(rate: number): void;
  play(): void;
  pause(): void;
  seekTo(ms: number): void;
  /** Puts [tracks] on the player, the first at [startMs], playing or not. */
  load(tracks: RoomTrack[], startMs: number, play: boolean): void;
  replaceUpcoming(tracks: RoomTrack[]): void;
  addToQueue(track: RoomTrack): void;
  playNow(track: RoomTrack): void;
  skipNext(): void;
  skipPrevious(): void;
  current(): RoomTrack | null;
  upcoming(): RoomTrack[];
}

/** Where a room's events go and come from: the relays, or a bus in a test. */
export interface Transport {
  start(): void;
  publish(event: NostrEvent): boolean;
  close(): void;
}
export type TransportFactory = (filter: Record<string, unknown>, onEvent: (e: NostrEvent) => void, onConnected: (n: number) => void) => Transport;
const relays: TransportFactory = (filter, onEvent, onConnected) => new RelayPool(RELAYS, filter, onEvent, onConnected);

export type Control = { kind: 'toggle' } | { kind: 'next' } | { kind: 'previous' } | { kind: 'seek'; ms: number };

const HEARTBEAT_MS = 2_000;
const REQUEST_TIMEOUT_MS = 30_000;
const HERE_MS = 10_000;
const MEMBER_TIMEOUT_MS = 45_000;
const HOST_TIMEOUT_MS = 25_000;
const MAX_CHAT = 100;
const DRIFT_MS = 400;
const IN_STEP_MS = 25;
const MAX_NUDGE = 0.05;
const NUDGE_FULL_MS = 120;
const SYNC_TICK_MS = 250;
const SETTLE_MS = 500;
const LOCAL_MS = 1_500;

/** How far into the song a guest should be right now, given the host's last [s] and the clock gap. */
export function targetPosition(s: State, localNow: number, offsetMs: number): number {
  return !s.playing ? (s.pos ?? 0) : Math.max(0, (s.pos ?? 0) + (localNow + offsetMs - (s.at ?? 0)));
}
/** The host clock's lead over ours from one ping: it answered at [hostAt], taken as halfway through the round trip. */
export const clockOffset = (sentAt: number, hostAt: number, receivedAt: number) => hostAt - (sentAt + Math.floor((receivedAt - sentAt) / 2));

interface Seen {
  name: string;
  lastSeen: number;
}

export class RoomSession {
  private readonly key = newPrivateKey();
  readonly me = publicKeyOf(this.key);
  private roomKey: RoomKey | null = null;
  private epoch = 0;
  private readonly requests = new Map<string, Seen>();
  private readonly members = new Map<string, Seen>();
  private readonly dropped = new Map<string, string>();
  private pool: Transport | null = null;
  private timers: ReturnType<typeof setInterval>[] = [];
  private sending: Promise<void> = Promise.resolve();
  private reading: Promise<void> = Promise.resolve();
  private stopped = false;
  view: RoomView;

  // host
  private seq = 0;
  private open = false;
  private lastSent: State | null = null;
  private lastSentLocal = 0;
  private stateTimer: ReturnType<typeof setTimeout> | null = null;

  // guest
  private hostKey: string | null = null;
  private last: State | null = null;
  private lastAt = 0;
  private offset = 0;
  private bestRtt = Number.MAX_SAFE_INTEGER;
  private settleUntil = 0;
  private holding = false;
  private rate = 1;
  private seekLead = 150;
  private measureSeek = false;
  private localUntil = 0;
  private lastHere = 0;

  constructor(
    private readonly code: RoomCode,
    readonly myName: string,
    readonly hosting: boolean,
    private readonly player: RoomPlayer,
    private readonly publish: (view: RoomView) => void,
    private readonly notice: (text: string) => void,
    private askToJoin: boolean,
    private readonly transport: TransportFactory = relays,
  ) {
    this.view = {
      code: code.raw, hosting, myName, myId: this.me, phase: 'connecting', relays: 0, members: [], hostName: hosting ? myName : null,
      open: false, chat: [], track: null, hostPrivate: false, holding: false, endedReason: null, requests: [], askToJoin,
    };
  }

  async start(): Promise<void> {
    if (this.hosting) this.roomKey = await RoomKey.random(this.code.tag);
    const filter = { kinds: [KIND], '#t': [this.code.tag], since: Math.floor(Date.now() / 1000) - 10 };
    this.pool = this.transport(filter, (e) => this.receive(e), (n) => this.connected(n));
    this.pool.start();
    if (this.hosting) this.startHost();
    else this.startGuest();
  }

  private connected(n: number) {
    const phase = this.view.phase === 'connecting' && n > 0 ? (this.hosting ? 'live' : 'finding') : this.view.phase;
    this.update({ relays: n, phase });
  }

  stop(saying: boolean): void {
    if (this.stopped) return;
    if (saying) this.send(this.hosting ? { t: 'end' } : { t: 'bye' });
    this.stopped = true;
    this.setRate(1);
    this.timers.forEach(clearInterval);
    if (this.stateTimer) clearTimeout(this.stateTimer);
    // Let the goodbye go out first.
    const pool = this.pool;
    void this.sending.finally(() => setTimeout(() => pool?.close(), 600));
  }

  private update(change: Partial<RoomView>) {
    this.view = { ...this.view, ...change };
    this.publish(this.view);
  }

  private addChat(line: Omit<ChatLine, 'at'>) {
    this.update({ chat: [...this.view.chat, { ...line, at: Date.now() }].slice(-MAX_CHAT) });
  }

  private system(text: string) {
    this.addChat({ name: '', text, system: true });
  }

  /** Sealed and signed in order, one at a time, as the app's outbox. */
  private send(m: Msg) {
    if (this.stopped) return;
    this.sending = this.sending.then(async () => {
      const text = JSON.stringify(m);
      const content = inLobby(m) ? await this.code.seal(text) : this.roomKey ? await this.roomKey.seal(text) : null;
      if (!content) return; // a guest not let in yet has nothing to say in the room
      const event = await signedEvent(this.key, KIND, [['t', this.code.tag]], content);
      this.pool?.publish(event);
    }).catch(() => undefined);
  }

  private receive(event: NostrEvent) {
    if (event.pubkey === this.me) return;
    this.reading = this.reading.then(async () => {
      if (this.stopped) return;
      let text = this.roomKey ? await this.roomKey.open(event.content) : null;
      const inRoom = text !== null;
      if (text === null) text = await this.code.open(event.content);
      if (text === null) return;
      let msg: Msg;
      try {
        msg = JSON.parse(text) as Msg;
      } catch {
        return;
      }
      if (!msg || typeof msg.t !== 'string') return;
      // Only knocking and the host's answers travel under the code.
      if (!inRoom && !inLobby(msg)) return;
      if (this.hosting) await this.hostHandle(event.pubkey, msg);
      else await this.guestHandle(event.pubkey, msg);
    }).catch(() => undefined);
  }

  private setRate(r: number) {
    if (Math.abs(r - this.rate) < 0.005 && !(r === 1 && this.rate !== 1)) return;
    this.rate = r;
    this.player.setRate(r);
  }

  // ---------------- host ----------------

  private startHost() {
    this.update({ hostName: this.myName, members: this.memberList(), askToJoin: this.askToJoin });
    this.system('Room open. Share the code so friends can join.');
    this.timers.push(setInterval(() => {
      const now = Date.now();
      const sent = this.lastSent;
      const jumped = !!sent?.track && Math.abs(this.player.positionMs() - targetPosition(sent, now, 0)) > 2_000;
      if (jumped || now - this.lastSentLocal > HEARTBEAT_MS) this.sendState();
      let changed = false;
      for (const [id, r] of this.requests) if (now - r.lastSeen > REQUEST_TIMEOUT_MS) { this.requests.delete(id); changed = true; }
      if (changed) this.update({ requests: this.requestList() });
      const gone = [...this.members].filter(([, m]) => now - m.lastSeen > MEMBER_TIMEOUT_MS);
      if (gone.length) {
        for (const [id, m] of gone) {
          this.members.delete(id);
          this.dropped.set(id, m.name);
          this.system(`${m.name} left`);
        }
        while (this.dropped.size > 50) this.dropped.delete(this.dropped.keys().next().value!);
        this.update({ members: this.memberList() });
      }
    }, 500));
  }

  /** The player changed: one state for a burst of changes. */
  playerChanged() {
    if (!this.hosting || this.stopped) return;
    if (this.stateTimer) clearTimeout(this.stateTimer);
    this.stateTimer = setTimeout(() => this.sendState(), 40);
  }

  private memberList(): Member[] {
    return [{ id: this.me, name: this.myName, host: true }, ...[...this.members].map(([id, m]) => ({ id, name: m.name }))];
  }
  private requestList(): Member[] {
    return [...this.requests].map(([id, m]) => ({ id, name: m.name }));
  }

  private async admit(id: string) {
    const wrapped = await this.roomKey?.wrapFor(this.key, id).catch(() => null);
    if (wrapped) this.send({ t: 'admit', to: id, key: wrapped, epoch: this.epoch, host: this.myName });
  }

  private async letIn(id: string, name: string, now: number) {
    this.requests.delete(id);
    this.members.set(id, { name, lastSeen: now });
    await this.admit(id);
    this.system(`${name} joined`);
    this.update({ members: this.memberList(), requests: this.requestList() });
    this.sendState();
  }

  private async rejoin(id: string, name: string, now: number) {
    this.dropped.delete(id);
    this.requests.delete(id);
    this.members.set(id, { name, lastSeen: now });
    await this.admit(id);
    this.system(`${name} is back`);
    this.update({ members: this.memberList(), requests: this.requestList() });
    this.sendState();
  }

  approve(id: string) {
    const r = this.requests.get(id);
    if (this.hosting && r) void this.letIn(id, r.name, Date.now());
  }

  reject(id: string) {
    if (!this.hosting || !this.requests.delete(id)) return;
    this.send({ t: 'deny', to: id, host: this.myName });
    this.update({ requests: this.requestList() });
  }

  /** Puts [id] out: the room moves to a new key, handed to everyone else. */
  async remove(id: string) {
    const m = this.members.get(id);
    if (!this.hosting || !m) return;
    this.members.delete(id);
    this.roomKey = await RoomKey.random(this.code.tag);
    this.epoch++;
    this.send({ t: 'deny', to: id, removed: true, host: this.myName });
    for (const other of this.members.keys()) await this.admit(other);
    this.system(`${m.name} was removed`);
    this.update({ members: this.memberList() });
    this.sendState();
  }

  setAskToJoin(on: boolean) {
    if (!this.hosting) return;
    this.askToJoin = on;
    this.update({ askToJoin: on });
    if (!on) for (const [id, r] of [...this.requests]) void this.letIn(id, r.name, Date.now());
  }

  setOpen(on: boolean) {
    if (!this.hosting) return;
    this.open = on;
    this.update({ open: on });
    this.sendState();
  }

  private sendState() {
    const p = this.player;
    const track = p.current();
    const s: State = {
      t: 'state', seq: ++this.seq, host: this.myName, track, private: !!p.currentId() && !track,
      playing: p.playing() && !p.buffering(), pos: Math.round(p.positionMs()), at: Date.now(),
      next: p.upcoming().slice(0, 10), members: this.memberList(), open: this.open,
    };
    this.lastSent = s;
    this.lastSentLocal = Date.now();
    this.update({ track, hostPrivate: !!s.private, open: this.open, members: s.members ?? [] });
    this.send(s);
  }

  private async hostHandle(from: string, msg: Msg) {
    const now = Date.now();
    const p = this.player;
    if (!inLobby(msg) && !this.members.has(from)) {
      const name = this.dropped.get(from);
      if (name === undefined) return;
      await this.rejoin(from, name, now);
    }
    const member = this.members.get(from);
    if (member) member.lastSeen = now;
    switch (msg.t) {
      case 'hello': {
        const name = String(msg.name ?? 'Someone').slice(0, 30);
        if (member) {
          await this.admit(from);
          this.sendState();
        } else if (this.dropped.has(from)) await this.rejoin(from, name, now);
        else if (!this.askToJoin) await this.letIn(from, name, now);
        else {
          const known = this.requests.get(from);
          if (!known) {
            this.requests.set(from, { name, lastSeen: now });
            this.update({ requests: this.requestList() });
            this.notice(`${name} wants to join`);
          } else known.lastSeen = now;
          this.send({ t: 'pending', to: from, host: this.myName });
        }
        break;
      }
      case 'bye':
        if (this.requests.delete(from)) this.update({ requests: this.requestList() });
        else if (member) {
          this.members.delete(from);
          this.system(`${member.name} left`);
          this.update({ members: this.memberList() });
          this.sendState();
        }
        break;
      case 'ping':
        this.send({ t: 'pong', c: msg.c, h: now, to: from });
        break;
      case 'ask': {
        const who = member?.name ?? 'Someone';
        const t = msg.track;
        if (msg.action === 'add' || (msg.action === 'now' && !this.open)) {
          if (!t) return;
          p.addToQueue(t);
          this.system(`${who} added ${t.title}`);
          return;
        }
        if (msg.action === 'now') {
          if (!t) return;
          p.playNow(t);
          this.system(`${who} played ${t.title}`);
          return;
        }
        if (!this.open) return;
        if (msg.action === 'play') p.play();
        else if (msg.action === 'pause') p.pause();
        else if (msg.action === 'seek') p.seekTo(msg.pos ?? 0);
        else if (msg.action === 'next') p.skipNext();
        else if (msg.action === 'prev') p.skipPrevious();
        this.playerChanged();
        // What a guest did to the room's music shows in its activity, as a song they add does. Seeks
        // come many to a drag, so they aren't listed.
        const did = ({ play: 'resumed the music', pause: 'paused the music', next: 'skipped to the next song', prev: 'went back a song' } as Record<string, string>)[msg.action];
        if (did) this.system(`${who} ${did}`);
        break;
      }
      case 'chat':
        this.addChat({ name: member?.name ?? 'Someone', text: String(msg.text).slice(0, 300) });
        break;
      case 'voice':
        if (msg.last && !msg.live) this.addChat({ name: member?.name ?? 'Someone', text: 'sent a voice note (it plays in the Android app)' });
        break;
    }
  }

  // ---------------- guest ----------------

  private startGuest() {
    // Knock until the host lists this device, then check in now and then.
    const knock = () => {
      const now = Date.now();
      const listed = !!this.roomKey && !!this.last?.members?.some((m) => m.id === this.me);
      if (this.view.relays > 0) {
        if (!listed || this.view.phase === 'lost') this.send({ t: 'hello', name: this.myName });
        else if (now - this.lastHere >= HERE_MS) {
          this.send({ t: 'here', name: this.myName });
          this.lastHere = now;
        }
      }
    };
    this.timers.push(setInterval(knock, 2_000));
    // Clock checks: a quick burst once the host is known, keeping the fastest round trip, then every 15 s.
    let burst = 0;
    let lastPing = 0;
    this.timers.push(setInterval(() => {
      const now = Date.now();
      if (!this.hostKey || (burst >= 6 && now - lastPing < 15_000)) return;
      burst++;
      lastPing = now;
      this.send({ t: 'ping', c: now });
    }, 300));
    this.timers.push(setInterval(() => {
      if (this.view.phase === 'live' && Date.now() - this.lastAt > HOST_TIMEOUT_MS) {
        this.update({ phase: 'lost' });
        this.notice('Lost touch with the host');
        this.setRate(1);
      }
      if (this.view.phase === 'live' && this.last) this.follow(this.last, false);
    }, SYNC_TICK_MS));
  }

  private async guestHandle(from: string, msg: Msg) {
    const now = Date.now();
    switch (msg.t) {
      case 'pending':
        if (msg.to === this.me && !this.roomKey && (!this.hostKey || from === this.hostKey)) {
          this.hostKey = from;
          this.update({ phase: 'waiting', hostName: msg.host });
        }
        break;
      case 'admit':
        if (msg.to === this.me && (!this.hostKey || from === this.hostKey) && msg.epoch >= this.epoch) {
          const k = await RoomKey.unwrap(msg.key, this.key, from, this.code.tag);
          if (!k) return;
          this.hostKey = from;
          this.roomKey = k;
          this.epoch = msg.epoch;
          if (this.view.phase === 'waiting' || this.view.phase === 'connecting') this.update({ phase: 'finding', hostName: msg.host });
        }
        break;
      case 'deny':
        if (msg.to === this.me && (!this.hostKey || from === this.hostKey)) {
          const host = msg.host || this.view.hostName || 'The host';
          this.update({ phase: 'ended', endedReason: msg.removed ? `${host} removed you from the room` : `${host} didn't let you in` });
          this.stop(false);
        }
        break;
      case 'state': {
        if (!this.hostKey) this.hostKey = from;
        if (from !== this.hostKey || (this.last?.seq ?? -1) >= msg.seq) return;
        const first = !this.last;
        const before = new Set(this.last?.members?.map((m) => m.id) ?? []);
        this.last = msg;
        this.lastAt = now;
        const members = msg.members ?? [];
        const withMe = members.some((m) => m.id === this.me) ? members : [...members, { id: this.me, name: this.myName }];
        const previous = this.view.members;
        this.update({ phase: 'live', hostName: msg.host, members: withMe, open: !!msg.open, track: msg.track ?? null, hostPrivate: !!msg.private });
        if (first) this.system(`You joined ${msg.host}'s room`);
        else {
          for (const m of members) if (!before.has(m.id) && m.id !== this.me) this.system(`${m.name} joined`);
          const ids = new Set(members.map((m) => m.id));
          for (const m of previous) if (before.has(m.id) && !ids.has(m.id) && m.id !== this.me) this.system(`${m.name} left`);
        }
        this.follow(msg, true);
        break;
      }
      case 'end':
        if (from === this.hostKey) {
          this.update({ phase: 'ended', endedReason: `${this.view.hostName ?? 'The host'} ended the room` });
          this.stop(false);
        }
        break;
      case 'pong':
        if (from === this.hostKey && msg.to === this.me) {
          const rtt = now - msg.c;
          if (rtt >= 0 && rtt <= 10_000 && rtt <= this.bestRtt + 50) {
            this.bestRtt = Math.min(this.bestRtt, rtt);
            this.offset = clockOffset(msg.c, msg.h, now);
          }
        }
        break;
      case 'chat':
        this.addChat({ name: this.nameOf(from), text: String(msg.text).slice(0, 300) });
        break;
      case 'voice':
        if (msg.last && !msg.live) this.addChat({ name: this.nameOf(from), text: 'sent a voice note (it plays in the Android app)' });
        break;
    }
  }

  /** Brings this device in line with the host's [s]: the same song, playing or not, and the same spot. */
  private follow(s: State, fresh: boolean) {
    const p = this.player;
    const now = Date.now();
    if (now < this.localUntil) {
      if (this.agrees(s, now)) this.localUntil = 0;
      else return;
    }
    const track = s.track;
    if (!track) {
      this.setRate(1);
      if (s.private && p.playing() && !this.holding) p.pause();
      return;
    }
    const target = targetPosition(s, now, this.offset);
    if (p.currentId() !== track.id) {
      if (!fresh && now < this.settleUntil) return;
      this.setRate(1);
      p.load([track, ...(s.next ?? [])], s.playing ? target + this.seekLead : target, !!s.playing && !this.holding);
      this.settleUntil = now + SETTLE_MS * 4; // a new song has to load first
      this.measureSeek = false;
      return;
    }
    const after = p.upcomingIds();
    const next = (s.next ?? []).map((t) => t.id);
    if (fresh && after.slice(0, next.length).join() !== next.join()) p.replaceUpcoming(s.next ?? []);
    if (this.holding) return this.setRate(1);
    if (s.playing) {
      if (!p.playing()) p.play();
      if (p.buffering() || now < this.settleUntil) return;
      const drift = p.positionMs() - target;
      if (this.measureSeek) {
        this.measureSeek = false;
        this.seekLead = Math.min(3_000, Math.max(0, this.seekLead - drift));
      }
      if (Math.abs(drift) > DRIFT_MS) {
        this.setRate(1);
        p.seekTo(target + this.seekLead);
        this.settleUntil = now + SETTLE_MS;
        this.measureSeek = true;
      } else if (Math.abs(drift) > IN_STEP_MS) this.setRate(1 - MAX_NUDGE * Math.max(-1, Math.min(1, drift / NUDGE_FULL_MS)));
      else this.setRate(1);
    } else {
      this.setRate(1);
      if (p.playing()) p.pause();
      if (Math.abs(p.positionMs() - (s.pos ?? 0)) > 100) p.seekTo(s.pos ?? 0);
    }
  }

  private agrees(s: State, now: number): boolean {
    const p = this.player;
    return s.track?.id === p.currentId() && !!s.playing === p.playing() && Math.abs(targetPosition(s, now, this.offset) - p.positionMs()) < 1_500;
  }

  /** For a guest following a room: a control goes to the host instead. True when the room took it. */
  intercept(control: Control): boolean {
    if (this.hosting || this.view.phase !== 'live') return false;
    const p = this.player;
    if (this.view.open) {
      let ask: Msg;
      if (control.kind === 'toggle') {
        if (p.playing()) { p.pause(); ask = { t: 'ask', action: 'pause' }; } else { p.play(); ask = { t: 'ask', action: 'play' }; }
      } else if (control.kind === 'next') ask = { t: 'ask', action: 'next' };
      else if (control.kind === 'previous') ask = { t: 'ask', action: 'prev' };
      else { p.seekTo(control.ms); ask = { t: 'ask', action: 'seek', pos: Math.round(control.ms) }; }
      this.setRate(1);
      this.localUntil = Date.now() + LOCAL_MS;
      this.send(ask);
      return true;
    }
    if (control.kind === 'toggle') {
      // Without control, play/pause is just for you; the room plays on.
      this.holding = !this.holding;
      this.update({ holding: this.holding });
      if (this.holding) {
        p.pause();
        this.notice('Paused for you. The room keeps playing.');
      } else {
        this.settleUntil = 0;
        if (this.last) this.follow(this.last, true);
      }
    } else this.notice(`Only ${this.view.hostName ?? 'the host'} can change the song`);
    return true;
  }

  /** A song picked here: the host queues it; a guest sends it to the room, or with [now] and control, plays it for everyone. */
  suggest(track: RoomTrack, now: boolean): boolean {
    if (this.hosting) return false;
    if (this.view.phase !== 'live') return false;
    if (now && this.view.open) {
      this.send({ t: 'ask', action: 'now', track });
      this.notice(`Playing "${track.title}" for everyone`);
    } else {
      this.send({ t: 'ask', action: 'add', track });
      this.notice(`Sent "${track.title}" to the room`);
    }
    return true;
  }

  say(text: string) {
    const t = text.trim().slice(0, 300);
    if (!t) return;
    if (!this.roomKey) return this.notice("You can chat once you're let in");
    this.send({ t: 'chat', text: t });
    this.addChat({ name: this.myName, text: t, mine: true });
  }

  private nameOf(id: string): string {
    return this.view.members.find((m) => m.id === id)?.name ?? 'Someone';
  }
}

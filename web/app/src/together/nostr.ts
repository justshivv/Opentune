import { publicKeyOf, sha256, signId, toHex } from './crypto';

/** One Nostr event (NIP-01), as relays pass them around. */
export interface NostrEvent {
  id: string;
  pubkey: string;
  created_at: number;
  kind: number;
  tags: string[][];
  content: string;
  sig: string;
}

/** Builds and signs an event; [privateKey] is this session's key. */
export async function signedEvent(privateKey: Uint8Array, kind: number, tags: string[][], content: string): Promise<NostrEvent> {
  const pubkey = publicKeyOf(privateKey);
  const created_at = Math.floor(Date.now() / 1000);
  // NIP-01's serialization is JSON's own escaping of [0, pubkey, created_at, kind, tags, content].
  const id = await sha256(JSON.stringify([0, pubkey, created_at, kind, tags, content]));
  return { id: toHex(id), pubkey, created_at, kind, tags, content, sig: signId(id, privateKey) };
}

function eventOf(value: unknown): NostrEvent | null {
  const e = value as NostrEvent;
  return e && typeof e.id === 'string' && typeof e.pubkey === 'string' && typeof e.content === 'string' && Array.isArray(e.tags) ? e : null;
}

/**
 * Several public relays at once, all carrying one subscription, as the app's
 * RelayPool: every event goes to every connected relay and each one coming in
 * is handed on once, so a room keeps working while any one relay does.
 * Dropped connections come back with growing pauses.
 */
export class RelayPool {
  private sockets = new Map<string, WebSocket>();
  private open = new Set<string>();
  private seen = new Set<string>();
  private closed = false;
  private timers = new Set<ReturnType<typeof setTimeout>>();

  constructor(
    private readonly urls: string[],
    private readonly filter: Record<string, unknown>,
    private readonly onEvent: (event: NostrEvent) => void,
    private readonly onConnected: (count: number) => void,
  ) {}

  start(): void {
    for (const url of this.urls) this.connect(url, 2_000);
  }

  private connect(url: string, backoff: number): void {
    if (this.closed) return;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      return this.retry(url, backoff);
    }
    this.sockets.set(url, ws);
    ws.onopen = () => {
      backoff = 2_000;
      this.open.add(url);
      this.onConnected(this.open.size);
      ws.send(JSON.stringify(['REQ', 'room', this.filter]));
    };
    ws.onmessage = (m) => this.handle(typeof m.data === 'string' ? m.data : '');
    ws.onclose = () => {
      if (this.open.delete(url)) this.onConnected(this.open.size);
      this.retry(url, backoff);
    };
  }

  private retry(url: string, backoff: number): void {
    if (this.closed) return;
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      this.connect(url, Math.min(backoff * 2, 60_000));
    }, backoff);
    this.timers.add(timer);
  }

  private handle(text: string): void {
    let msg: unknown;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    if (!Array.isArray(msg) || msg[0] !== 'EVENT') return;
    const event = eventOf(msg[2]);
    if (!event || this.seen.has(event.id)) return;
    this.remember(event.id);
    this.onEvent(event);
  }

  private remember(id: string): void {
    this.seen.add(id);
    if (this.seen.size > 2000) this.seen.delete(this.seen.values().next().value!);
  }

  /** Sends [event] to every connected relay; false if none is connected. */
  publish(event: NostrEvent): boolean {
    this.remember(event.id); // our own copy coming back isn't news
    const frame = JSON.stringify(['EVENT', event]);
    let sent = false;
    for (const url of this.open) {
      const ws = this.sockets.get(url);
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(frame);
        sent = true;
      }
    }
    return sent;
  }

  close(): void {
    this.closed = true;
    this.timers.forEach(clearTimeout);
    this.sockets.forEach((ws) => ws.close(1000));
    this.sockets.clear();
    this.open.clear();
    this.onConnected(0);
  }
}

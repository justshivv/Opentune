import type { ConnectGroup } from './group';

/** A song as Connect passes it between devices, as CTrack on the phone. */
export interface CTrack {
  id: string;
  title: string;
  artist?: string;
  thumb?: string | null;
  length?: string | null;
  album?: string | null;
}

interface Base {
  from: string;
  seq: number;
  at: number;
}

/** Everything a device says to the others; the same JSON as ConnectMsg.kt. */
export type ConnectMsg =
  | (Base & { type: 'presence'; name: string; form: string; active?: boolean })
  | (Base & {
      type: 'state';
      track?: CTrack | null;
      private?: boolean;
      playing?: boolean;
      pos?: number;
      queue?: CTrack[];
      volume?: number;
      shuffle?: boolean;
      repeat?: string;
      // When this device took the music (started it, changed the song, was handed it), on its own clock; pausing doesn't count.
      since?: number | null;
      // How it sounds there, in that device's words ("Lossless", "Good Quality").
      quality?: string | null;
    })
  // [mode], for repeat: "off", "all" or "one".
  | (Base & { type: 'command'; to: string; id: string; action: string; pos?: number; value?: number; track?: CTrack | null; tracks?: CTrack[]; mode?: string | null })
  | (Base & { type: 'ack'; to: string; id: string; ok?: boolean; reason?: string | null })
  | (Base & { type: 'transfer'; to: string; id: string; queue?: CTrack[]; index?: number; pos?: number; playing?: boolean })
  // [next]: the secret of the group the device moved to; the devices here follow it.
  | (Base & { type: 'leave'; next?: string })
  | (Base & { type: 'ping'; to: string; c: number })
  | (Base & { type: 'pong'; to: string; c: number; h: number });

export type StateMsg = Extract<ConnectMsg, { type: 'state' }>;
export type Command = Extract<ConnectMsg, { type: 'command' }>;
export type Ack = Extract<ConnectMsg, { type: 'ack' }>;
export type Transfer = Extract<ConnectMsg, { type: 'transfer' }>;

export const MAX_QUEUE = 100;
export const MAX_SEALED = 48_000;
const TYPES = new Set(['presence', 'state', 'command', 'ack', 'transfer', 'leave', 'ping', 'pong']);

export const encode = (m: ConnectMsg) => JSON.stringify(m);

export function decode(text: string): ConnectMsg | null {
  try {
    const m = JSON.parse(text) as ConnectMsg;
    if (!m || typeof m !== 'object' || !TYPES.has(m.type) || typeof m.from !== 'string' || typeof m.seq !== 'number') return null;
    return { ...m, at: typeof m.at === 'number' ? m.at : 0 };
  } catch {
    return null;
  }
}

function trimmed(m: ConnectMsg, max: number): ConnectMsg {
  if (m.type === 'state' && (m.queue?.length ?? 0) > max) return { ...m, queue: m.queue!.slice(0, max) };
  if (m.type === 'transfer' && (m.queue?.length ?? 0) > max) {
    const index = m.index ?? 0;
    return { ...m, queue: m.queue!.slice(index, index + Math.max(max, 1)), index: 0 };
  }
  return m;
}

const queueSize = (m: ConnectMsg) => (m.type === 'state' || m.type === 'transfer' ? (m.queue?.length ?? 0) : 0);

/** [msg] sealed for [group], its queue cut from the end until it fits a relay's limit. */
export async function fit(msg: ConnectMsg, group: ConnectGroup, limit = MAX_SEALED): Promise<string> {
  let m = trimmed(msg, MAX_QUEUE);
  let sealed = await group.seal(encode(m));
  while (sealed.length > limit) {
    const size = queueSize(m);
    if (!size) break;
    m = trimmed(m, Math.floor((size * 3) / 4));
    sealed = await group.seal(encode(m));
  }
  return sealed;
}

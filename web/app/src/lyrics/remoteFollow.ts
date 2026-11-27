import { LyricClock } from './clock';

/** What the other device last said about its playback (Connect), as the lyrics need it. */
export interface RemoteReading {
  /** The song, so a new one starts the words afresh. */
  trackId: string | null | undefined;
  playing: boolean;
  /** Its position when it said so, in song milliseconds. */
  posMs: number;
  /** When it said so, on this browser's wall clock (Date.now), the relay's delay allowed for. */
  saidAtWallMs: number;
}

/** What the lyrics element should do this frame besides showing [ms]. */
export type FollowStep = 'run' | 'jump' | 'pause' | 'resume';

/**
 * A difference past which a new reading is a seek on the other device (or this one's seek of it),
 * not the small disagreement between two readings carried over the relays: the words land there
 * at once instead of easing towards it.
 */
export const REMOTE_JUMP_MS = 1500;

/**
 * The lyrics' playhead for a song playing on another device. Each report over the relays dates
 * the position a little differently (a few hundred milliseconds either way), and setting the
 * words to each estimate in turn made them step forward and back, restarting the word wipes.
 * Here the readings steer the same clock local playback uses (lyrics/clock.ts): the words run
 * on their own frames, close small gaps smoothly and never step back; a seek, a new song, a
 * pause and a resume are told apart and handled as such.
 */
export class RemoteLyricFollower {
  private readonly clock: LyricClock;
  private track: string | null | undefined = undefined;
  private playing: boolean | null = null;
  private lastSaidAt = Number.NaN;

  constructor(startMs: number) {
    this.clock = new LyricClock(startMs, false);
  }

  /** Where the words go at [perfNowMs]/[wallNowMs] (the same instant on both clocks), and what changed. */
  frame(reading: RemoteReading, perfNowMs: number, wallNowMs: number): { ms: number; step: FollowStep } {
    const target = reading.playing ? reading.posMs + Math.max(0, wallNowMs - reading.saidAtWallMs) : reading.posMs;
    const newSong = reading.trackId !== this.track;
    this.track = reading.trackId;
    if (!reading.playing) {
      const was = this.playing;
      this.playing = false;
      // Paused: the words stand where the other device stopped; a seek while paused moves them.
      const moved = Math.abs(target - this.clock.displayedMs) > 250;
      if (was !== false || newSong || moved) {
        this.clock.hold(target, perfNowMs);
        return { ms: target, step: was !== false ? 'pause' : 'jump' };
      }
      return { ms: this.clock.displayedMs, step: 'run' };
    }
    const resumed = this.playing === false;
    this.playing = true;
    const fresh = reading.saidAtWallMs !== this.lastSaidAt;
    this.lastSaidAt = reading.saidAtWallMs;
    if (newSong || resumed || (fresh && Math.abs(target - this.clock.displayedMs) > REMOTE_JUMP_MS)) {
      // Land there now, and run on from it.
      this.clock.hold(target, perfNowMs);
      this.clock.restart();
      return { ms: target, step: resumed ? 'resume' : 'jump' };
    }
    // The reading, dated on the frame clock by when it was said.
    const sampledAt = perfNowMs - (wallNowMs - reading.saidAtWallMs);
    return { ms: this.clock.frame(perfNowMs, reading.posMs, sampledAt, 0), step: 'run' };
  }
}

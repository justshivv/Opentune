import { expect, it } from 'vitest';
import { RemoteLyricFollower, type RemoteReading } from './remoteFollow';

/** Runs frames every 16 ms from [fromMs] to [toMs] on both clocks, the readings given by [at]. */
function run(f: RemoteLyricFollower, fromMs: number, toMs: number, at: (t: number) => RemoteReading) {
  const out: { t: number; ms: number; step: string }[] = [];
  for (let t = fromMs; t <= toMs; t += 16) out.push({ t, ...f.frame(at(t), t, t) });
  return out;
}

it('jittery reports never move the words backwards and keep them on the song', () => {
  const f = new RemoteLyricFollower(10_000);
  // Reports every second, each dated up to 300 ms off either way; the song really is at 10 s + t.
  const report = (t: number): RemoteReading => {
    const said = Math.floor(t / 1000) * 1000;
    const jitter = [0, 280, -250, 120, -300, 200][Math.floor(t / 1000) % 6]!;
    return { trackId: 'a', playing: true, posMs: 10_000 + said, saidAtWallMs: said + jitter };
  };
  const frames = run(f, 0, 8000, report);
  for (let i = 1; i < frames.length; i++) expect(frames[i]!.ms).toBeGreaterThanOrEqual(frames[i - 1]!.ms);
  const last = frames[frames.length - 1]!;
  expect(Math.abs(last.ms - (10_000 + last.t))).toBeLessThan(400);
  // Only the first frame, which meets the song, lands; after that the readings only steer.
  expect(frames.slice(1).filter((x) => x.step === 'jump')).toHaveLength(0);
});

it('a seek ahead on the other device moves the words there at once', () => {
  const f = new RemoteLyricFollower(10_000);
  run(f, 0, 2000, () => ({ trackId: 'a', playing: true, posMs: 10_000, saidAtWallMs: 0 }));
  const after = f.frame({ trackId: 'a', playing: true, posMs: 75_000, saidAtWallMs: 2016 }, 2032, 2032);
  expect(after.step).toBe('jump');
  expect(after.ms).toBe(75_016);
});

it('a pause stops the words where it stopped, a seek while paused moves them, and a resume runs on', () => {
  const f = new RemoteLyricFollower(0);
  run(f, 0, 1000, (t) => ({ trackId: 'a', playing: true, posMs: 0, saidAtWallMs: 0 + 0 * t }));
  expect(f.frame({ trackId: 'a', playing: false, posMs: 1000, saidAtWallMs: 1000 }, 1016, 1016)).toEqual({ ms: 1000, step: 'pause' });
  expect(f.frame({ trackId: 'a', playing: false, posMs: 1000, saidAtWallMs: 1000 }, 3000, 3000)).toEqual({ ms: 1000, step: 'run' });
  expect(f.frame({ trackId: 'a', playing: false, posMs: 40_000, saidAtWallMs: 3100 }, 3116, 3116)).toEqual({ ms: 40_000, step: 'jump' });
  const resumed = f.frame({ trackId: 'a', playing: true, posMs: 40_000, saidAtWallMs: 4000 }, 4016, 4016);
  expect(resumed.step).toBe('resume');
  expect(resumed.ms).toBe(40_016);
});

it('a new song starts the words afresh', () => {
  const f = new RemoteLyricFollower(0);
  run(f, 0, 1000, () => ({ trackId: 'a', playing: true, posMs: 0, saidAtWallMs: 0 }));
  const next = f.frame({ trackId: 'b', playing: true, posMs: 0, saidAtWallMs: 1016 }, 1032, 1032);
  expect(next).toEqual({ ms: 16, step: 'jump' });
});

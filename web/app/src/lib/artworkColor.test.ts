import { describe, expect, it } from 'vitest';
import { likeAccent } from './artworkColor';

describe('likeAccent', () => {
  it('is absent for a grey cover', () => {
    expect(likeAccent(128, 128, 128)).toBeNull();
    expect(likeAccent(20, 22, 25)).toBeNull();
  });

  it('keeps the cover hue', () => {
    expect(likeAccent(200, 30, 30)).toBe('hsl(0 74% 68%)');
    expect(likeAccent(30, 80, 200)).toMatch(/^hsl\(222 /);
  });

  it('lights a very dark cover and lifts a muted one', () => {
    // Near-black green: dark and dull, still a clear, light green.
    expect(likeAccent(10, 40, 12)).toMatch(/^hsl\(124 \d+% 68%\)$/);
    const muted = likeAccent(110, 120, 140)!;
    const saturation = Number(/hsl\(\d+ (\d+)%/.exec(muted)![1]);
    expect(saturation).toBeGreaterThanOrEqual(65);
  });
});

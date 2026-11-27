import { describe, expect, it } from 'vitest';
import { likeAccent, vibrantGlow } from './artworkColor';

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

/** A 24×24 cover: [count, r, g, b] runs of pixels. */
function cover(...runs: [number, number, number, number][]): number[] {
  const px: number[] = [];
  for (const [n, r, g, b] of runs) for (let i = 0; i < n; i++) px.push(r, g, b, 255);
  return px;
}
const hsl = (s: string) => /hsl\((\d+) (\d+)% (\d+)%\)/.exec(s)!.slice(1).map(Number) as [number, number, number];

it('a mostly black cover with a small red area glows red, not black', () => {
  const [h, s, l] = hsl(vibrantGlow(cover([520, 8, 8, 10], [56, 200, 30, 40])));
  expect(h < 15 || h > 345).toBe(true);
  expect(s).toBeGreaterThanOrEqual(55);
  expect(l).toBeGreaterThanOrEqual(50);
});
it('a dark muddy cover still gives a clear, bright glow of its hue', () => {
  const [h, s, l] = hsl(vibrantGlow(cover([576, 70, 45, 25])));
  expect(h).toBeGreaterThan(15); expect(h).toBeLessThan(45);
  expect(s).toBeGreaterThanOrEqual(55);
  expect(l).toBeGreaterThanOrEqual(50);
});
it('the strongest colour wins over a larger dull one', () => {
  const [h] = hsl(vibrantGlow(cover([400, 90, 85, 80], [176, 30, 90, 220])));
  expect(h).toBeGreaterThan(200); expect(h).toBeLessThan(235);
});
it('a black-and-white cover glows silver', () => {
  expect(vibrantGlow(cover([300, 10, 10, 10], [276, 240, 240, 240]))).toBe('hsl(220 8% 72%)');
});
it('a pale pastel cover keeps its hue', () => {
  const [h, s] = hsl(vibrantGlow(cover([576, 235, 200, 220])));
  expect(h).toBeGreaterThan(310); expect(h).toBeLessThan(345);
  expect(s).toBeGreaterThanOrEqual(55);
});
it('a near-grey cover with a faint cast still glows silver', () => {
  expect(vibrantGlow(cover([576, 128, 126, 124]))).toBe('hsl(220 8% 72%)');
});

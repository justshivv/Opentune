import { expect, it } from 'vitest';
import { displacementPixels } from './liquidGlass';

const at = (data: Uint8ClampedArray, width: number, x: number, y: number) => {
  const i = (y * width + x) * 4;
  return [data[i]!, data[i + 1]!];
};

it('leaves the middle of the glass still', () => {
  const data = displacementPixels(200, 60, 30, 20);
  expect(at(data, 200, 100, 30)).toEqual([128, 128]);
});

it('pulls the backdrop inward at each edge, most at the rim', () => {
  const width = 200;
  const data = displacementPixels(width, 60, 30, 20);
  // Left and right ends: sampling moves toward the centre across; top and bottom: down and up.
  expect(at(data, width, 0, 30)[0]).toBeGreaterThan(210);
  expect(at(data, width, 199, 30)[0]).toBeLessThan(46);
  expect(at(data, width, 100, 0)[1]).toBeGreaterThan(200);
  expect(at(data, width, 100, 59)[1]).toBeLessThan(56);
  // Further in, the bend is gentler.
  expect(at(data, width, 100, 8)[1]).toBeLessThan(at(data, width, 100, 1)[1]!);
  expect(at(data, width, 100, 8)[1]).toBeGreaterThan(128);
});

it('bends corners along their diagonal', () => {
  const data = displacementPixels(100, 100, 30, 20);
  const [x, y] = at(data, 100, 3, 3);
  expect(x).toBeGreaterThan(150);
  expect(y).toBeGreaterThan(150);
  expect(Math.abs(x! - y!)).toBeLessThan(3);
});

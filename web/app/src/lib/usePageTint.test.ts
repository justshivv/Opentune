import { expect, it } from 'vitest';
import { toned } from './usePageTint';

it('Automatic keeps the artwork colour', () => {
  const color = { background: 'rgb(230, 220, 200)', dark: false };
  expect(toned(color, 'auto')).toBe(color);
});
it('Dark makes a pale cover a deep shade of its hue, under white text', () => {
  const t = toned({ background: 'rgb(230, 220, 200)', dark: false }, 'dark');
  expect(t.dark).toBe(true);
  expect(t.background).toMatch(/^hsl\(40\.0 /);
  expect(Number(/ ([\d.]+)%\)$/.exec(t.background)![1])).toBeLessThanOrEqual(24);
});
it('Light makes a dark cover a pale shade, under dark text', () => {
  const t = toned({ background: '#1a237e', dark: true }, 'light');
  expect(t.dark).toBe(false);
  expect(Number(/ ([\d.]+)%\)$/.exec(t.background)![1])).toBeGreaterThanOrEqual(88);
});
it('an unreadable colour is left alone', () => {
  const color = { background: 'var(--x)', dark: true };
  expect(toned(color, 'light')).toBe(color);
});

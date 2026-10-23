// @vitest-environment happy-dom
import { expect, it } from 'vitest';
import { appearanceOf, DEFAULT_APPEARANCE, useAppearance } from './appearance';
it('recovers invalid persisted choices without losing valid preferences', () => {
  expect(appearanceOf(null)).toEqual(DEFAULT_APPEARANCE);
  expect(appearanceOf({ mode: 'invalid', accent: 'url(example)', pureBlack: false, playerStyle: 'vinyl' }))
    .toEqual({ ...DEFAULT_APPEARANCE, pureBlack: false, playerStyle: 'vinyl' });
});
it('saves appearance changes independently and round-trips across reloads', () => {
  useAppearance.getState().update({ mode: 'dark', playerStyle: 'polaroid', motion: false });
  const saved = appearanceOf(JSON.parse(localStorage.getItem('opentune:appearance')!));
  expect(saved).toMatchObject({ mode: 'dark', playerStyle: 'polaroid', motion: false });
  expect(saved.accent).toBe(DEFAULT_APPEARANCE.accent);
});

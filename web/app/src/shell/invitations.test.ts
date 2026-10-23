import { describe, expect, it } from 'vitest';
import { shouldAsk } from './invitations';

const day = 86_400_000;

describe('monthly invitations', () => {
  it('waits for days of use: five for the star, ten for the Discord', () => {
    expect(shouldAsk({ days: 4, asked: {}, never: {} }, 'star', 100 * day)).toBe(false);
    expect(shouldAsk({ days: 5, asked: {}, never: {} }, 'star', 100 * day)).toBe(true);
    expect(shouldAsk({ days: 9, asked: {}, never: {} }, 'discord', 100 * day)).toBe(false);
    expect(shouldAsk({ days: 10, asked: {}, never: {} }, 'discord', 100 * day)).toBe(true);
  });
  it('asks each at most once in thirty days, and two weeks apart from the other', () => {
    expect(shouldAsk({ days: 40, asked: { star: 100 * day }, never: {} }, 'star', 129 * day)).toBe(false);
    expect(shouldAsk({ days: 40, asked: { star: 100 * day }, never: {} }, 'star', 130 * day)).toBe(true);
    expect(shouldAsk({ days: 40, asked: { star: 100 * day }, never: {} }, 'discord', 110 * day)).toBe(false);
    expect(shouldAsk({ days: 40, asked: { star: 100 * day }, never: {} }, 'discord', 114 * day)).toBe(true);
  });
  it('never asks again once turned down', () => {
    expect(shouldAsk({ days: 400, asked: {}, never: { discord: true } }, 'discord', 500 * day)).toBe(false);
  });
});

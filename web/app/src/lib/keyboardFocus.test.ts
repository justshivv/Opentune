// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest';
import { installKeyboardFocus } from './keyboardFocus';

let uninstall: (() => void) | null = null;
afterEach(() => {
  uninstall?.();
  uninstall = null;
});

const key = (k: string, init: KeyboardEventInit = {}) => window.dispatchEvent(new KeyboardEvent('keydown', { key: k, ...init }));

it('shows focus for the keyboard and hides it again for the pointer', () => {
  uninstall = installKeyboardFocus();
  const root = document.documentElement;
  expect(root.dataset.keyboard).toBeUndefined();
  key('Tab');
  expect(root.dataset.keyboard).toBe('true');
  window.dispatchEvent(new Event('pointerdown'));
  expect(root.dataset.keyboard).toBeUndefined();
  key('ArrowDown');
  expect(root.dataset.keyboard).toBe('true');
});

it('ignores typing and shortcuts', () => {
  uninstall = installKeyboardFocus();
  key('a');
  key(' ');
  key('ArrowRight', { ctrlKey: true });
  expect(document.documentElement.dataset.keyboard).toBeUndefined();
});

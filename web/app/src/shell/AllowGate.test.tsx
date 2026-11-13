// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { ExtensionStatus } from '@/extension/useExtension';
import type { HostAccess } from '@/extension/useHostAccess';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const state = vi.hoisted(() => ({
  extension: { state: 'missing' } as ExtensionStatus,
  lyrics: 'unavailable' as HostAccess,
  requestLyrics: vi.fn(),
  allowSources: vi.fn(() => Promise.resolve()),
}));

vi.mock('@/lib/platform', () => ({ isMobileBrowser: () => false }));
vi.mock('@/extension/useExtension', () => ({ useExtension: () => state.extension }));
vi.mock('@/extension/useHostAccess', () => ({
  useHostAccess: () => ({ access: state.lyrics, request: state.requestLyrics }),
}));
vi.mock('@/extension/useExtensionUpdate', () => ({
  usePublishedExtension: () => null,
  downloadUrl: (path: string) => path,
}));
vi.mock('@/addons/client', async () => {
  const { create } = await import('zustand');
  const useAddons = create(() => ({
    sources: [{ id: 'a1', name: 'Lossless One', url: 'https://one.example/manifest.json', enabled: true }],
    locked: ['a1'],
  }));
  return {
    useAddons,
    syncPublished: () => Promise.resolve(true),
    checkAccess: () => Promise.resolve(),
    allowSources: state.allowSources,
  };
});

const { useAllowGate } = await import('./AllowGate');
const { useAddons } = await import('@/addons/client');

let locked: boolean | null = null;
function Harness() {
  const gate = useAllowGate();
  locked = gate.locked;
  return <>{gate.gate}</>;
}

let root: ReturnType<typeof createRoot> | null = null;
async function render() {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(<Harness />));
}
const rerender = () => act(async () => root!.render(<Harness />));

const present: ExtensionStatus = {
  state: 'present',
  extension: { info: { version: '0.5.0', browser: 'chromium', capabilities: [] } } as never,
};
const button = (label: string) => [...document.querySelectorAll('button')].find((b) => b.textContent?.trim() === label);
const steps = () => [...document.querySelectorAll('[data-step-state]')].map((s) => s.getAttribute('data-step-state'));

beforeEach(() => {
  localStorage.clear();
  state.extension = { state: 'missing' };
  state.lyrics = 'unavailable';
  useAddons.setState({ locked: ['a1'] });
});
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});

it('locks the player until the extension is installed', async () => {
  await render();
  expect(document.querySelector('[role="dialog"]')).toBeTruthy();
  expect(locked).toBe(true);
  expect(steps()).toEqual(['todo', 'waiting', 'waiting']);
  // The download for this browser is right there.
  expect(document.querySelector('a[download]')).toBeTruthy();
});

it('asks for the lyric and add-on hosts once the extension is in', async () => {
  await render();
  state.extension = present;
  state.lyrics = 'needed';
  await rerender();
  expect(steps()).toEqual(['done', 'todo', 'todo']);
  expect(locked).toBe(true);
  act(() => button('Allow')!.click());
  expect(state.requestLyrics).toHaveBeenCalled();
  await act(async () => button('Allow')!.click());
  expect(state.allowSources).toHaveBeenCalledWith([expect.objectContaining({ id: 'a1' })]);
});

it('unlocks once everything is allowed, and stays out of the way next time', async () => {
  await render();
  state.extension = present;
  state.lyrics = 'granted';
  await act(async () => useAddons.setState({ locked: [] }));
  await rerender();
  expect(steps()).toEqual(['done', 'done', 'done']);
  expect(locked).toBe(false);
  act(() => button('Start Listening')!.click());
  expect(document.querySelector('[role="dialog"]')).toBeNull();
  expect(localStorage.getItem('opentune:allow-gate-done')).toBe('1');

  // A later visit with everything still allowed never shows it.
  act(() => root?.unmount());
  await render();
  expect(document.querySelector('[role="dialog"]')).toBeNull();
  expect(locked).toBe(false);
});

it('comes back if a host is no longer allowed', async () => {
  localStorage.setItem('opentune:allow-gate-done', '1');
  state.extension = present;
  state.lyrics = 'needed';
  useAddons.setState({ locked: [] });
  await render();
  expect(document.querySelector('[role="dialog"]')).toBeTruthy();
  expect(locked).toBe(true);
});

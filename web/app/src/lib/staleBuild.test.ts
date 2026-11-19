import { describe, expect, it, vi } from 'vitest';
import { isChunkLoadError, recovering, reloadOnce } from './staleBuild';

const memory = () => {
  const values = new Map<string, string>();
  return { getItem: (k: string) => values.get(k) ?? null, setItem: (k: string, v: string) => void values.set(k, v) };
};

describe('stale build recovery', () => {
  it('knows a missing code file in each browser’s words', () => {
    expect(isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: https://justshivv.github.io/Opentune/player/assets/LyricsPanel-jCXkBUBv.js'))).toBe(true);
    expect(isChunkLoadError(new TypeError('error loading dynamically imported module: https://x/a.js'))).toBe(true);
    expect(isChunkLoadError(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(isChunkLoadError(new Error('Unable to preload CSS for /assets/a.css'))).toBe(true);
    expect(isChunkLoadError(new TypeError('Cannot read properties of undefined'))).toBe(false);
    expect(isChunkLoadError(null)).toBe(false);
  });

  it('reloads once, and not again within the minute', () => {
    const storage = memory();
    const reload = vi.fn();
    expect(reloadOnce(storage, 1_000_000, reload)).toBe(true);
    expect(reloadOnce(storage, 1_030_000, reload)).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
    // A later deploy is a new case.
    expect(reloadOnce(storage, 1_100_000, reload)).toBe(true);
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('never reloads without somewhere to note it', () => {
    const reload = vi.fn();
    expect(reloadOnce(null, 1, reload)).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  it('a missing file waits for the reload; any other failure is passed on', async () => {
    const missing = recovering(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module: x')), () => true);
    const settled = vi.fn();
    void missing().then(settled, settled);
    await new Promise((r) => setTimeout(r, 10));
    expect(settled).not.toHaveBeenCalled();

    const broken = recovering(() => Promise.reject(new Error('boom')), () => true);
    await expect(broken()).rejects.toThrow('boom');

    const looped = recovering(() => Promise.reject(new TypeError('Failed to fetch dynamically imported module: x')), () => false);
    await expect(looped()).rejects.toThrow('Failed to fetch dynamically imported module');
  });
});

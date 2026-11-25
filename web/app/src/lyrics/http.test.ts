import { afterEach, beforeEach, expect, it, vi } from 'vitest';

const bridge = vi.hoisted(() => ({ current: null as null | { call: ReturnType<typeof vi.fn> } }));
vi.mock('@/extension/bridge', () => ({ getBridge: () => bridge.current }));

const { lyricsGet, resetLyricHosts } = await import('./http');

const fetchMock = vi.fn<typeof fetch>();
beforeEach(() => {
  resetLyricHosts();
  bridge.current = null;
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

it('never asks a host that blocks pages directly; without the extension it is skipped quietly', async () => {
  expect(await lyricsGet('https://lyrics-api.boidu.dev/getLyrics?s=a')).toBeNull();
  expect(await lyricsGet('https://lyrics.paxsenix.org/apple-music/lyrics?id=1&ttml=true')).toBeNull();
  expect(fetchMock).not.toHaveBeenCalled();

  bridge.current = { call: vi.fn(async () => ({ status: 200, headers: {}, url: '', bodyEncoding: 'text', body: 'words' })) };
  expect(await lyricsGet('https://lyrics-api.boidu.dev/qq/getLyrics?s=a')).toBe('words');
  expect(fetchMock).not.toHaveBeenCalled();
});

it('remembers a host the browser blocked, and goes through the extension after that', async () => {
  fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
  expect(await lyricsGet('https://lyrics.example/a')).toBeNull();
  expect(await lyricsGet('https://lyrics.example/b')).toBeNull();
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('leaves a host alone after a 429 for as long as it asks', async () => {
  fetchMock.mockResolvedValueOnce(new Response('', { status: 429, headers: { 'retry-after': '120' } }));
  expect(await lyricsGet('https://lyricsplus.binimum.org/v2/lyrics?x=1')).toBeNull();
  expect(await lyricsGet('https://lyricsplus.binimum.org/v2/lyrics?x=2')).toBeNull();
  expect(fetchMock).toHaveBeenCalledTimes(1);
  // Other hosts are still asked.
  fetchMock.mockResolvedValueOnce(new Response('ok'));
  expect(await lyricsGet('https://lrclib.net/api/search?q=x')).toBe('ok');
});

import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import fixture from '../../../../core/src/test/resources/config/signed.json';

const saved = new Map<string, string>();
const mockFetch = vi.fn();
beforeEach(() => {
  vi.resetModules(); saved.clear(); mockFetch.mockReset();
  vi.stubGlobal('localStorage', { getItem: (k: string) => saved.get(k) ?? null, setItem: (k: string, v: string) => saved.set(k, v) });
  vi.stubGlobal('fetch', mockFetch);
  vi.spyOn(Date, 'now').mockReturnValue(fixture.now);
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('pins the bootstrap key, retains signed state offline and expires safely without resetting the pin', async () => {
  mockFetch.mockResolvedValueOnce(Response.json({ publicKey: fixture.publicKey }))
    .mockResolvedValueOnce(Response.json(fixture.envelope));
  const { refreshConfig, currentConfig } = await import('./remote');
  await refreshConfig(true);
  expect(currentConfig().revision).toBe(1);
  expect(mockFetch.mock.calls[0]?.[0]).toBe('https://addon-opentune.rethinknow.in/v1/app-config/key');
  mockFetch.mockRejectedValue(new Error('offline'));
  await refreshConfig(true);
  expect(currentConfig().revision).toBe(1);
  expect(mockFetch.mock.calls.at(-1)?.[0]).toBe('https://addon-opentune.rethinknow.in/v1/app-config');
  vi.mocked(Date.now).mockReturnValue(fixture.now + 8 * 86400000);
  await refreshConfig(true);
  expect(currentConfig().revision).toBe(0);
  expect(JSON.parse(saved.get('opentune:remote-config')!).key).toBe(fixture.publicKey);
  expect(JSON.parse(saved.get('opentune:remote-config')!).revision).toBe(1);
});

it('starts offline from the validated stored copy and rejects a tampered network document', async () => {
  saved.set('opentune:remote-config', JSON.stringify({ key: fixture.publicKey, revision: 1, envelope: JSON.stringify(fixture.envelope) }));
  mockFetch.mockResolvedValue(Response.json({ ...fixture.envelope, payload: fixture.envelope.payload + 'x' }));
  const { refreshConfig, currentConfig } = await import('./remote');
  await refreshConfig(true);
  expect(currentConfig().revision).toBe(1);
  expect(mockFetch).toHaveBeenCalledTimes(1);
  expect(mockFetch.mock.calls[0]?.[0]).toBe('https://addon-opentune.rethinknow.in/v1/app-config');
});

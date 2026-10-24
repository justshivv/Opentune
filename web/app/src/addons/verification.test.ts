import { describe, it, expect, vi, afterEach } from 'vitest';
import { OFFICIAL_ADDON, isOfficialAddon, needsVerification, verificationHeaders } from './verification';

afterEach(() => vi.unstubAllGlobals());
describe('verification is private to the official source', () => {
  it('uses exact HTTPS origin, not provider names, URL substrings or another Worker', () => {
    expect(isOfficialAddon(OFFICIAL_ADDON + '/monochrome/manifest.json')).toBe(true);
    expect(needsVerification(OFFICIAL_ADDON + '/monochrome/search?q=track')).toBe(true);
    for (const url of [
      'https://public-addon.example/search?q=OpenTune',
      'https://public-addon.example/auth/web/info',
      'https://losslessbridge.de.deplexo.com/stream/123',
      'https://bluebit.jucepe0929.workers.dev/search?q=test',
      OFFICIAL_ADDON + '.evil.example/search', OFFICIAL_ADDON + ':8443/search',
      OFFICIAL_ADDON.replace('https:', 'http:') + '/search',
      'https://public-addon.example/redirect?to=' + OFFICIAL_ADDON + '/search',
    ]) expect(needsVerification(url), url).toBe(false);
  });
  it('public addons and manifests need no IndexedDB, session, key or verification network request', async () => {
    const blocked = vi.fn(() => { throw new Error('Must not touch verification storage'); });
    vi.stubGlobal('indexedDB', { open: blocked });
    vi.stubGlobal('fetch', blocked);
    for (const url of ['https://public-addon.example/search', 'https://public-addon.example/stream/123',
      OFFICIAL_ADDON + '/monochrome/manifest.json', OFFICIAL_ADDON + '/archive/health', OFFICIAL_ADDON + '/']) {
      expect(await verificationHeaders(url)).toEqual({});
    }
    expect(blocked).not.toHaveBeenCalled();
  });
});

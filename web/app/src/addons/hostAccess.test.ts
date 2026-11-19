import { describe, expect, it } from 'vitest';
import { BridgeCallError } from '@opentune/shared/bridge';
import { askHost } from './hostAccess';

const SAAVN = 'https://www.jiosaavn.com';
/** The extension, refusing every request with [code]. Plain functions: a rejecting vi.fn is reported as an error of its own. */
const refusing = (code: BridgeCallError['code']) => () => Promise.reject(new BridgeCallError(code, 'refused'));

describe('askHost', () => {
  it('asks the extension for a small file on that host', async () => {
    const asked: unknown[] = [];
    const call = async (method: string, params: { url: string }) => { asked.push([method, params]); return { status: 200 }; };
    expect(await askHost(call, SAAVN)).toBe(true);
    expect(asked).toEqual([['addon.fetch', { url: 'https://www.jiosaavn.com/robots.txt' }]]);
  });
  it('is not allowed when the extension refuses for want of permission', async () => {
    expect(await askHost(refusing('permission'), SAAVN)).toBe(false);
  });
  it('is allowed when the request got past the permission check and failed later', async () => {
    expect(await askHost(refusing('network'), SAAVN)).toBe(true);
  });
  it('is unknown without the extension, or when it does not answer', async () => {
    expect(await askHost(refusing('timeout'), SAAVN)).toBeNull();
    expect(await askHost(null, SAAVN)).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import fixture from '../../../../core/src/test/resources/config/signed.json';
import { defaults, validateData, verifyConfig } from './protocol';

describe('signed configuration', () => {
  const envelope = JSON.stringify(fixture.envelope);
  it('accepts the same Node ES256 signature as Android and keeps order and owner switches', async () => {
    const result = await verifyConfig(envelope, fixture.publicKey, 0, fixture.now);
    expect(result.revision).toBe(1);
    expect(result.data.sections[0]?.id).toBe('recommendations');
    expect(result.data.sources?.[0]?.enabled).toBe(false);
    expect(result.data.banners[0]?.platforms).toEqual(['android', 'tv', 'web']);
  });
  it('rejects tamper, expiry and replay of a lower revision', async () => {
    await expect(verifyConfig(JSON.stringify({ ...fixture.envelope, payload: fixture.envelope.payload + 'x' }), fixture.publicKey, 0, fixture.now)).rejects.toThrow();
    await expect(verifyConfig(envelope, fixture.publicKey, 2, fixture.now)).rejects.toThrow();
    await expect(verifyConfig(envelope, fixture.publicKey, 0, fixture.now + 8 * 86400000)).rejects.toThrow();
  });
  it('rejects unknown sections, unsafe links and unbounded request policies', () => {
    const valid = defaults().data;
    expect(validateData(valid)).toEqual(valid);
    for (const delta of [{ sections: [] }, { network: { timeoutMs: 999999, retries: 10 } }, { links: { ...valid.links, web: 'javascript:alert(1)' } }]) {
      expect(() => validateData({ ...valid, ...delta })).toThrow();
    }
    expect(valid.sources).toBeNull();
  });
});

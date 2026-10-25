import { describe, expect, it } from 'vitest';
import { mergeCatalog, publishedOf } from './catalog';

describe('published sources', () => {
  const mine = { id: 'm1', name: 'Mine', url: 'https://mine.example/manifest.json', enabled: true };
  it('applies owner pauses without changing personal sources or the listener switch', () => {
    const source = { id: 'a', name: 'A', url: 'https://a.example/manifest.json', enabled: false };
    const first = mergeCatalog([mine], [source]);
    expect(first[0]).toEqual(mine);
    expect(first[1]).toMatchObject({ enabled: true, ownerOff: true });
    const resumed = mergeCatalog([{ ...first[1]!, enabled: false }, mine], [{ ...source, enabled: true }]);
    expect(resumed[0]).toMatchObject({ enabled: false, ownerOff: false });
    expect(mergeCatalog(resumed, [])).toEqual([mine]);
  });
  it('adds new ones on, keeps the listener’s order and switches, and drops unpublished ones', () => {
    const first = mergeCatalog([mine], [{ id: 'a', name: 'A', url: 'https://a.example/manifest.json' }, { id: 'b', name: 'B', url: 'https://b.example/manifest.json' }]);
    expect(first.map((s) => s.id)).toEqual(['m1', 'admin:a', 'admin:b']);
    expect(first.every((s) => s.enabled)).toBe(true);
    // The listener turned A off and moved B above their own.
    const arranged = [{ ...first[2]! }, mine, { ...first[1]!, enabled: false }];
    const next = mergeCatalog(arranged, [{ id: 'a', name: 'A renamed', url: 'https://a2.example/manifest.json' }]);
    expect(next.map((s) => [s.id, s.enabled, s.name])).toEqual([['m1', true, 'Mine'], ['admin:a', false, 'A renamed']]);
    expect(next[1]!.url).toBe('https://a2.example/manifest.json');
  });
  it('reads only well-formed entries and gives every address as its manifest', () => {
    expect(publishedOf({ sources: [
      { id: 'ok', name: ' Good ', url: 'https://good.example/' },
      { id: 'bad id', name: 'X', url: 'https://x.example/' },
      { id: 'http', name: 'Y', url: 'http://plain.example/' },
    ] })).toEqual([{ id: 'ok', name: 'Good', url: 'https://good.example/manifest.json' }]);
    expect(() => publishedOf({ sources: 'nope' })).toThrow();
  });
});

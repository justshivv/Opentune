import { describe, expect, it } from 'vitest';
import { decryptMediaUrl, matchSaavn, plain, rendition320, verified320 } from './jiosaavn';

describe('JioSaavn', () => {
  it('decrypts a real encrypted_media_url the way JioSaavn does (DES-ECB, key 38346591)', () => {
    expect(decryptMediaUrl('ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyinwvqAX+ZIcRfT9jlvhsHPMJ2M/BvHn0HyBOcibnfn6nF33L6IRN+Rw7tS9a8Gtq'))
      .toBe('https://aac.saavncdn.com/229/f4e09fdb0538ec8e0f799435c7fbe0fa_96.mp4');
  });
  it('asks for the 320 kbps rendition, and only on its own CDN', () => {
    expect(rendition320('https://aac.saavncdn.com/229/f4e09fdb0538ec8e0f799435c7fbe0fa_96.mp4')).toBe('https://aac.saavncdn.com/229/f4e09fdb0538ec8e0f799435c7fbe0fa_320.mp4');
    expect(rendition320('https://evil.example/229/a_96.mp4')).toBeNull();
    expect(rendition320('https://aac.saavncdn.com/229/a.m4a')).toBeNull();
  });
  it('matches the same song by title, length and a shared credit, not a remix or a different length', () => {
    const song = (id: string, title: string, duration: number, singers: string[]) => ({
      id, title, more_info: { '320kbps': 'true', duration: String(duration), artistMap: { primary_artists: singers.map((name) => ({ name })) } },
    });
    const results = [song('remix', 'Zaalima - Remix', 299, ['Arijit Singh']), song('short', 'Zaalima', 250, ['Arijit Singh']), song('SfxSwGeF', 'Zaalima', 300, ['Arijit Singh', 'Harshdeep Kaur'])];
    expect(matchSaavn(results, { title: 'Zaalima (From "Raees")', artist: 'Arijit Singh & Harshdeep Kaur', duration: 299 })).toBe('SfxSwGeF');
    expect(matchSaavn(results, { title: 'Zaalima', artist: 'Someone Else', duration: 299 })).toBeNull();
    expect(plain('Tum Hi Ho &amp; Me')).toBe('tum hi ho me');
  });
  it('accepts a file only when it is MP4 at about 320 kbps', () => {
    const head = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0]);
    expect(verified320(head, 12_000_000, 300)).toBe(true);
    expect(verified320(head, 3_600_000, 300)).toBe(false);
    expect(verified320(new Uint8Array(12), 12_000_000, 300)).toBe(false);
  });
});

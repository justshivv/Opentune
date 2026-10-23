import { describe, expect, it } from 'vitest';
import { qualityLabel } from './quality';
describe('active audio quality', () => {
  it('names YouTube and JioSaavn by tier, without the bitrate, and says when an upgrade is being looked for', () => {
    expect(qualityLabel('251 opus 146kbps direct via WEB', true, 'playing')).toEqual({ text: 'Good Quality · upgrading', lossless: false });
    expect(qualityLabel('251 opus 146kbps direct via VISIONOS', false, 'playing').text).toBe('Good Quality');
    expect(qualityLabel('JioSaavn · AAC 320 kbps via JIOSAAVN', false, 'playing').text).toBe('High Quality');
    expect(qualityLabel(null, true, 'loading').text).toBe('Upgrading quality');
  });
  it('uses the measured active FLAC format and leaves CD quality distinct', () => {
    expect(qualityLabel('Source · FLAC 16-bit 44.1 kHz via ADDON', false, 'playing').text).toBe('Lossless');
    expect(qualityLabel('Source · FLAC 24-bit 96.0 kHz via ADDON', false, 'playing').text).toBe('Hi-Res Lossless');
    expect(qualityLabel('Source · FLAC 24-bit 96.0 kHz via ADDON', true, 'playing')).toEqual({ text: 'Hi-Res Lossless', lossless: true });
  });
});

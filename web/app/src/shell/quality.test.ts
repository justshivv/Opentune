import { describe, expect, it } from 'vitest';
import { qualityLabel } from './quality';
describe('active audio quality', () => {
  it('does not promote YouTube audio just because a source is being checked', () => {
    expect(qualityLabel('251 opus 146kbps via WEB', true, 'playing')).toEqual({ text: 'High quality, upgrading', lossless: false });
    expect(qualityLabel(null, true, 'loading').text).toBe('Upgrading quality');
  });
  it('uses the measured active FLAC format and leaves CD quality distinct', () => {
    expect(qualityLabel('Source · FLAC 16-bit 44.1 kHz via ADDON', false, 'playing').text).toBe('Lossless');
    expect(qualityLabel('Source · FLAC 24-bit 96.0 kHz via ADDON', false, 'playing').text).toBe('Hi-Res Lossless');
    expect(qualityLabel('Source · FLAC 16-bit 96.0 kHz via ADDON', false, 'paused').lossless).toBe(true);
  });
  it('does not show a failed FLAC stream as active lossless', () => {
    expect(qualityLabel('Source · FLAC 24-bit 96.0 kHz via ADDON', false, 'error')).toEqual({ text: 'Playback unavailable', lossless: false });
  });
  it('does not accept a provider name containing FLAC as active lossless evidence', () => {
    expect(qualityLabel('FLAC radio via WEB', false, 'playing').lossless).toBe(false);
  });
});

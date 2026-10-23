/**
 * The quality label, as Android's: a search result isn't proof of playback, so only the
 * active engine's format earns a tier. YouTube is Good Quality and JioSaavn's 320 kbps
 * High Quality, named without their bitrates; an add-on's FLAC is Lossless or Hi-Res
 * Lossless, from the file's own header.
 */
export function qualityLabel(format: string | null, upgrading: boolean, status: string) {
  const lossless = status !== 'error' && status !== 'idle' && !!format && /\bFLAC\b/i.test(format) && /\bADDON\b/.test(format);
  const bits = Number(format?.match(/(\d+)-bit/)?.[1] ?? 0);
  const rate = Number(format?.match(/([\d.]+) kHz/)?.[1] ?? 0);
  const saavn = !!format && /\bJIOSAAVN\b/.test(format);
  const tier = lossless ? (bits > 16 || rate > 48 ? 'Hi-Res Lossless' : 'Lossless')
    : status === 'error' ? 'Playback unavailable'
    : !format ? (upgrading ? 'Upgrading quality' : 'Loading audio')
    : saavn ? 'High Quality' : 'Good Quality';
  const text = upgrading && format && !lossless && status !== 'error' ? `${tier} · upgrading` : tier;
  return { text, lossless };
}

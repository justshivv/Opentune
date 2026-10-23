/** A search result isn't proof of playback. Only the active engine's format earns a lossless label. */
export function qualityLabel(format: string | null, upgrading: boolean, status: string) {
  const lossless = status !== 'error' && status !== 'idle' && !!format && /\bFLAC\b/i.test(format) && /\bADDON\b/.test(format);
  const bits = Number(format?.match(/(\d+)-bit/)?.[1] ?? 0);
  const rate = Number(format?.match(/([\d.]+) kHz/)?.[1] ?? 0);
  const text = lossless ? (bits > 16 || rate > 48 ? 'Hi-Res Lossless' : 'Lossless')
    : status === 'error' ? 'Playback unavailable'
    : !format ? (upgrading ? 'Upgrading quality' : 'Loading audio')
    : upgrading ? 'High quality, upgrading' : 'High quality';
  return { text, lossless };
}

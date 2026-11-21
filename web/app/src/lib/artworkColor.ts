import { useEffect, useState } from 'react';
import { artworkAtPixels } from './artwork';

export interface ArtworkColor {
  /** The background colour behind the page ("joe colour", report §3). */
  background: string;
  /** Whether text on it should be light (a dark colour) or dark. */
  dark: boolean;
  /**
   * The cover's hue as a clear, light accent for the liked star on the
   * player's dark backdrop. Absent for a grey cover.
   */
  accent?: string;
}

/**
 * The liked star's colour from the cover's average [r, g, b] (0–255): its hue,
 * pushed up to a clear saturation and set light, so it reads on the player's
 * backdrop, which always fades to near-black, however dark or pale the cover.
 * Null for a cover that is all greys, where the hue means nothing.
 */
export function likeAccent(r: number, g: number, b: number): string | null {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const chroma = max - min;
  if (chroma < 18) return null;
  let hue: number;
  if (max === r) hue = ((g - b) / chroma) % 6;
  else if (max === g) hue = (b - r) / chroma + 2;
  else hue = (r - g) / chroma + 4;
  hue = Math.round((hue * 60 + 360) % 360);
  const lightness = (max + min) / 510;
  const saturation = chroma / 255 / (1 - Math.abs(2 * lightness - 1) || 1);
  const s = Math.round(Math.max(saturation, 0.65) * 100);
  return `hsl(${hue} ${Math.min(s, 100)}% 68%)`;
}

const cache = new Map<string, ArtworkColor>();

/**
 * The artwork's average colour, drawn from a tiny copy of it on a canvas.
 * YouTube's image hosts send `Access-Control-Allow-Origin: *`, so the canvas
 * stays readable without the extension.
 */
async function measure(url: string): Promise<ArtworkColor> {
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.referrerPolicy = 'no-referrer';
  image.decoding = 'async';
  // 60px: a size YouTube's pages request, so the CDN has it cached (lib/artwork.ts).
  image.src = artworkAtPixels(url, 60);
  await image.decode();
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 8;
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  context.drawImage(image, 0, 0, 8, 8);
  const { data } = context.getImageData(0, 0, 8, 8);
  let r = 0;
  let g = 0;
  let b = 0;
  for (let i = 0; i < data.length; i += 4) {
    r += data[i]!;
    g += data[i + 1]!;
    b += data[i + 2]!;
  }
  const n = data.length / 4;
  [r, g, b] = [r / n, g / n, b / n];
  // Apple darkens the colour behind its dark layout; keep text legible.
  const luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  const dark = luminance < 0.6;
  const k = dark ? Math.min(1, 0.55 / Math.max(luminance, 0.05)) * 0.6 : 1;
  const scale = (v: number) => Math.round(dark ? Math.min(v * k, 90) : v);
  const accent = likeAccent(r, g, b) ?? undefined;
  return { background: `rgb(${scale(r)}, ${scale(g)}, ${scale(b)})`, dark, accent };
}

export function useArtworkColor(url: string | null | undefined): ArtworkColor | null {
  const [color, setColor] = useState<ArtworkColor | null>(() => (url ? cache.get(url) ?? null : null));
  useEffect(() => {
    if (!url) return setColor(null);
    const known = cache.get(url);
    if (known) return setColor(known);
    let live = true;
    measure(url)
      .then((measured) => {
        cache.set(url, measured);
        if (live) setColor(measured);
      })
      .catch(() => {
        // An unreadable image leaves the page in its normal colours.
      });
    return () => {
      live = false;
    };
  }, [url]);
  return color;
}

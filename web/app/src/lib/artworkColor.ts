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
  /**
   * The cover's most vivid colour, set bright enough to glow on a dark page (Home's accent glow):
   * its strongest hue rather than its average, which for most covers is a muddy dark. A soft
   * silver for a black-and-white cover.
   */
  glow?: string;
}

/**
 * The glow colour from a cover's pixels (RGBA, 0–255): each pixel's hue, weighted by how
 * colourful and how mid-bright it is (near-black, near-white and grey pixels count for little),
 * summed in 24 hue buckets; the strongest bucket and its neighbours give the hue and colour,
 * then lightness and saturation are set where a glow reads well on near-black. A cover with
 * almost no colour gives silver.
 */
export function vibrantGlow(data: Uint8ClampedArray | number[]): string {
  const BUCKETS = 24;
  const weight = new Array<number>(BUCKETS).fill(0);
  const sums = Array.from({ length: BUCKETS }, () => [0, 0, 0]);
  let total = 0;
  for (let i = 0; i + 2 < data.length; i += 4) {
    const r = data[i]!, g = data[i + 1]!, b = data[i + 2]!;
    const max = Math.max(r, g, b), min = Math.min(r, g, b), chroma = max - min;
    const l = (max + min) / 510;
    const sat = chroma === 0 ? 0 : chroma / 255 / (1 - Math.abs(2 * l - 1) || 1);
    // Colourful and neither too dark nor washed out: the pixels that carry the cover's colour.
    const w = sat * sat * Math.max(0, 1 - Math.abs(l - 0.5) * 1.7) * (chroma / 255);
    total += 1;
    if (w <= 0) continue;
    let hue = max === r ? ((g - b) / chroma) % 6 : max === g ? (b - r) / chroma + 2 : (r - g) / chroma + 4;
    hue = (hue * 60 + 360) % 360;
    const k = Math.floor(hue / (360 / BUCKETS)) % BUCKETS;
    weight[k]! += w;
    sums[k]![0]! += r * w; sums[k]![1]! += g * w; sums[k]![2]! += b * w;
  }
  let best = 0;
  let bestScore = -1;
  for (let k = 0; k < BUCKETS; k++) {
    const score = weight[k]! + 0.5 * (weight[(k + 1) % BUCKETS]! + weight[(k + BUCKETS - 1) % BUCKETS]!);
    if (score > bestScore) { bestScore = score; best = k; }
  }
  // Too little colour anywhere: a soft silver rather than a muddy or black glow.
  if (total === 0 || bestScore / total < 0.003) return 'hsl(220 8% 72%)';
  let r = 0, g = 0, b = 0, w = 0;
  for (const k of [best, (best + 1) % BUCKETS, (best + BUCKETS - 1) % BUCKETS]) {
    r += sums[k]![0]!; g += sums[k]![1]!; b += sums[k]![2]!; w += weight[k]!;
  }
  [r, g, b] = [r / w, g / w, b / w];
  const max = Math.max(r, g, b), min = Math.min(r, g, b), chroma = max - min || 1;
  let hue = max === r ? ((g - b) / chroma) % 6 : max === g ? (b - r) / chroma + 2 : (r - g) / chroma + 4;
  hue = Math.round((hue * 60 + 360) % 360);
  const l = (max + min) / 510;
  const sat = chroma / 255 / (1 - Math.abs(2 * l - 1) || 1);
  return `hsl(${hue} ${Math.round(Math.min(0.9, Math.max(sat, 0.55)) * 100)}% ${Math.round(Math.min(0.62, Math.max(l, 0.5)) * 100)}%)`;
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
  canvas.width = canvas.height = 24;
  const context = canvas.getContext('2d', { willReadFrequently: true })!;
  context.drawImage(image, 0, 0, 24, 24);
  // 24×24 for the glow's colour search; the average below is the same at any size.
  const { data } = context.getImageData(0, 0, 24, 24);
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
  return { background: `rgb(${scale(r)}, ${scale(g)}, ${scale(b)})`, dark, accent, glow: vibrantGlow(data) };
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

import { useEffect } from 'react';
import { useAppearance, type Appearance } from '@/state/appearance';
import type { ArtworkColor } from './artworkColor';

/** "#rrggbb" or "rgb(r, g, b)" as 0–255 channels; null for anything else. */
function channels(color: string): [number, number, number] | null {
  const hex = /^#([0-9a-f]{6})$/i.exec(color.trim());
  if (hex) {
    const n = parseInt(hex[1]!, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  const rgb = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i.exec(color.trim());
  return rgb ? [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])] : null;
}

function toHsl([r, g, b]: [number, number, number]): [number, number, number] {
  [r, g, b] = [r / 255, g / 255, b / 255];
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  const h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}

/**
 * The page colour for the "Page color" setting: Automatic keeps the artwork's own (light covers
 * give light pages); Dark takes a deep shade of its hue, under white text; Light a pale one, under
 * dark text. Grey covers stay grey.
 */
export function toned(color: ArtworkColor, mode: Appearance['pageTone']): ArtworkColor {
  if (mode === 'auto') return color;
  const rgb = channels(color.background);
  if (!rgb) return color;
  const [h, s, l] = toHsl(rgb);
  if (mode === 'dark') {
    const light = Math.min(l, 0.24);
    return { ...color, background: `hsl(${h.toFixed(1)} ${(Math.min(s, 0.7) * 100).toFixed(1)}% ${(Math.max(light, 0.1) * 100).toFixed(1)}%)`, dark: true };
  }
  return { ...color, background: `hsl(${h.toFixed(1)} ${(Math.min(s, 0.5) * 100).toFixed(1)}% ${(Math.max(l, 0.88) * 100).toFixed(1)}%)`, dark: false };
}

/**
 * Tints the whole scrolling page with the artwork's colour while a detail
 * page is open, as Apple's album and playlist pages do (report §3,
 * `has-theme-override`). The text colours that sit on it are switched in
 * tokens.css by `data-tint`. Settings › Appearance › Page color can make it
 * always dark or always light.
 */
export function usePageTint(color: ArtworkColor | null) {
  const mode = useAppearance((s) => s.pageTone);
  useEffect(() => {
    const page = document.getElementById('scrollable-page');
    if (!page || !color) return;
    const tint = toned(color, mode);
    page.style.setProperty('--page-tint', tint.background);
    page.dataset.tint = tint.dark ? 'dark' : 'light';
    // A chosen tone also tones the blurred cover across the top (.ot-wash in opentune.css).
    if (mode !== 'auto') page.dataset.tone = mode;
    return () => {
      page.style.removeProperty('--page-tint');
      delete page.dataset.tint;
      delete page.dataset.tone;
    };
  }, [color, mode]);
}

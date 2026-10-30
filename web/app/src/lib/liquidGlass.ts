import { useEffect, useState } from 'react';
import { useAppearance } from '@/state/appearance';

/**
 * Liquid Glass for the web: what's behind a floating pill is bent at its rim
 * as a thick lens would, with the colours split a little where it bends most.
 *
 * The bend is an SVG feDisplacementMap that backdrop-filter runs through
 * `url(#id)`. Only Chromium (Chrome, Edge, Opera, Brave) runs SVG filters in
 * backdrop-filter; Safari and Firefox drop the whole declaration, so there the
 * glass stays frosted (the CSS keeps the frosted filter as the fallback).
 */

type Brands = { brands?: { brand: string }[] };

export function liquidGlassSupported(): boolean {
  const data = (navigator as Navigator & { userAgentData?: Brands }).userAgentData;
  return !!data?.brands?.some((b) => b.brand === 'Chromium');
}

/**
 * The displacement map for a rounded rectangle [width]×[height] with corner
 * [radius], as RGBA pixels: red moves sampling across, green moves it down,
 * 128 is still. Within [bezel] of the edge the backdrop is sampled from further
 * in, along the edge's normal, on a circular profile: barely at the bezel's
 * inner side, steeply at the rim, as light through a rounded slab of glass.
 * Offsets are in units of the filter's largest shift.
 */
export function displacementPixels(width: number, height: number, radius: number, bezel: number): Uint8ClampedArray<ArrayBuffer> {
  const data = new Uint8ClampedArray(width * height * 4);
  const hx = width / 2;
  const hy = height / 2;
  const r = Math.max(0, Math.min(radius, hx, hy));
  const band = Math.max(1, Math.min(bezel, hx, hy));
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const px = x + 0.5 - hx;
      const py = y + 0.5 - hy;
      const qx = Math.abs(px) - (hx - r);
      const qy = Math.abs(py) - (hy - r);
      let inside: number;
      let nx = 0;
      let ny = 0;
      if (qx > 0 && qy > 0) {
        // A corner: the normal points out from the corner's centre.
        const length = Math.hypot(qx, qy);
        inside = r - length;
        nx = (qx / length) * Math.sign(px);
        ny = (qy / length) * Math.sign(py);
      } else if (qx > qy) {
        inside = r - qx;
        nx = Math.sign(px);
      } else {
        inside = r - qy;
        ny = Math.sign(py);
      }
      const t = Math.min(1, Math.max(0, 1 - inside / band));
      const shift = 1 - Math.sqrt(1 - t * t);
      const i = (y * width + x) * 4;
      data[i] = Math.round(128 - nx * shift * 127);
      data[i + 1] = Math.round(128 - ny * shift * 127);
      data[i + 2] = 128;
      data[i + 3] = 255;
    }
  }
  return data;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
let host: SVGSVGElement | null = null;
const filters = new Map<string, { id: string; node: SVGFilterElement; users: number }>();
let next = 0;

function svgHost(): SVGSVGElement {
  if (host?.isConnected) return host;
  host = document.createElementNS(SVG_NS, 'svg');
  // Not display:none, which would stop the filters being found at all.
  host.setAttribute('width', '0');
  host.setAttribute('height', '0');
  host.setAttribute('aria-hidden', 'true');
  host.style.position = 'absolute';
  host.style.pointerEvents = 'none';
  document.body.append(host);
  return host;
}

function mapUrl(width: number, height: number, radius: number, bezel: number): string {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) return '';
  context.putImageData(new ImageData(displacementPixels(width, height, radius, bezel), width, height), 0, 0);
  return canvas.toDataURL();
}

function el<K extends keyof SVGElementTagNameMap>(name: K, attributes: Record<string, string | number>): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG_NS, name);
  for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, String(value));
  return node;
}

/**
 * One filter per size and shape, shared by everything that size (all of a
 * page's shelf arrows use one) and removed when the last user lets go.
 */
function acquire(width: number, height: number, radius: number, bezel: number, depth: number): string {
  const key = `${width}x${height}r${radius}b${bezel}d${depth}`;
  const known = filters.get(key);
  if (known) {
    known.users++;
    return key;
  }
  const id = `ot-liquid-${next++}`;
  const box = { x: 0, y: 0, width, height };
  const filter = el('filter', { id, ...box, filterUnits: 'userSpaceOnUse', primitiveUnits: 'userSpaceOnUse', 'color-interpolation-filters': 'sRGB' });
  const image = el('feImage', { ...box, result: 'map', preserveAspectRatio: 'none' });
  image.setAttribute('href', mapUrl(width, height, radius, bezel));
  filter.append(image);
  // Red, green and blue each bent a little less than the last: the rim's colour fringe.
  const channels = [
    ['r', 1, '1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0'],
    ['g', 0.93, '0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0'],
    ['b', 0.86, '0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0'],
  ] as const;
  for (const [name, part, matrix] of channels) {
    filter.append(
      el('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', scale: depth * 2 * part, xChannelSelector: 'R', yChannelSelector: 'G', result: `${name}-bent` }),
      el('feColorMatrix', { in: `${name}-bent`, type: 'matrix', values: matrix, result: name }),
    );
  }
  filter.append(el('feBlend', { in: 'r', in2: 'g', mode: 'screen', result: 'rg' }), el('feBlend', { in: 'rg', in2: 'b', mode: 'screen' }));
  svgHost().append(filter);
  filters.set(key, { id, node: filter, users: 1 });
  return key;
}

function release(key: string) {
  const known = filters.get(key);
  if (!known || --known.users > 0) return;
  known.node.remove();
  filters.delete(key);
}

/**
 * Gives the element Liquid Glass while the setting is on and the browser can
 * draw it: `data-liquid` on it, and `--liquid-filter` naming its lens, which
 * the glass CSS puts in its backdrop-filter. [bezel] is how far in from the
 * edge the glass bends; [depth] the furthest it moves anything, in pixels.
 */
export function useLiquidGlass<T extends HTMLElement>(bezel = 22, depth = 22): (node: T | null) => void {
  // A callback ref, so an element that comes and goes (the mini player) gets its lens each time.
  const [node, setNode] = useState<T | null>(null);
  const enabled = useAppearance((s) => s.liquidGlass);
  useEffect(() => {
    if (!node || !enabled || !liquidGlassSupported()) return;
    let held: string | null = null;
    const apply = () => {
      const width = Math.round(node.offsetWidth);
      const height = Math.round(node.offsetHeight);
      if (!width || !height) return;
      const radius = Math.round(Math.min(Number.parseFloat(getComputedStyle(node).borderTopLeftRadius) || 0, width / 2, height / 2));
      const key = acquire(width, height, radius, bezel, depth);
      if (held) release(held);
      held = key;
      node.style.setProperty('--liquid-filter', `url(#${filters.get(key)!.id})`);
      node.dataset.liquid = '';
    };
    apply();
    const observer = new ResizeObserver(apply);
    observer.observe(node);
    return () => {
      observer.disconnect();
      if (held) release(held);
      node.style.removeProperty('--liquid-filter');
      delete node.dataset.liquid;
    };
  }, [node, enabled, bezel, depth]);
  return setNode;
}

/**
 * One frame of the opening's dive (see opening.ts), shared by the worker that draws it off the
 * page's main thread and the fallback that draws it on the page. [progress] runs 0 to 1 over
 * the dive; [w] and [h] are the window's size in CSS pixels, the context already scaled to them.
 */
export function drawDive(
  ctx: CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D,
  mark: Path2D,
  w: number,
  h: number,
  progress: number,
): void {
  const dv = DIVE_EASE(progress);
  // The mark's size as the splash leaves it: min(240px, 42vmin) across, settled to 105 %.
  const base = Math.min(START_PX, Math.min(w, h) * 0.42) / VIEWPORT;
  // A big screen needs a bigger dive for the mark's band to cover it.
  const zoom = ZOOM * Math.max(1, Math.max(w, h) / 900);
  const s = base * 1.05 * Math.exp(Math.log(zoom) * dv * dv);
  const window_ = smooth(0.04, 0.28, dv);
  const fade = 1 - smooth(0.8, 1, dv);

  ctx.clearRect(0, 0, w, h);
  ctx.globalCompositeOperation = 'source-over';
  ctx.globalAlpha = fade;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, w, h);

  ctx.save();
  ctx.translate(w / 2 - (VIEWPORT / 2) * s, h / 2 - (VIEWPORT / 2) * s);
  ctx.scale(s, s);
  ctx.globalAlpha = 1 - window_;
  ctx.fillStyle = '#fff';
  ctx.fill(mark);
  // Then the mark becomes a window onto the player.
  if (window_ > 0) {
    ctx.globalCompositeOperation = 'destination-out';
    ctx.globalAlpha = window_;
    ctx.fillStyle = '#000';
    ctx.fill(mark);
    ctx.globalCompositeOperation = 'source-over';
  }
  // Its white edge outlines the window, the same width on screen however big the mark gets.
  if (fade > 0 && dv > 0) {
    ctx.globalAlpha = fade * Math.min(1, dv * 20);
    ctx.strokeStyle = '#fff';
    ctx.lineJoin = 'round';
    ctx.lineWidth = RIM_PX / s;
    ctx.stroke(mark);
  }
  ctx.restore();
}

/** How long the dive lasts, as on the phone. */
export const DIVE_MS = 900;
/** A late frame counts for at most this much of the dive, so a hitch slows it rather than skipping it. */
export const MAX_STEP_MS = 34;

/** The mark's own drawing box (logo-mark.svg's viewBox). */
const VIEWPORT = 108;
/** The phone's system splash draws the mark 240 dp across; the opening starts there. */
const START_PX = 240;
const RIM_PX = 2.5;
/** How many times its size the mark grows by the end of the dive. */
const ZOOM = 90;

const smooth = (from: number, to: number, x: number) => {
  const t = Math.min(1, Math.max(0, (x - from) / (to - from)));
  return t * t * (3 - 2 * t);
};

/** CSS cubic-bezier(x1, y1, x2, y2) as a function of time, as Compose's CubicBezierEasing. */
function bezier(x1: number, y1: number, x2: number, y2: number): (t: number) => number {
  const at = (a: number, b: number, t: number) => 3 * a * t * (1 - t) ** 2 + 3 * b * t * t * (1 - t) + t ** 3;
  return (t) => {
    if (t <= 0) return 0;
    if (t >= 1) return 1;
    let lo = 0;
    let hi = 1;
    for (let i = 0; i < 24; i++) {
      const mid = (lo + hi) / 2;
      if (at(x1, x2, mid) < t) lo = mid;
      else hi = mid;
    }
    return at(y1, y2, (lo + hi) / 2);
  };
}

const DIVE_EASE = bezier(0.6, 0, 0.85, 0.35);

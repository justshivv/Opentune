import markSvg from '@/assets/logo-mark.svg?raw';

/**
 * The opening, as the phone app has it (app/…/ui/components/OpeningReveal.kt): OpenTune's white
 * mark on black settles with a glint of light across it, then the screen dives into it. The mark
 * turns into a window onto the player, outlined in white, and rushes toward you until the player
 * fills the screen. The player behind never moves: it is simply revealed.
 *
 * Drawn on one canvas over the page, before React's first paint, so the page never shows bare
 * first. Each frame counts for at most 25 ms of the animation: while the page is busy starting
 * up and a frame comes late, the opening slows down instead of jumping ahead.
 */
export function playOpening(): void {
  const d = /\sd="([^"]+)"/.exec(markSvg)?.[1];
  if (!d || typeof Path2D === 'undefined') return;
  const mark = new Path2D(d);
  const canvas = document.createElement('canvas');
  canvas.setAttribute('aria-hidden', 'true');
  canvas.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;';
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  document.body.append(canvas);

  const fit = () => {
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(window.innerWidth * ratio);
    canvas.height = Math.round(window.innerHeight * ratio);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  };
  fit();
  window.addEventListener('resize', fit);

  let settle = 0;
  let dive = 0;
  let phase: 'settle' | 'hold' | 'dive' = 'settle';
  let progress = 0;
  let last = -1;

  const draw = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    const st = SETTLE_EASE(settle);
    const dv = DIVE_EASE(dive);
    // The mark starts at the phone splash's size, a little smaller on a small window.
    const base = Math.min(START_PX, Math.min(w, h) * 0.42) / VIEWPORT;
    // A big screen needs a bigger dive for the mark's band to cover it.
    const zoom = ZOOM * Math.max(1, Math.max(w, h) / 900);
    // The mark grows a touch as it settles and the dive carries on from there: one motion.
    const s = base * (1 + 0.05 * st) * Math.exp(Math.log(zoom) * dv * dv);
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
    // A band of light that runs across the mark once as it settles.
    if (settle > 0.05 && settle < 0.98 && dive === 0) {
      const x = -30 + 170 * st;
      const glint = ctx.createLinearGradient(x - 18, 30, x + 18, 78);
      glint.addColorStop(0, 'rgba(218,218,218,0)');
      glint.addColorStop(0.5, 'rgba(218,218,218,0.9)');
      glint.addColorStop(1, 'rgba(218,218,218,0)');
      ctx.save();
      ctx.clip(mark);
      ctx.globalAlpha = 1;
      ctx.fillStyle = glint;
      ctx.fillRect(-20, -20, VIEWPORT + 40, VIEWPORT + 40);
      ctx.restore();
    }
    // Then the mark becomes a window onto the player.
    if (window_ > 0) {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.globalAlpha = window_;
      ctx.fillStyle = '#000';
      ctx.fill(mark);
      ctx.globalCompositeOperation = 'source-over';
    }
    // Its white edge outlines the window, the same width on screen however big the mark gets.
    if (fade > 0) {
      ctx.globalAlpha = fade;
      ctx.strokeStyle = '#fff';
      ctx.lineJoin = 'round';
      ctx.lineWidth = RIM_PX / s;
      ctx.stroke(mark);
    }
    ctx.restore();
  };

  const finish = () => {
    window.removeEventListener('resize', fit);
    canvas.remove();
  };

  const frame = (now: number) => {
    if (last >= 0) {
      const step = Math.min(now - last, 25);
      if (phase === 'settle') {
        progress += step / SETTLE_MS;
        settle = Math.min(progress, 1);
        if (progress >= 1) {
          phase = 'hold';
          progress = 0;
        }
      } else if (phase === 'hold') {
        progress += step;
        if (progress >= HOLD_MS) {
          phase = 'dive';
          progress = 0;
        }
      } else {
        progress += step / DIVE_MS;
        dive = Math.min(progress, 1);
        if (progress >= 1) {
          finish();
          return;
        }
      }
    }
    last = now;
    draw();
    requestAnimationFrame(frame);
  };
  draw();
  requestAnimationFrame(frame);
}

/** The mark's own drawing box (logo-mark.svg's viewBox). */
const VIEWPORT = 108;
/** The phone's system splash draws the mark 240 dp across; the opening starts there. */
const START_PX = 240;
const RIM_PX = 2.5;
const SETTLE_MS = 620;
const HOLD_MS = 60;
const DIVE_MS = 900;
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

const SETTLE_EASE = bezier(0.2, 0, 0, 1);
const DIVE_EASE = bezier(0.6, 0, 0.85, 0.35);

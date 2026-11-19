import markSvg from '@/assets/logo-mark.svg?raw';
import { DIVE_MS, MAX_STEP_MS, drawDive } from './openingDraw';

/**
 * The opening, as the phone app has it (app/…/ui/components/OpeningReveal.kt): OpenTune's white
 * mark on black settles with a glint of light across it, then the screen dives into it. The mark
 * turns into a window onto the player, outlined in white, and rushes toward you until the player
 * fills the screen. The player behind never moves: it is simply revealed.
 *
 * The first part is in index.html (#ot-splash), so it shows on the first paint and runs on the
 * GPU while the scripts load; waiting for them, the opening used to start late. The dive is
 * drawn on a canvas by a worker (openingWorker.ts), off the page's main thread: drawn on the
 * page, its frames waited behind the player loading its first page and it shook. Its first
 * frame matches the splash's last, and the splash goes once that frame is up, so they join up.
 * Where a canvas can't be handed to a worker, the page draws it as before.
 */
export function playOpening(enabled: boolean): void {
  const splash = document.getElementById('ot-splash');
  if (!splash) return;
  const d = /\sd="([^"]+)"/.exec(markSvg)?.[1];
  if (!enabled || !d || typeof Path2D === 'undefined') {
    splash.remove();
    return;
  }
  // The splash's settle and glint have finished, and the player has drawn its first frame.
  const settled = new Promise<void>((resolve) => setTimeout(resolve, Math.max(0, SETTLE_MS + HOLD_MS - performance.now())));
  const drawn = new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
  void Promise.all([settled, drawn]).then(() => dive(splash, d));
}

function dive(splash: HTMLElement, d: string): void {
  const canvas = document.createElement('canvas');
  canvas.setAttribute('aria-hidden', 'true');
  canvas.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;';
  const width = window.innerWidth;
  const height = window.innerHeight;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  document.body.append(canvas);
  const finish = () => {
    canvas.remove();
    splash.remove();
  };

  if (typeof canvas.transferControlToOffscreen === 'function' && typeof Worker !== 'undefined') {
    try {
      const offscreen = canvas.transferControlToOffscreen();
      const worker = new Worker(new URL('./openingWorker.ts', import.meta.url), { type: 'module' });
      // If the worker never answers, the page isn't left behind the splash.
      const giveUp = setTimeout(() => {
        worker.terminate();
        finish();
      }, 4000);
      worker.onmessage = (event: MessageEvent<string>) => {
        if (event.data === 'shown') splash.remove();
        if (event.data === 'done') {
          clearTimeout(giveUp);
          worker.terminate();
          finish();
        }
      };
      worker.onerror = () => {
        clearTimeout(giveUp);
        worker.terminate();
        finish();
      };
      worker.postMessage({ canvas: offscreen, mark: d, width, height, ratio }, [offscreen]);
      return;
    } catch {
      // Falls through to drawing on the page, on a fresh canvas: this one may have been handed over.
      canvas.remove();
      onPage(splash, d, width, height, ratio);
      return;
    }
  }
  canvas.remove();
  onPage(splash, d, width, height, ratio);
}

/** The dive drawn on the page, where a canvas can't be handed to a worker. */
function onPage(splash: HTMLElement, d: string, width: number, height: number, ratio: number): void {
  const canvas = document.createElement('canvas');
  canvas.setAttribute('aria-hidden', 'true');
  canvas.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;z-index:2147483647;pointer-events:none;';
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    splash.remove();
    return;
  }
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  const mark = new Path2D(d);
  let progress = 0;
  let last = -1;
  const frame = (now: number) => {
    if (last >= 0) progress += Math.min(now - last, MAX_STEP_MS) / DIVE_MS;
    last = now;
    if (progress >= 1) {
      canvas.remove();
      return;
    }
    drawDive(ctx, mark, width, height, progress);
    requestAnimationFrame(frame);
  };
  // The canvas takes the splash's place in one frame: drawn as it was left, then the splash goes.
  drawDive(ctx, mark, width, height, 0);
  document.body.append(canvas);
  splash.remove();
  requestAnimationFrame(frame);
}

/** The splash's settle and glint (index.html), and the hold after them, as on the phone. */
const SETTLE_MS = 620;
const HOLD_MS = 60;

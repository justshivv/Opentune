/// <reference lib="webworker" />
import { DIVE_MS, MAX_STEP_MS, drawDive } from './openingDraw';

/**
 * Draws the opening's dive on a canvas handed over from the page (opening.ts), so its frames
 * don't wait behind the page's own work while the player is still loading its first page.
 * Tells the page when its first frame is up ('shown') and when the dive is over ('done').
 */
type Start = { canvas: OffscreenCanvas; mark: string; width: number; height: number; ratio: number };

const scope = self as unknown as DedicatedWorkerGlobalScope;

scope.onmessage = (event: MessageEvent<Start>) => {
  const { canvas, mark: d, width, height, ratio } = event.data;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    scope.postMessage('done');
    return;
  }
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  const mark = new Path2D(d);
  const next: (callback: (now: number) => void) => void =
    typeof scope.requestAnimationFrame === 'function'
      ? (callback) => scope.requestAnimationFrame(callback)
      : (callback) => setTimeout(() => callback(performance.now()), 16);

  let progress = 0;
  let last = -1;
  drawDive(ctx, mark, width, height, 0);
  scope.postMessage('shown');
  const frame = (now: number) => {
    if (last >= 0) progress += Math.min(now - last, MAX_STEP_MS) / DIVE_MS;
    last = now;
    if (progress >= 1) {
      ctx.clearRect(0, 0, width, height);
      scope.postMessage('done');
      return;
    }
    drawDive(ctx, mark, width, height, progress);
    next(frame);
  };
  next(frame);
};

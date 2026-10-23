/**
 * The full player opening out of the mini player and folding back into it, as
 * the app's PlayerSheet does: the card's edges run between the mini player's
 * and the window's, the cover flies between the two covers, and the rest
 * fades in once the card is mostly open.
 */
export const OPEN_MS = 500;
export const CLOSE_MS = 460;
const EASE = 'cubic-bezier(0.3, 0.1, 0.2, 1)';

/** Off with Settings › Motion, or when the system asks for less motion. */
export function motionAllowed(): boolean {
  return document.documentElement.dataset.motion !== 'false' && !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/** The first of [selector]'s elements that is on screen. */
function shown(selector: string): HTMLElement | null {
  for (const el of document.querySelectorAll<HTMLElement>(selector)) {
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0 && el.checkVisibility?.() !== false) return el;
  }
  return null;
}

function clipAt(r: DOMRect, radius: number): string {
  return `inset(${r.top}px ${innerWidth - r.right}px ${innerHeight - r.bottom}px ${r.left}px round ${radius}px)`;
}

/** The transform that puts [to] (measured as it stands) over [from], about its centre; [k] is the scale it already has. */
function flight(from: DOMRect, to: DOMRect, k: number): string {
  const dx = from.left + from.width / 2 - (to.left + to.width / 2);
  const dy = from.top + from.height / 2 - (to.top + to.height / 2);
  return `translate(${dx / k}px, ${dy / k}px) scale(${from.width / to.width}, ${from.height / to.height})`;
}

/**
 * Runs the open or close animation on [root]; [cover] is the big cover,
 * [offset] how far a touch drag had already pulled the sheet down. Resolves
 * when it has finished, at once when there is nothing to animate.
 */
const live = new WeakMap<HTMLElement, Animation[]>();

export function morphPlayer(root: HTMLElement, cover: HTMLElement | null, opening: boolean, offset = 0): Promise<void> {
  // Reopened while it was still closing: the new motion takes over from the old one.
  live.get(root)?.forEach((a) => a.cancel());
  const fades = [...root.querySelectorAll<HTMLElement>('[data-morph-fade]')];
  if (!motionAllowed()) {
    if (!opening) return Promise.resolve();
    const fade = root.animate({ opacity: [0, 1] }, { duration: 160 });
    live.set(root, [fade]);
    return fade.finished.then(() => undefined, () => undefined);
  }
  const ms = opening ? OPEN_MS : CLOSE_MS;
  const card = shown('[data-player-origin]');
  const small = shown('[data-cover-origin]');
  const running: Animation[] = [];
  const order = <T,>(frames: [T, T]) => (opening ? frames : [frames[1], frames[0]]);

  if (card) {
    const radius = Number.parseFloat(getComputedStyle(card).borderTopLeftRadius) || 28;
    running.push(root.animate({
      clipPath: order([clipAt(card.getBoundingClientRect(), radius), 'inset(0px 0px 0px 0px round 0px)']),
      translate: opening ? ['0 0', '0 0'] : [`0 ${offset}px`, '0 0'],
    }, { duration: ms, easing: EASE, fill: opening ? 'none' : 'forwards' }));
  } else {
    running.push(root.animate({ opacity: order([0, 1]), translate: order(['0 32px', '0 0']) }, { duration: ms, easing: EASE, fill: opening ? 'none' : 'forwards' }));
  }

  if (cover && small) {
    const to = cover.getBoundingClientRect();
    if (to.width > 0) {
      const at = new DOMRect(to.left, to.top - offset, to.width, to.height);
      const k = Number.parseFloat(getComputedStyle(cover).scale) || 1;
      // The small cover is the one flying: hidden while its copy travels.
      small.style.visibility = 'hidden';
      const fly = cover.animate({ transform: order([flight(small.getBoundingClientRect(), at, k), 'none']), borderRadius: order(['10px', '28px']) }, { duration: ms, easing: EASE, fill: opening ? 'none' : 'forwards' });
      void fly.finished.finally(() => (small.style.visibility = ''));
      running.push(fly);
    }
  }

  for (const el of fades) {
    running.push(el.animate({ opacity: order([0, 1]), translate: order(['0 12px', '0 0']) }, opening
      ? { duration: ms * 0.7, delay: ms * 0.3, easing: EASE, fill: 'backwards' }
      : { duration: ms * 0.35, easing: 'ease-in', fill: 'forwards' }));
  }
  live.set(root, running);
  return Promise.all(running.map((a) => a.finished)).then(() => undefined);
}
